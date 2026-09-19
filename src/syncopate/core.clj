(ns syncopate.core
  "Syncopate — a production-quality ragtime adaptor for Datalevin.

  Public surface:
    - `store`            construct the ragtime DataStore (re-exported from
                         `syncopate.store`).
    - `load-resources`   build migrations from EDN/CLJ files on the classpath.
    - `->migration`      build a single migration from a spec map.
    - `pending` / `status`  observability without running anything.

  Migrations are EDN-first. A migration spec is a map:

    {:id \"0002-split-names\"          ; optional; defaults to the filename
     :up   <step | [step ...]>
     :down <step | [step ...]>        ; optional if :up is purely additive
     :transaction? true               ; optional, default true
     :irreversible? false}            ; optional

  A *step* is one of:
    - {:schema/create {attr def ...}} add NEW attributes     (auto-invertible)
    - {:schema/alter  {attr def ...}} modify existing attrs  (needs :down)
    - {:schema/remove [attr ...]}     retract + drop attrs   (needs :down)
    - {:tx [tx-data ...]}             raw `transact!` data
    - a fully-qualified symbol        resolved to (fn [conn] ...) and called
    - (in .clj files) an actual fn    called as (fn [conn] ...)

  Sugar:
    - `:up`/`:down` may be a single step instead of a vector.
    - Omit `:down` and it is derived automatically iff every `:up` step is a
      `:schema/create` (adds → removes). Otherwise supply `:down`, or set
      `:irreversible? true` to opt out of rollback with a clear error.

  The obsolete bare `:schema` step (ambiguous between create and alter) is
  rejected with an actionable error — see `->migration`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]
            [ragtime.protocols :as rp]
            [taoensso.trove :as trove]
            [syncopate.schema :as schema]
            [syncopate.store :as store])
  (:import [java.io File]
           [java.util.jar JarFile]))

;; ---------------------------------------------------------------------------
;; Step engine
;; ---------------------------------------------------------------------------

(defn- as-steps
  "Normalise a direction value into an ordered vector of steps."
  [x]
  (cond (nil? x)    []
        (vector? x) x
        :else       [x]))

(defn- tx?
  "True if `step` is a raw-transaction step: a `:tx` map naming nothing else."
  [step]
  (and (map? step) (== 1 (count step)) (contains? step :tx)))

(defn- ambiguous-step?
  "True if map `step` has more than one key. A well-formed step is a
  single-operation map (see `tx?` and the `syncopate.schema` predicates), so a map
  with several keys — whether it combines operations or carries a stray key — is
  rejected rather than silently reduced to one. (An empty map names nothing and is
  not ambiguous; it falls through to :unknown.)"
  [step]
  (and (map? step) (< 1 (count step))))

(defn- step-phase [step]
  (cond (symbol? step)               :fn
        (or (fn? step) (var? step))  :fn
        (tx? step)                   :tx
        (schema/schema-delta? step)  :schema
        (ambiguous-step? step)       :ambiguous
        :else                        :unknown))

(defn- step-summary
  "A compact, log-safe rendering of a step — never the raw tx-data or fn body."
  [step]
  (cond
    (symbol? step) {:phase :fn :fn step}
    (var? step)    {:phase :fn :fn (symbol (str (:ns (meta step))) (str (:name (meta step))))}
    (fn? step)     {:phase :fn :fn :anonymous}
    (tx? step)     {:phase :tx :tx-count (count (:tx step))}
    (schema/create? step) {:phase :schema :op :create :attrs (vec (keys (:schema/create step)))}
    (schema/alter?  step) {:phase :schema :op :alter  :attrs (vec (keys (:schema/alter step)))}
    (schema/remove? step) {:phase :schema :op :remove :attrs (vec (:schema/remove step))}
    (ambiguous-step? step) {:phase :ambiguous :keys (vec (keys step))}
    :else {:phase :unknown}))

(defn- ms-since [^long start-ns]
  (quot (- (System/nanoTime) start-ns) 1000000))

(defn- run-step!
  "Execute one migration step against connection `conn`."
  [conn step]
  (cond
    (symbol? step)
    (if-let [f (requiring-resolve step)]
      (f conn)
      (throw (ex-info (str "Cannot resolve migration function " step)
                      {:symbol step})))

    (or (var? step) (fn? step))
    (step conn)

    (tx? step)
    (d/transact! conn (:tx step))

    (schema/schema-delta? step)
    (schema/apply-delta! conn step)

    (ambiguous-step? step)
    (throw (ex-info (str "A step must name exactly one of :tx / :schema/create / "
                         ":schema/alter / :schema/remove; this step names several. "
                         "Split them into separate steps.")
                    {:step step :syncopate/ambiguous-step true}))

    (schema/legacy-schema-step? step)
    (throw (ex-info (str "The `:schema` step is obsolete — use `:schema/create` "
                         "for new attributes or `:schema/alter` to modify existing "
                         "ones. See doc/schema-migrations.md.")
                    {:step step :syncopate/legacy-schema true}))

    :else
    (throw (ex-info (str "Unrecognised migration step: " (pr-str step))
                    {:step step}))))

(defn- run-steps!
  "Run `step-coll` in order against connection `conn` (no transaction of its
  own). Any failure is re-thrown with the migration id, direction, offending
  step and phase so the cause is never ambiguous (ragtime issues #130/#146)."
  [conn id direction step-coll]
  (doseq [step step-coll]
    (trove/log! {:level :debug :id :syncopate/step
                 :msg  (str "step: " id " " (name direction))
                 :data (assoc (step-summary step) :migration/id id :direction direction)})
    (try
      (run-step! conn step)
      (catch Throwable t
        (trove/log! {:level :error :id :syncopate/migration-failed
                     :msg   (format "Migration %s failed running %s" id (name direction))
                     :error t
                     :data  {:migration/id id
                             :direction    direction
                             :phase        (step-phase step)
                             :step         (step-summary step)}})
        (throw (ex-info (format "Syncopate migration %s failed running %s"
                                id (name direction))
                        {:syncopate/id id
                         :direction    direction
                         :phase        (step-phase step)
                         :step         step}
                        t))))))

(defn- run-direction!
  "Run a migration direction, wrapped in a Datalevin transaction unless the
  migration opts out."
  [conn id direction transaction? step-coll]
  (if transaction?
    (d/with-transaction [c conn] (run-steps! c id direction step-coll))
    (run-steps! conn id direction step-coll)))

;; ---------------------------------------------------------------------------
;; Migration record
;; ---------------------------------------------------------------------------

(defrecord SyncopateMigration [id up down transaction? irreversible?]
  rp/Migration
  (id [_] id)
  (run-up! [_ store]
    (run-direction! (:conn store) id :up transaction? (as-steps up)))
  (run-down! [_ store]
    (when irreversible?
      (throw (ex-info (format "Migration %s is irreversible and cannot be rolled back" id)
                      {:syncopate/id id})))
    (run-direction! (:conn store) id :down transaction? (as-steps down))))

(defn- auto-down
  "Derive a migration's :down by inverting a :up made purely of `:schema/create`
  steps (creates → removes), logging the derivation. Throws if the :up isn't
  purely `:schema/create`, so a missing :down can't silently leave a migration
  unreversible."
  [id up-steps]
  (when-not (and (seq up-steps) (every? schema/additive? up-steps))
    (throw (ex-info (format (str "Migration %s has no :down and its :up is not "
                                 "purely :schema/create; :schema/alter, "
                                 ":schema/remove and data/fn steps need an "
                                 "explicit :down (or set :irreversible? true)")
                            id)
                    {:syncopate/id id})))
  (trove/log! {:level :debug :id :syncopate/auto-down
               :msg  (str "Auto-derived :down for " id)
               :data {:migration/id (str id)}})
  (mapv schema/invert (reverse up-steps)))

(defn- legacy-schema-guidance
  "Actionable upgrade error for a migration still using the obsolete bare
  `:schema` step. Forks the author between `:schema/create` and `:schema/alter`,
  and — when the :up is purely legacy-`:schema` — includes the naive auto-`:down`
  to copy-paste, which also makes the destructive rollback obvious."
  [id up-steps]
  (let [legacy-up  (filter schema/legacy-schema-step? up-steps)
        naive-down (mapv (fn [s] {:schema/remove (vec (keys (:schema s)))})
                         (reverse legacy-up))
        pr-down    (binding [*print-namespace-maps* false] (pr-str naive-down))]
    (str "Migration " id " uses the obsolete `:schema` step, which was ambiguous "
         "(it could add new attributes OR modify existing ones). Replace it:\n"
         "  - new attributes  -> :schema/create  (rollback auto-derived)\n"
         "  - modify existing -> :schema/alter   (supply an explicit :down)\n"
         (when (and (seq legacy-up) (every? schema/legacy-schema-step? up-steps))
           (str "\nIf these are all NEW attributes, switch to :schema/create; its "
                "auto-derived rollback would be:\n"
                "  :down " pr-down "\n"
                "If any already exist, use :schema/alter with an explicit :down "
                "instead — the rollback above would DROP the attribute(s) and "
                "delete their data.\n"))
         "\nSee doc/schema-migrations.md.")))

(defn ->migration
  "Build a `SyncopateMigration` from a spec map (see the namespace docstring)."
  [{:keys [id up down transaction? irreversible?] :as spec}]
  (when-not id
    (throw (ex-info "Migration spec is missing :id" {:spec spec})))
  (let [up-steps   (as-steps up)
        down-steps (as-steps down)]
    (when (some schema/legacy-schema-step? (concat up-steps down-steps))
      (throw (ex-info (legacy-schema-guidance id up-steps)
                      {:syncopate/id id :syncopate/legacy-schema true})))
    (when-let [amb (first (filter ambiguous-step? (concat up-steps down-steps)))]
      (throw (ex-info
              (format (str "Migration %s has a step that names more than one "
                           "operation %s — a step must perform exactly one of :tx / "
                           ":schema/create / :schema/alter / :schema/remove. Split "
                           "them into separate steps.")
                      id (vec (keys amb)))
              {:syncopate/id id :step amb :syncopate/ambiguous-step true})))
    (let [down' (cond
                  (contains? spec :down) down
                  (not irreversible?)    (auto-down id up-steps))]
      (map->SyncopateMigration
       {:id            (str id)
        :up            up
        :down          down'
        :transaction?  (if (contains? spec :transaction?) transaction? true)
        :irreversible? (boolean irreversible?)}))))

;; ---------------------------------------------------------------------------
;; Loaders
;; ---------------------------------------------------------------------------

(def ^:private migration-file-re #"(?i).*\.(edn|clj)$")

(defn- file->id [name]
  (str/replace name #"(?i)\.(edn|clj)$" ""))

(defn- parse-spec
  "Parse migration source `content` from a file named `fname`. EDN files are
  read as data (symbols stay symbols, resolved lazily at run time); CLJ files
  are evaluated and must yield a spec map (whose steps may be real fns)."
  [fname content]
  (let [spec (if (str/ends-with? (str/lower-case fname) ".clj")
               (load-string content)
               (edn/read-string content))]
    (->migration (update spec :id #(or % (file->id fname))))))

(defn- log-loaded
  "Emit a load summary — info when migrations were found, warn when none were
  (a common footgun: wrong path / migrations not on the classpath)."
  [migs source]
  (if (seq migs)
    (trove/log! {:level :info :id :syncopate/loaded
                 :msg  (format "Loaded %d migration(s) from %s" (count migs) source)
                 :data {:count (count migs) :source source :ids (mapv :id migs)}})
    (trove/log! {:level :warn :id :syncopate/no-migrations
                 :msg  (str "No migrations found at " source)
                 :data {:source source}}))
  migs)

(defn- load-directory
  "Raw producer: parse every migration file in filesystem directory `path`.
  Unsorted, unlogged — `load-resources` adds the shared finalize tail."
  [path]
  (->> (.listFiles (io/file path))
       (filter #(re-matches migration-file-re (.getName ^File %)))
       (map (fn [^File f] (parse-spec (.getName f) (slurp f))))))

(defn- jar-resources
  "Entries under classpath dir `path` inside the jar at `jar-url`."
  [^java.net.URL jar-url path]
  (let [jar-path (-> (.getPath jar-url)
                     (str/replace-first #"^file:" "")
                     (str/replace #"!.*$" ""))
        prefix   (str (str/replace path #"/$" "") "/")]
    (with-open [jar (JarFile. jar-path)]
      (->> (enumeration-seq (.entries jar))
           (map #(.getName ^java.util.jar.JarEntry %))
           (filter #(and (str/starts-with? % prefix)
                         (re-matches migration-file-re %)))
           (mapv (fn [entry]
                   (let [fname (subs entry (count prefix))]
                     (with-open [in (.getInputStream jar (.getJarEntry jar entry))]
                       (parse-spec fname (slurp in))))))))))

(defn- resource-migrations
  "Raw producer: parse migrations from a resolved classpath resource URL,
  dispatching on protocol (exploded file dir vs jar)."
  [^java.net.URL url path]
  (case (.getProtocol url)
    "file" (load-directory (io/file url))
    "jar"  (jar-resources url path)
    (throw (ex-info (str "Unsupported resource protocol: " (.getProtocol url))
                    {:url url}))))

(defn load-resources
  "Load migrations from a classpath prefix, sorted by id. Works from both
  exploded directories (dev) and jars (production)."
  [path]
  (if-let [url (io/resource path)]
    (as-> (resource-migrations url path) $
      (sort-by :id $)
      (vec $)
      (log-loaded $ (str "classpath:" path)))
    (log-loaded [] (str "classpath:" path))))

;; ---------------------------------------------------------------------------
;; Store + observability
;; ---------------------------------------------------------------------------

(defn store
  "Construct a Syncopate ragtime DataStore over a live Datalevin connection
  (embedded or client/server). See `syncopate.store/store` for options."
  ([conn] (store/store conn))
  ([conn opts] (store/store conn opts)))

(defn close!
  "Release resources a store holds beyond its connection — for a client/server
  store, the KV client opened to the server. No-op for embedded stores (the caller
  closes the connection)."
  [store]
  (store/close! store))

(defn pending
  "The migrations from `migrations` not yet recorded as applied in `store`,
  in order (ragtime issue #127)."
  [store migrations]
  (let [applied (set (rp/applied-migration-ids store))]
    (vec (remove #(applied (rp/id %)) migrations))))

(defn status
  "A report of applied vs pending migration ids (ragtime issues #85/#127)."
  [store migrations]
  {:applied (vec (rp/applied-migration-ids store))
   :pending (mapv rp/id (pending store migrations))})

;; ---------------------------------------------------------------------------
;; Atomic helpers
;;
;; ragtime.core/migrate applies the body and records the applied-id as two
;; separate protocol calls -> two transactions. Because Syncopate keeps the
;; applied-id in a KV DBI on the *same* LMDB environment as the data, these
;; helpers fold both writes into one `d/with-transaction`: the migration body
;; and its bookkeeping commit or roll back together (verified: a throw inside
;; undoes both the datalog and the KV write). For `:transaction? false`
;; migrations there is no transaction to fold into, so they fall back to the
;; two-step protocol path (the only case where the seam remains).
;; ---------------------------------------------------------------------------

(defn migrate!
  "Atomically apply `migration`'s `:up` and record it as applied. Returns `store`."
  [store migration]
  (let [conn  (:conn store)
        dbi   (:dbi-name store)
        id    (rp/id migration)
        txn?  (boolean (:transaction? migration))
        steps (as-steps (:up migration))
        t0    (System/nanoTime)]
    (trove/log! {:level :debug :id :syncopate/migrating
                 :msg  (str "Migrating up: " id)
                 :data {:migration/id id :transactional? txn? :steps (count steps)}})
    (if (and txn? (not (:remote? store)))
      ;; embedded: fold the applied-id write into the migration's own transaction
      (let [seq (store/next-seq (:kv store) dbi)]  ; read on the store's KV, before the txn
        (d/with-transaction [c conn]
          (run-steps! c id :up steps)
          (store/record! (store/conn->lmdb c) dbi id seq)))  ; write via the txn conn → joins it
      ;; client/server (or :transaction? false): body runs (in its own datalog
      ;; transaction when txn?), then the applied-id is recorded separately — the
      ;; two-transaction seam (the KV client can't join a remote datalog txn).
      (do (rp/run-up! migration store)
          (rp/add-migration-id store id)))
    (trove/log! {:level :info :id :syncopate/migrated
                 :msg  (str "Applied migration: " id)
                 :data {:migration/id id :direction :up :steps (count steps) :ms (ms-since t0)}}))
  store)

(defn rollback!
  "Atomically apply `migration`'s `:down` and un-record it. Returns `store`."
  [store migration]
  (let [conn  (:conn store)
        dbi   (:dbi-name store)
        id    (rp/id migration)
        txn?  (boolean (:transaction? migration))
        steps (as-steps (:down migration))
        t0    (System/nanoTime)]
    (when (:irreversible? migration)
      (throw (ex-info (format "Migration %s is irreversible and cannot be rolled back" id)
                      {:syncopate/id id})))
    (trove/log! {:level :debug :id :syncopate/rolling-back
                 :msg  (str "Rolling back: " id)
                 :data {:migration/id id :transactional? txn? :steps (count steps)}})
    (if (and txn? (not (:remote? store)))
      (d/with-transaction [c conn]
        (run-steps! c id :down steps)
        (store/unrecord! (store/conn->lmdb c) dbi id))
      (do (rp/run-down! migration store)
          (rp/remove-migration-id store id)))
    (trove/log! {:level :info :id :syncopate/rolled-back
                 :msg  (str "Rolled back migration: " id)
                 :data {:migration/id id :direction :down :steps (count steps) :ms (ms-since t0)}}))
  store)

(defn migrate-all!
  "Apply every pending migration from `migrations` in order, each atomically.
  Idempotent: a second call with nothing pending is a no-op. Returns `store`."
  [store migrations]
  (let [todo (pending store migrations)
        t0   (System/nanoTime)]
    (if (empty? todo)
      (trove/log! {:level :info :id :syncopate/nothing-pending
                   :msg "No pending migrations — nothing to do"})
      (do
        (trove/log! {:level :info :id :syncopate/migrate-all
                     :msg  (format "Applying %d pending migration(s)" (count todo))
                     :data {:count (count todo) :pending (mapv rp/id todo)}})
        (doseq [m todo]
          (migrate! store m))
        (trove/log! {:level :info :id :syncopate/migrate-all-done
                     :msg  (format "Applied %d migration(s)" (count todo))
                     :data {:count (count todo) :applied (mapv rp/id todo) :ms (ms-since t0)}}))))
  store)

(defn rollback-last!
  "Atomically roll back the most recently applied migration, resolving its
  `:down` from `migrations`. No-op if nothing is applied. Returns `store`."
  [store migrations]
  (if-let [last-id (last (rp/applied-migration-ids store))]
    (if-let [m (first (filter #(= last-id (rp/id %)) migrations))]
      (do (trove/log! {:level :info :id :syncopate/rollback-last
                       :msg  (str "Rolling back last applied migration: " last-id)
                       :data {:migration/id last-id}})
          (rollback! store m))
      (throw (ex-info (format "Cannot roll back %s: not found among the provided migrations" last-id)
                      {:syncopate/id last-id})))
    (trove/log! {:level :info :id :syncopate/nothing-applied
                 :msg "No applied migrations — nothing to roll back"}))
  store)
