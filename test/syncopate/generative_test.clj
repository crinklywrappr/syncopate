(ns syncopate.generative-test
  (:require [clojure.set :as set]
            [clojure.test :refer [is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datalevin.core :as d]
            [ragtime.protocols :as rp]
            [syncopate.core :as syncopate]
            [syncopate.gen :as sgen]
            [syncopate.test-db :as test-db]))

(defn- with-temp-db
  "Open a fresh Datalevin conn + store (embedded or remote per test mode), run
  (f conn store), and always release both — close! frees the remote store's KV
  client (a no-op embedded) so many iterations don't leak server sessions."
  [f]
  (let [conn  (test-db/fresh-conn)
        store (syncopate/store conn)]
    (try (f conn store)
         (finally (syncopate/close! store) (d/close conn)))))

(defn- schema-attrs [conn] (set (keys (d/schema conn))))

(defn- up-attrs [spec]
  (set (mapcat #(keys (:schema %)) (:up spec))))

(defn- datom-count
  "Number of entities carrying attr `a` (0 if `a` isn't in the schema)."
  [conn a]
  (if (contains? (schema-attrs conn) a)
    (or (d/q '[:find (count ?e) . :in $ ?a :where [?e ?a]] (d/db conn) a) 0)
    0))

;; ---------------------------------------------------------------------------
;; 1. Pure: an auto-derived :down removes exactly what :up added.
;;    (conn-free — runs identically in both modes; not reduced for remote.)
;; ---------------------------------------------------------------------------

(defspec derived-down-covers-additions 300
  (prop/for-all [{:keys [migrations]} sgen/plan]
    (every?
     (fn [spec]
       (let [m (syncopate/->migration spec)]
         (= (set (mapcat :schema/remove (:down m)))
            (up-attrs spec))))
     migrations)))

;; ---------------------------------------------------------------------------
;; The remaining specs hit a conn — reduced iteration counts in remote mode
;; (every iteration is a round-trip to the server) via test-db/gen-count.
;; ---------------------------------------------------------------------------

;; 2. Round-trip: migrate-all! then full rollback restores the schema (the
;;    money-test analog).
(defspec migrate-rollback-restores-schema (test-db/gen-count 60)
  (prop/for-all [{:keys [migrations attr->def]} sgen/plan]
    (with-temp-db
      (fn [conn store]
        (let [ms    (mapv syncopate/->migration migrations)
              base  (schema-attrs conn)]
          (syncopate/migrate-all! store ms)
          (and (is (every? (schema-attrs conn) (keys attr->def))
                   "all generated attrs present after migrate-all!")
               (is (= (mapv :id migrations) (rp/applied-migration-ids store))
                   "applied ids match creation order")
               (do (dotimes [_ (count ms)] (syncopate/rollback-last! store ms))
                   true)
               (is (empty? (rp/applied-migration-ids store))
                   "everything rolled back")
               (is (= base (schema-attrs conn))
                   "schema restored to baseline after full rollback")))))))

;; 3. "Go ham": arbitrary interleaving of migrate!/rollback-last! tracks a model.
;;    (migrate! always applies the next pending, rollback-last! pops the last, so
;;    the applied set is always a prefix — the model is just a count.)
(defspec interleaved-migrate-rollback-matches-model (test-db/gen-count 40)
  (prop/for-all [{:keys [migrations]} sgen/plan
                 ops (gen/vector (gen/elements [:up :down]) 0 30)]
    (with-temp-db
      (fn [conn store]
        (let [ms    (mapv syncopate/->migration migrations)
              base  (schema-attrs conn)
              total (count ms)
              n     (reduce
                     (fn [n op]
                       (cond
                         (and (= op :up) (< n total))
                         (do (syncopate/migrate! store (nth ms n)) (inc n))
                         (and (= op :down) (pos? n))
                         (do (syncopate/rollback-last! store ms) (dec n))
                         :else n))
                     0 ops)
              applied  (take n migrations)
              live     (set (mapcat up-attrs applied))
              dormant  (set (mapcat up-attrs (drop n migrations)))]
          (and (is (= (mapv :id applied) (rp/applied-migration-ids store))
                   "applied ids track the model")
               (is (every? (schema-attrs conn) live)
                   "applied migrations' attrs are present")
               (is (empty? (set/intersection dormant (schema-attrs conn)))
                   "unapplied migrations' attrs are absent")
               (is (= base (set/difference (schema-attrs conn) live))
                   "schema is exactly baseline plus the applied attrs")))))))

;; 4. Rich migrations: :tx data + removal :down round-trips schema AND data
;;    (exercises apply-delta!'s retract-then-drop with data actually present).
(defspec rich-migrate-rollback-restores-schema-and-data (test-db/gen-count 40)
  (prop/for-all [{:keys [migrations model]} sgen/rich-plan]
    (with-temp-db
      (fn [conn store]
        (let [ms    (mapv syncopate/->migration migrations)
              base  (schema-attrs conn)]
          (syncopate/migrate-all! store ms)
          (and
           (is (every? (fn [{:keys [attr count]}]
                         (and (contains? (schema-attrs conn) attr)
                              (= count (datom-count conn attr))))
                       model)
               "each attr present with expected datom count after migrate-all!")
           (do (dotimes [_ (count ms)] (syncopate/rollback-last! store ms)) true)
           (is (empty? (rp/applied-migration-ids store)) "all rolled back")
           (is (= base (schema-attrs conn)) "schema restored to baseline")
           (is (every? (fn [{:keys [attr]}] (zero? (datom-count conn attr))) model)
               "all data retracted after full rollback")))))))

;; 5. Irreversible migrations apply, but rollback! throws and leaves them applied.
(defspec irreversible-migrate-then-rollback-throws (test-db/gen-count 100)
  (prop/for-all [n  gen/nat
                 a  sgen/attr
                 vt sgen/value-type]
    (with-temp-db
      (fn [conn store]
        (let [id    (str "irr" n)
              m     (syncopate/->migration
                     {:id id :irreversible? true :up [{:schema {a {:db/valueType vt}}}]})]
          (syncopate/migrate! store m)
          (and (is (contains? (schema-attrs conn) a) "attr added")
               (is (= [id] (rp/applied-migration-ids store)) "recorded")
               (is (thrown? clojure.lang.ExceptionInfo (syncopate/rollback! store m))
                   "rollback! of an irreversible migration throws")
               (is (= [id] (rp/applied-migration-ids store))
                   "still applied after the failed rollback")))))))

;; 6. Cross-migration churn: add/remove/re-add over a pool tracks the model.
(defspec churn-add-remove-readd-tracks-model (test-db/gen-count 40)
  (prop/for-all [{:keys [migrations final-live attrs]} sgen/churn-plan]
    (with-temp-db
      (fn [conn store]
        (let [ms    (mapv syncopate/->migration migrations)
              base  (schema-attrs conn)]
          (syncopate/migrate-all! store ms)
          (and
           (is (= final-live (set/intersection attrs (schema-attrs conn)))
               "live churn attrs match the model after migrate-all!")
           (do (dotimes [_ (count ms)] (syncopate/rollback-last! store ms)) true)
           (is (= base (schema-attrs conn))
               "baseline restored after full rollback")))))))

;; 7. Applied-id ordering with heterogeneous ids (applied in id order): the
;;    store returns them id-sorted, and rollback-last! pops the highest id.
(defspec applied-ids-ordered-with-heterogeneous-ids (test-db/gen-count 40)
  (prop/for-all [{:keys [migrations sorted-ids]} sgen/weird-id-plan]
    (with-temp-db
      (fn [conn store]
        (let [ms    (mapv syncopate/->migration migrations)]
          (syncopate/migrate-all! store ms)
          (and
           (is (= sorted-ids (rp/applied-migration-ids store))
               "applied ids returned in id order")
           (do (syncopate/rollback-last! store ms) true)
           (is (= (vec (butlast sorted-ids)) (rp/applied-migration-ids store))
               "rollback-last! removes the highest id")))))))

;; 8. (A) fn/symbol data-transform steps round-trip end-to-end: split a
;;    :user/name into given/family (a resolved symbol fn), rollback rejoins it.
(defspec fn-step-transform-round-trips (test-db/gen-count 40)
  (prop/for-all [names (gen/vector sgen/clean-name 1 8)]
    (with-temp-db
      (fn [conn store]
        (let [setup (syncopate/->migration
                     {:id "0001" :up {:schema {:user/name {:db/valueType :db.type/string}}}})
              split (syncopate/->migration
                     {:id   "0002"
                      :up   [{:schema {:user/given-name  {:db/valueType :db.type/string}
                                       :user/family-name {:db/valueType :db.type/string}}}
                             'syncopate.example/split-user-names
                             {:schema/remove [:user/name]}]
                      :down [{:schema {:user/name {:db/valueType :db.type/string}}}
                             'syncopate.example/join-user-names
                             {:schema/remove [:user/given-name :user/family-name]}]})]
          (syncopate/migrate! store setup)
          (d/transact! conn (mapv (fn [n] {:user/name n}) names))
          (syncopate/migrate! store split)
          (let [recombined (->> (d/q '[:find ?g ?f
                                       :where [?e :user/given-name ?g] [?e :user/family-name ?f]]
                                     (d/db conn))
                                (map (fn [[g f]] (.trim (str g " " f))))
                                set)]
            (and
             (is (= (set names) recombined)
                 "split produced given/family that recombine to the original names")
             (do (syncopate/rollback! store split) true)
             (is (= (set names)
                    (set (d/q '[:find [?n ...] :where [?e :user/name ?n]] (d/db conn))))
                 "rollback (join) restored :user/name"))))))))

;; 9. (B) applied-migration-ids tracks APPLICATION order even for out-of-order ids.
(defspec applied-ids-track-application-order (test-db/gen-count 40)
  (prop/for-all [ids sgen/distinct-ids]
    (with-temp-db
      (fn [conn store]
        (doseq [[i id] (map-indexed vector ids)]
          (syncopate/migrate! store
            (syncopate/->migration
             {:id id :up [{:schema {(keyword "gen" (str "o" i)) {:db/valueType :db.type/long}}}]})))
        (is (= ids (rp/applied-migration-ids store))
            "applied ids reflect application order, not id-sorted order")))))

;; 10. (D) exact data values round-trip; explicit :down on an additive migration.
(defspec data-values-exactly-round-trip (test-db/gen-count 30)
  (prop/for-all [vals (gen/vector gen/string-alphanumeric 0 6)]
    (with-temp-db
      (fn [conn store]
        (let [a     :gen/sval
              m     (syncopate/->migration
                     {:id   "0001"
                      :up   [{:schema {a {:db/valueType :db.type/string}}}
                             {:tx (mapv (fn [v] {a v}) vals)}]
                      :down [{:schema/remove [a]}]})]
          (syncopate/migrate! store m)
          (and
           (is (= (set vals)
                  (set (d/q '[:find [?v ...] :in $ ?a :where [?e ?a ?v]] (d/db conn) a)))
               "inserted string values round-trip exactly")
           (do (syncopate/rollback! store m) true)
           (is (zero? (datom-count conn a)) "data + attr gone after rollback")))))))

(defspec explicit-down-on-additive-round-trips (test-db/gen-count 40)
  (prop/for-all [attrs (gen/not-empty (gen/set sgen/attr {:max-elements 5}))]
    (with-temp-db
      (fn [conn store]
        (let [base   (schema-attrs conn)
              schema (into {} (map (fn [a] [a {:db/valueType :db.type/long}]) attrs))
              m      (syncopate/->migration
                      {:id "0001" :up [{:schema schema}] :down [{:schema/remove (vec attrs)}]})]
          (syncopate/migrate! store m)
          (and
           (is (every? (schema-attrs conn) attrs) "attrs added")
           (do (syncopate/rollback! store m) true)
           (is (= base (schema-attrs conn)) "hand-written :down removed them")))))))
