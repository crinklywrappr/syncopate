(ns syncopate.store
  "The ragtime DataStore half of Syncopate.

  Applied-migration state lives in a dedicated key-value DBI on the *same*
  environment the app's Datalog connection already holds — never as datoms. This
  holds for both connection kinds:

  - **embedded** (a local LMDB directory): reuse the connection's own environment
    via the backing `datalevin.storage.Store`'s `lmdb` handle (Datalevin forbids a
    second LMDB connection to the same directory in one process, so we can't just
    `open-kv`).
  - **client/server** (a `dtlv://` URI): the remote Datalog store does not expose
    the KV surface, so open a KV *client* to the SAME server database via
    `open-kv`. It shares the datalog connection's server environment (its DBIs sit
    alongside `datalevin/eav` etc.), so the promise holds — a KV DBI on the same
    env, never datoms. (Because that KV client is a separate session, it can't join
    the datalog `with-transaction`, so client/server migrations record with the
    two-transaction seam rather than the single-transaction fold.)"
  (:require [datalevin.core :as d]
            [ragtime.protocols :as rp]
            [taoensso.trove :as trove])
  (:import [java.util Date]
           [datalevin.remote DatalogStore]))

(def default-dbi-name "__syncopate_migrations")

(defn- remote-conn?
  "True if `conn` is a client/server (`dtlv://`) connection rather than embedded."
  [conn]
  (instance? DatalogStore (:store @conn)))

(defn conn->lmdb
  "The KV/LMDB handle backing an *embedded* Datalog connection, via datalevin's
  supported `datalog-kv` (rather than reaching into the storage internals). Works
  for a transaction-bound connection too, so the atomic helpers in `syncopate.core`
  can write the applied-id inside the migration's own `with-transaction`."
  [conn]
  (d/datalog-kv conn))

(defn- open-kv-handle
  "The KV `ILMDB` handle for a connection's applied-migration DBI. Embedded → the
  connection's own env (`conn->lmdb`). Client/server → a KV client opened to the
  SAME server database via `open-kv`."
  [conn]
  (if (remote-conn? conn)
    (d/open-kv (.-uri ^DatalogStore (:store @conn)))
    (conn->lmdb conn)))

(defn ensure-store!
  "Open (creating if absent) the migrations DBI on `lmdb`. Idempotent — our
  equivalent of the SQL adaptors' `ensure-migrations-table-exists`."
  [lmdb dbi-name]
  (d/open-dbi lmdb dbi-name))

(defn next-seq
  "The next monotonic application sequence number for the migrations DBI on `lmdb`:
  one past the largest `:seq` on record (never reused, so it's robust to the gaps
  `unrecord!` leaves). Ordering by `:seq` reflects true application order, immune to
  wall-clock millisecond ties."
  [lmdb dbi-name]
  (ensure-store! lmdb dbi-name)
  (transduce
   (map (fn [[_ v]] (:seq v -1)))
   (completing max inc)
   -1 (d/get-range lmdb dbi-name [:all])))

(defn record!
  "Record `id` as applied with application sequence `seq` on `lmdb` (which may be a
  transaction-bound handle). Assumes the DBI already exists (see `ensure-store!`)."
  [lmdb dbi-name id seq]
  (d/transact-kv lmdb [[:put dbi-name (str id) {:applied-at (Date.) :seq seq}]])
  (trove/log! {:level :debug :id :syncopate/recorded
               :msg  (str "Recorded applied migration: " id)
               :data {:migration/id (str id) :dbi dbi-name :seq seq}}))

(defn unrecord!
  "Remove `id` from the applied set on `lmdb` (which may be transaction-bound)."
  [lmdb dbi-name id]
  (d/transact-kv lmdb [[:del dbi-name (str id)]])
  (trove/log! {:level :debug :id :syncopate/unrecorded
               :msg  (str "Removed applied migration: " id)
               :data {:migration/id (str id) :dbi dbi-name}}))

(defrecord SyncopateStore [conn dbi-name kv remote?]
  rp/DataStore
  (add-migration-id [_ id]
    (record! kv dbi-name id (next-seq kv dbi-name)))

  (remove-migration-id [_ id]
    (unrecord! kv dbi-name id))

  (applied-migration-ids [_]
    ;; Ordered by application :seq (true application order; :applied-at / id are
    ;; only fallbacks for any legacy record). Deliberately NOT wrapped in a catch:
    ;; an empty history is an empty DBI, and a real failure must surface.
    (ensure-store! kv dbi-name)
    (->> (into [] (d/get-range kv dbi-name [:all]))
         (sort-by (fn [[k v]] [(:seq v -1) (:applied-at v) k]))
         (mapv first))))

(defn- warm!
  "Datalevin quirk: a freshly `open-dbi`'d DBI is invisible to `transact-kv` on a
  `with-transaction`-bound connection until a *realized* read has happened first.
  Warm the DBI once at store construction so the first `migrate!`/`rollback!` on a
  cold store is safe."
  [lmdb dbi-name]
  (ensure-store! lmdb dbi-name)
  (into [] (d/get-range lmdb dbi-name [:all]))
  nil)

(defn store
  "Construct a Syncopate DataStore over a live Datalevin connection — embedded or
  client/server (`dtlv://`). For a client/server connection this opens a KV client
  to the same server database; release it with `close!` when done.

  Options:
    :dbi-name - name of the KV DBI holding applied-migration state
                (default \"__syncopate_migrations\")."
  ([conn] (store conn {}))
  ([conn {:keys [dbi-name] :or {dbi-name default-dbi-name}}]
   (let [remote? (remote-conn? conn)
         kv      (open-kv-handle conn)]
     (warm! kv dbi-name)
     (->SyncopateStore conn dbi-name kv remote?))))

(defn close!
  "Release resources the store holds beyond its `conn`. For a client/server store
  this closes the KV client opened to the server; embedded stores hold the
  connection's own env, so it's a no-op (the caller closes the connection)."
  [store]
  (when (:remote? store) (d/close-kv (:kv store)))
  nil)
