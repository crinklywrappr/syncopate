(ns syncopate.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [datalevin.core :as d]
            [ragtime.core :as ragtime]
            [ragtime.protocols :as rp]
            [taoensso.trove :as trove]
            [syncopate.core :as syncopate]
            [syncopate.example :as example])
  (:import [java.net URL]
           [java.util UUID]
           [java.util.jar JarEntry JarOutputStream]))

(def ^:dynamic *conn* nil)
(def ^:dynamic *store* nil)

(defn with-temp-db [f]
  (let [dir  (str "/tmp/syncopate-test-" (UUID/randomUUID))
        conn (d/get-conn dir {})]
    (binding [*conn*  conn
              *store* (syncopate/store conn)]
      (try (f)
           (finally (d/close conn))))))

(use-fixtures :each with-temp-db)
;; Keep normal test runs quiet; logging tests install a capturing fn via binding.
(use-fixtures :once (fn [f] (trove/set-log-fn! nil) (f)))

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

(deftest atomic-rollback-on-failure
  ;; The key guarantee: a failing transactional :up leaves NEITHER the schema
  ;; change NOR the applied-id behind — both roll back together.
  (let [boom (syncopate/->migration
              {:id "boomer"
               :up [{:schema {:widget/name {:db/valueType :db.type/string}}}
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
               :up [{:schema {:widget/name {:db/valueType :db.type/string}}}
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
      (is (= {:phase :schema :add [:a/b] :remove [:c/d]}
             (summary {:schema {:a/b {}} :schema/remove [:c/d]})))
      (is (= {:phase :unknown}                          (summary 42))))
    (testing "step-phase"
      (is (= :fn      (phase 'my/fn)))
      (is (= :fn      (phase #'clojure.core/identity)))
      (is (= :fn      (phase (fn [_]))))
      (is (= :tx      (phase {:tx []})))
      (is (= :schema  (phase {:schema {}})))
      (is (= :unknown (phase 42))))))

(deftest run-step-tx-and-unrecognised
  (let [run-step! #'syncopate/run-step!]
    (testing ":tx step transacts"
      (run-step! *conn* {:schema {:thing/name {:db/valueType :db.type/string}}})
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
                        "{:up {:schema {:x/y {:db/valueType :db.type/string}}}}"
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
            :up {:schema {:gadget/name {:db/valueType :db.type/string}}}})]
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
                        (syncopate/->migration {:up {:schema {:a/b {}}}}))))
