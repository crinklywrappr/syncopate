(ns syncopate.store
  "The ragtime DataStore half of Syncopate.

  Applied-migration state lives in a dedicated key-value DBI on the *same*
  environment the app's Datalog connection already holds — never as datoms. This
  holds for both connection kinds, via datalevin's `datalog-kv`:

  - **embedded** (a local LMDB directory): `datalog-kv` returns the connection's
    own LMDB handle.
  - **client/server** (a `dtlv://` URI): `datalog-kv` returns a KV *view* that
    shares the remote Datalog store's clients and transaction. The applied-id DBI
    sits on the SAME server database (alongside `datalevin/eav` etc.), and a write
    on a transaction-bound connection joins the migration's `with-transaction`. No
    separate KV session is opened, so embedded and client/server fold identically."
  (:require [datalevin.core :as d]
            [ragtime.protocols :as rp]
            [taoensso.trove :as trove])
  (:import [java.util Date]))

(def default-dbi-name "__syncopate_migrations")

(defn conn->lmdb
  "The KV handle backing a Datalog connection, via datalevin's `datalog-kv`. Works
  embedded and client/server, and on a transaction-bound connection, so the atomic
  helpers in `syncopate.core` can write the applied-id inside the migration's own
  `with-transaction`. On client/server it is a KV view sharing the connection's
  clients and transaction, not a separate session."
  [conn]
  (d/datalog-kv conn))

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

(defrecord SyncopateStore [conn dbi-name kv]
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
  client/server (`dtlv://`). The applied-migration DBI lives on the connection's
  own environment (embedded) or the same server database (client/server), reached
  via `datalog-kv`.

  Options:
    :dbi-name - name of the KV DBI holding applied-migration state
                (default \"__syncopate_migrations\")."
  ([conn] (store conn {}))
  ([conn {:keys [dbi-name] :or {dbi-name default-dbi-name}}]
   (let [kv (conn->lmdb conn)]
     (warm! kv dbi-name)
     (->SyncopateStore conn dbi-name kv))))

(defn close!
  "Release resources the store holds beyond its `conn`. The applied-migration KV
  handle shares the connection's own environment (embedded) or its server clients
  (client/server), so there is nothing extra to release. This is a no-op kept for
  API symmetry; the caller closes the connection."
  [_store]
  nil)
