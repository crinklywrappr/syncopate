(ns syncopate.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [datalevin.core :as d]
            [ragtime.core :as ragtime]
            [ragtime.protocols :as rp]
            [taoensso.trove :as trove]
            [syncopate.core :as syncopate]
            [syncopate.example :as example]
            [syncopate.test-db :as test-db])
  (:import [java.net URL]
           [java.util UUID]
           [java.util.jar JarEntry JarOutputStream]))

(def ^:dynamic *conn* nil)
(def ^:dynamic *store* nil)

(defn with-temp-db [f]
  ;; A fresh, isolated db per test — embedded temp dir or a fresh remote db,
  ;; per syncopate.test.mode (see syncopate.test-db). close! releases the
  ;; remote store's KV client (a no-op embedded).
  (let [conn  (test-db/fresh-conn)
        store (syncopate/store conn)]
    (binding [*conn*  conn
              *store* store]
      (try (f)
           (finally (syncopate/close! store)
                    (d/close conn))))))

(use-fixtures :each with-temp-db)
;; Keep normal test runs quiet; logging tests install a capturing fn via binding.
;; In remote mode, fail fast if no server is reachable.
(use-fixtures :once (fn [f] (trove/set-log-fn! nil) (test-db/assert-server!) (f)))

(defn- migrations [] (syncopate/load-resources "migrations"))

(defn- capture-logs
  "Run `thunk` with a capturing trove backend; return the collected events."
  [thunk]
  (let [logs (atom [])]
    (binding [trove/*log-fn*
              (fn [_ns _coords level id lazy_]
                (swap! logs conj {:level level :id id :log (force lazy_)}))]
      (thunk))
    @logs))

(defn- app-schema-attrs []
  ;; Attribute keys the application can see — must never include our bookkeeping.
  (set (keys (d/schema *conn*))))

(deftest loads-and-orders
  (let [ms (migrations)]
    (is (= ["0001-add-users" "0002-split-names" "0003-seed-departments"]
           (mapv rp/id ms)))))

(deftest auto-down-for-additive
  (let [m (->> (migrations) (filter #(= "0001-add-users" (rp/id %))) first)]
    (is (= [{:schema/remove [:user/id :user/name]}] (:down m))
        "additive :up should yield an inverted :down")))

(deftest reversibility-guard
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"no :down"
       (syncopate/->migration {:id "x" :up example/split-user-names}))
      "non-additive :up without :down must error at build time"))

(deftest full-migrate-and-rollback
  (let [ms (migrations)]
    (testing "before migrating, everything is pending"
      (is (= {:applied [] :pending ["0001-add-users" "0002-split-names" "0003-seed-departments"]}
             (syncopate/status *store* ms))))

    ;; seed a user under the 0001 schema, but migrations run first so schema
    ;; exists; we insert between 0001 and 0002 to exercise the data transform.
    (ragtime/migrate *store* (nth ms 0))
    (d/transact! *conn* [{:user/id 1 :user/name "Ada Lovelace"}
                         {:user/id 2 :user/name "Alan Turing"}])
    (ragtime/migrate *store* (nth ms 1))
    (ragtime/migrate *store* (nth ms 2))

    (testing "schema + data transformed by 0002 (symbol-referenced fn)"
      (is (contains? (app-schema-attrs) :user/given-name))
      (is (not (contains? (app-schema-attrs) :user/name)))
      (is (= #{"Ada" "Alan"}
             (set (d/q '[:find [?g ...] :where [?e :user/given-name ?g]] (d/db *conn*))))))

    (testing "0003 .clj migration ran real fns"
      (is (= #{"Engineering" "Sales"}
             (set (d/q '[:find [?n ...] :where [?e :dept/name ?n]] (d/db *conn*))))))

    (testing "applied ids recorded in KV, ordered by application time"
      (is (= ["0001-add-users" "0002-split-names" "0003-seed-departments"]
             (rp/applied-migration-ids *store*))))

    (testing "no bookkeeping leaks into the app's datalog schema"
      (is (empty? (filter #(or (= "syncopate" (namespace %))
                               (= "ragtime" (namespace %)))
                          (app-schema-attrs)))))

    (testing "rollback reverses in order"
      (ragtime/rollback *store* (nth ms 2))
      (ragtime/rollback *store* (nth ms 1))
      (is (empty? (d/q '[:find [?n ...] :where [?e :dept/name ?n]] (d/db *conn*))))
      (is (contains? (app-schema-attrs) :user/name))
      (is (not (contains? (app-schema-attrs) :user/given-name)))
      (is (= #{"Ada Lovelace" "Alan Turing"}
             (set (d/q '[:find [?n ...] :where [?e :user/name ?n]] (d/db *conn*)))))
      (is (= ["0001-add-users"] (rp/applied-migration-ids *store*))))))

(deftest status-mid-migration
  ;; status's whole point is the partial view; assert it mid-way, not just at the
  ;; all-pending / none-pending endpoints.
  (let [ms (migrations)]
    (syncopate/migrate! *store* (first ms))            ;apply only 0001
    (is (= {:applied ["0001-add-users"]
            :pending ["0002-split-names" "0003-seed-departments"]}
           (syncopate/status *store* ms))
        "status shows the partial split: first applied, the rest pending")))

(deftest atomic-migrate-and-rollback
  (let [ms (migrations)]
    (syncopate/migrate-all! *store* ms)
    (testing "migrate-all! applied everything"
      (is (= ["0001-add-users" "0002-split-names" "0003-seed-departments"]
             (rp/applied-migration-ids *store*)))
      (is (contains? (app-schema-attrs) :dept/name)))
    (testing "migrate-all! is idempotent — second call a no-op"
      (is (= [] (:pending (syncopate/status *store* ms))))
      (syncopate/migrate-all! *store* ms)
      (is (= 3 (count (rp/applied-migration-ids *store*)))))
    (testing "rollback-last! removes only the most recent"
      (syncopate/rollback-last! *store* ms)
      (is (= ["0001-add-users" "0002-split-names"] (rp/applied-migration-ids *store*)))
      (is (not (contains? (app-schema-attrs) :dept/name))))))

(deftest ^:embedded atomic-rollback-on-failure
  ;; Embedded-only: this is the atomic-fold guarantee that client/server
  ;; deliberately trades for the documented two-transaction seam (the schema
  ;; step commits before the failing fn, so it can't roll back remotely).
  ;; The key guarantee: a failing transactional :up leaves NEITHER the schema
  ;; change NOR the applied-id behind — both roll back together.
  (let [boom (syncopate/->migration
              {:id "boomer"
               :up [{:schema/create {:widget/name {:db/valueType :db.type/string}}}
                    (fn [_] (throw (ex-info "boom" {})))]
               :irreversible? true})]
    (is (thrown? clojure.lang.ExceptionInfo (syncopate/migrate! *store* boom)))
    (is (not (contains? (app-schema-attrs) :widget/name))
        "schema change must have rolled back")
    (is (empty? (rp/applied-migration-ids *store*))
        "applied-id must not have been recorded")))

(deftest error-context
  (let [bad (syncopate/->migration
             {:id "bad" :up 'no.such.ns/missing :irreversible? true})]
    (try
      (rp/run-up! bad *store*)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= "bad" (:syncopate/id (ex-data e))))
        (is (= :up (:direction (ex-data e))))
        (is (= :fn (:phase (ex-data e))))))))

(deftest logging-migrate-all
  (let [ms   (migrations)
        logs (capture-logs #(syncopate/migrate-all! *store* ms))
        ids  (set (map :id logs))]
    (is (contains? ids :syncopate/migrate-all))
    (is (contains? ids :syncopate/migrate-all-done))
    (is (contains? ids :syncopate/migrated))
    (testing "a migrated event carries a human msg + structured timing data"
      (let [e (first (filter #(= :syncopate/migrated (:id %)) logs))]
        (is (= :info (:level e)))
        (is (string? (:msg (:log e))))
        (is (number? (:ms (:data (:log e)))))
        (is (number? (:steps (:data (:log e)))))))))

(deftest logging-failure-at-error
  (let [boom (syncopate/->migration
              {:id "boomer"
               :up [{:schema/create {:widget/name {:db/valueType :db.type/string}}}
                    (fn [_] (throw (ex-info "boom" {})))]
               :irreversible? true})
        logs (capture-logs #(try (syncopate/migrate! *store* boom)
                                 (catch Exception _ nil)))
        e    (first (filter #(= :syncopate/migration-failed (:id %)) logs))]
    (is (some? e) "a migration-failed event must be emitted")
    (is (= :error (:level e)))
    (is (some? (:error (:log e))) "the event must carry the causing throwable")))

(deftest logging-no-migrations-warns
  (let [logs (capture-logs #(syncopate/load-resources "no-such-migrations-prefix"))
        e    (first (filter #(= :syncopate/no-migrations (:id %)) logs))]
    (is (some? e) "an empty load must warn")
    (is (= :warn (:level e)))))

;; ---------------------------------------------------------------------------
;; Coverage of edge/error branches (many via #' on private fns)
;; ---------------------------------------------------------------------------

(deftest step-summary-and-phase-arms
  (let [summary #'syncopate/step-summary
        phase   #'syncopate/step-phase]
    (testing "step-summary"
      (is (= {:phase :fn :fn 'my/fn}                    (summary 'my/fn)))
      (is (= {:phase :fn :fn 'clojure.core/identity}    (summary #'clojure.core/identity)))
      (is (= {:phase :fn :fn :anonymous}                (summary (fn [_]))))
      (is (= {:phase :tx :tx-count 2}                   (summary {:tx [{} {}]})))
      (is (= {:phase :schema :op :create :attrs [:a/b]}
             (summary {:schema/create {:a/b {}}})))
      (is (= {:phase :schema :op :alter :attrs [:a/b]}
             (summary {:schema/alter {:a/b {}}})))
      (is (= {:phase :schema :op :remove :attrs [:c/d]}
             (summary {:schema/remove [:c/d]})))
      (is (= {:phase :unknown}                          (summary 42))))
    (testing "step-phase"
      (is (= :fn      (phase 'my/fn)))
      (is (= :fn      (phase #'clojure.core/identity)))
      (is (= :fn      (phase (fn [_]))))
      (is (= :tx      (phase {:tx []})))
      (is (= :schema  (phase {:schema/create {}})))
      (is (= :schema  (phase {:schema/alter {:a/b {}}})))
      (is (= :schema  (phase {:schema/remove [:c/d]})))
      (is (= :unknown (phase 42))))))

(deftest run-step-tx-and-unrecognised
  (let [run-step! #'syncopate/run-step!]
    (testing ":tx step transacts"
      (run-step! *conn* {:schema/create {:thing/name {:db/valueType :db.type/string}}})
      (run-step! *conn* {:tx [{:thing/name "hi"}]})
      (is (= ["hi"] (d/q '[:find [?n ...] :where [?e :thing/name ?n]] (d/db *conn*)))))
    (testing "unrecognised step throws"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unrecognised"
                            (run-step! *conn* 42))))))

(deftest resource-migrations-unsupported-protocol
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Unsupported resource protocol"
       (#'syncopate/resource-migrations (URL. "http://example.com/x") "x"))))

(defn- make-jar [entries]
  (let [f (java.io.File/createTempFile "syncopate" ".jar")]
    (with-open [out (JarOutputStream. (io/output-stream f))]
      (doseq [[nm content] entries]
        (.putNextEntry out (JarEntry. ^String nm))
        (.write out (.getBytes ^String content))
        (.closeEntry out)))
    f))

(deftest jar-loading
  (let [jar  (make-jar {"migrations/0001-a.edn"
                        "{:up {:schema/create {:x/y {:db/valueType :db.type/string}}}}"
                        "migrations/README.txt" "not a migration"})
        url  (URL. (str "jar:file:" (.getAbsolutePath jar) "!/migrations"))
        migs (#'syncopate/resource-migrations url "migrations")]
    (try
      (is (= ["0001-a"] (mapv rp/id migs))
          "jar branch loads .edn entries and ignores non-migration files")
      (finally (.delete jar)))))

(deftest non-transactional-migration
  (let [m (syncopate/->migration
           {:id "notx" :transaction? false
            :up {:schema/create {:gadget/name {:db/valueType :db.type/string}}}})]
    (syncopate/migrate! *store* m)
    (is (contains? (app-schema-attrs) :gadget/name))
    (is (= ["notx"] (rp/applied-migration-ids *store*)))
    (syncopate/rollback! *store* m)
    (is (not (contains? (app-schema-attrs) :gadget/name)))
    (is (empty? (rp/applied-migration-ids *store*)))))

(deftest rollback-last-edge-cases
  (testing "nothing applied — no-op"
    (is (= *store* (syncopate/rollback-last! *store* [])))
    (is (empty? (rp/applied-migration-ids *store*))))
  (testing "applied id absent from provided migrations — throws"
    (syncopate/migrate! *store* (nth (migrations) 0))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found among"
                          (syncopate/rollback-last! *store* [])))))

(deftest migration-missing-id
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :id"
                        (syncopate/->migration {:up {:schema/create {:a/b {}}}}))))

(deftest applied-ids-follow-application-order
  ;; Apply out of id order ("b" then "a"); applied-migration-ids must reflect
  ;; APPLICATION order via the monotonic :seq, not the wall-clock/id tie-break.
  (syncopate/migrate! *store*
    (syncopate/->migration {:id "b" :up [{:schema/create {:z/b {:db/valueType :db.type/long}}}]}))
  (syncopate/migrate! *store*
    (syncopate/->migration {:id "a" :up [{:schema/create {:z/a {:db/valueType :db.type/long}}}]}))
  (is (= ["b" "a"] (rp/applied-migration-ids *store*))))

;; ---------------------------------------------------------------------------
;; Schema-op taxonomy: :schema/create / :schema/alter / :schema/remove
;; ---------------------------------------------------------------------------

(deftest legacy-schema-rejected
  (testing "bare :schema is obsolete — ->migration errors, forking create vs alter"
    (let [spec {:id "old" :up {:schema {:user/id {:db/valueType :db.type/long}}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":schema/create"
                            (syncopate/->migration spec)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":schema/alter"
                            (syncopate/->migration spec)))))
  (testing "a purely-additive legacy :up gets the naive :down to copy-paste"
    (try (syncopate/->migration {:id "old" :up [{:schema {:user/id {:db/valueType :db.type/long}}}]})
         (is false "should have thrown")
         (catch clojure.lang.ExceptionInfo e
           (is (:syncopate/legacy-schema (ex-data e)))
           (is (re-find #":schema/remove \[:user/id\]" (.getMessage e))))))
  (testing "run-step! backstops a raw :schema step too"
    (let [run-step! #'syncopate/run-step!]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"obsolete"
                            (run-step! *conn* {:schema {:x/y {}}}))))))

(deftest alter-requires-explicit-down
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not purely :schema/create"
                        (syncopate/->migration
                         {:id "alt" :up {:schema/alter {:person/email {:db/index true}}}}))
      ":schema/alter is not auto-invertible — a missing :down must error at build"))

(deftest alter-patches-existing-definition
  ;; datalevin 1.1.0 patches (merges) an existing attr's definition rather than
  ;; replacing it — the property that makes :schema/alter first-class (and made
  ;; the old ambiguous :schema step's auto-drop rollback a data-loss footgun).
  (let [run-step! #'syncopate/run-step!]
    (run-step! *conn* {:schema/create {:person/email {:db/valueType   :db.type/string
                                                      :db/cardinality :db.cardinality/many}}})
    (run-step! *conn* {:schema/alter {:person/email {:db/index true}}})
    (let [d (get (d/schema *conn*) :person/email)]
      (is (= :db.cardinality/many (:db/cardinality d)) "existing property preserved (patch, not replace)")
      (is (true? (:db/index d)) "new property applied by alter"))))

(deftest alter-round-trip-preserves-data
  ;; The fix: a :schema/alter migration with a correct explicit :down (the inverse
  ;; change) round-trips without dropping the attribute or its data — unlike the
  ;; old auto-derived drop-based rollback.
  (let [setup (syncopate/->migration
               {:id "0001" :up {:schema/create {:person/email {:db/valueType :db.type/string}}}})
        index (syncopate/->migration
               {:id   "0002"
                :up   {:schema/alter {:person/email {:db/index true}}}
                :down {:schema/alter {:person/email {:db/index false}}}})]
    (syncopate/migrate! *store* setup)
    (d/transact! *conn* [{:person/email "a@x.com"} {:person/email "b@x.com"}])
    (syncopate/migrate! *store* index)
    (is (true? (:db/index (get (d/schema *conn*) :person/email))) "index added by alter")
    (syncopate/rollback! *store* index)
    (is (contains? (app-schema-attrs) :person/email)
        "attribute still exists after alter rollback (not dropped)")
    (is (not (:db/index (get (d/schema *conn*) :person/email))) "index turned back off")
    (is (= #{"a@x.com" "b@x.com"}
           (set (d/q '[:find [?v ...] :where [?e :person/email ?v]] (d/db *conn*))))
        "data preserved through the alter round-trip")))
