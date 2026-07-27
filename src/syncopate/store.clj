(ns syncopate.store
  "The ragtime DataStore half of Syncopate.

  Applied-migration state lives in a dedicated key-value DBI on the *same*
  LMDB environment the app's Datalog connection already holds open — never as
  datoms. This keeps migration bookkeeping out of the application's schema and
  query space (it stays invisible to `d/schema` and Datalog queries) and works
  under `:closed-schema? true`.

  Why reach into the connection instead of opening our own KV handle: Datalevin
  forbids two LMDB connections to the same directory in one process — `open-kv`
  on a directory an open Datalog conn owns throws. The supported path for an
  embedded library is to reuse the connection's own environment, obtained via
  the backing Store's `lmdb` handle."
  (:require [datalevin.core :as d]
            [ragtime.protocols :as rp]
            [taoensso.trove :as trove])
  (:import [java.util Date]
           [datalevin.storage Store]))

(def default-dbi-name "__syncopate_migrations")

(defn conn->lmdb
  "The KV/LMDB handle backing a live Datalog connection. Works for a
  transaction-bound connection too, so the atomic helpers in `syncopate.core`
  can write the applied-id inside the migration's own `with-transaction`."
  [conn]
  (.lmdb ^Store (:store @conn)))

(defn ensure-store!
  "Open (creating if absent) the migrations DBI on `conn`'s environment.
  Idempotent — our equivalent of the SQL adaptors' `ensure-migrations-table-exists`.
  Must be called on the base connection *before* entering `with-transaction`
  (opening a DBI inside a write transaction is unsafe)."
  [conn dbi-name]
  (d/open-dbi (conn->lmdb conn) dbi-name))

(defn next-seq
  "The next monotonic application sequence number for `conn`'s migrations DBI:
  one past the largest `:seq` on record (never reused, so it's robust to the gaps
  `unrecord!` leaves). Ordering by `:seq` reflects true application order, immune
  to wall-clock millisecond ties."
  [conn dbi-name]
  (ensure-store! conn dbi-name)
  (transduce
   (map (fn [[_ v]] (:seq v -1)))
   (completing max inc)
   -1 (d/get-range (conn->lmdb conn) dbi-name [:all])))

(defn record!
  "Record `id` as applied with application sequence `seq`, via `conn` (which may be
  transaction-bound). Assumes the DBI already exists (see `ensure-store!`)."
  [conn dbi-name id seq]
  (d/transact-kv (conn->lmdb conn) [[:put dbi-name (str id) {:applied-at (Date.) :seq seq}]])
  (trove/log! {:level :debug :id :syncopate/recorded
               :msg  (str "Recorded applied migration: " id)
               :data {:migration/id (str id) :dbi dbi-name :seq seq}}))

(defn unrecord!
  "Remove `id` from the applied set, via `conn` (which may be transaction-bound)."
  [conn dbi-name id]
  (d/transact-kv (conn->lmdb conn) [[:del dbi-name (str id)]])
  (trove/log! {:level :debug :id :syncopate/unrecorded
               :msg  (str "Removed applied migration: " id)
               :data {:migration/id (str id) :dbi dbi-name}}))

(defrecord SyncopateStore [conn dbi-name]
  rp/DataStore
  (add-migration-id [_ id]
    (ensure-store! conn dbi-name)
    (record! conn dbi-name id (next-seq conn dbi-name)))

  (remove-migration-id [_ id]
    (ensure-store! conn dbi-name)
    (unrecord! conn dbi-name id))

  (applied-migration-ids [_]
    ;; Ordered by application time so ragtime rolls back most-recent-first.
    ;; Deliberately NOT wrapped in a catch: an empty history is an empty DBI
    ;; (zero entries), and a real failure must surface, not masquerade as "[]".
    (let [lmdb (conn->lmdb conn)]
      (ensure-store! conn dbi-name)
      ;; get-range returns a datalevin SpillableVector (its .toArray is
      ;; abstract, so sort-by would blow up) — realise into a plain vector first.
      (->> (into [] (d/get-range lmdb dbi-name [:all]))
           ;; :seq is the true application order (monotonic); :applied-at / id are
           ;; only fallbacks for any legacy record written before :seq existed.
           (sort-by (fn [[k v]] [(:seq v -1) (:applied-at v) k]))
           (mapv first)))))

(defn- warm!
  "Datalevin quirk (surfaced by generative testing): a freshly `open-dbi`'d DBI is
  invisible to `transact-kv` on a `with-transaction`-bound connection until a
  *realized* read has happened on the base connection first. Without this, the very
  first `migrate!`/`rollback!` on a cold store fails with `\"…\" is not open`. We do
  the warming read once at store construction so every subsequent op is safe."
  [conn dbi-name]
  (ensure-store! conn dbi-name)
  (into [] (d/get-range (conn->lmdb conn) dbi-name [:all]))
  nil)

(defn store
  "Construct a Syncopate DataStore over a live Datalevin Datalog connection.

  Options:
    :dbi-name - name of the KV DBI holding applied-migration state
                (default \"__syncopate_migrations\")."
  ([conn] (store conn {}))
  ([conn {:keys [dbi-name] :or {dbi-name default-dbi-name}}]
   (warm! conn dbi-name)
   (->SyncopateStore conn dbi-name)))
