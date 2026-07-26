(ns syncopate.generative-test
  (:require [clojure.set :as set]
            [clojure.test :refer [is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datalevin.core :as d]
            [ragtime.protocols :as rp]
            [syncopate.core :as syncopate]
            [syncopate.gen :as sgen])
  (:import [java.util UUID]))

(defn- with-temp-db
  "Open a fresh Datalevin conn, run (f conn), always close it."
  [f]
  (let [dir  (str "/tmp/syncopate-gen-" (UUID/randomUUID))
        conn (d/get-conn dir {})]
    (try (f conn)
         (finally (d/close conn)))))

(defn- schema-attrs [conn] (set (keys (d/schema conn))))

(defn- up-attrs [spec]
  (set (mapcat #(keys (:schema %)) (:up spec))))

;; ---------------------------------------------------------------------------
;; 1. Pure: an auto-derived :down removes exactly what :up added.
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
;; 2. Round-trip: migrate-all! then full rollback restores the schema (the
;;    money-test analog).
;; ---------------------------------------------------------------------------

(defspec migrate-rollback-restores-schema 60
  (prop/for-all [{:keys [migrations attr->def]} sgen/plan]
    (with-temp-db
      (fn [conn]
        (let [store (syncopate/store conn)
              ms    (mapv syncopate/->migration migrations)
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

;; ---------------------------------------------------------------------------
;; 3. "Go ham": arbitrary interleaving of migrate!/rollback-last! tracks a model.
;;    (migrate! always applies the next pending, rollback-last! pops the last, so
;;    the applied set is always a prefix — the model is just a count.)
;; ---------------------------------------------------------------------------

(defspec interleaved-migrate-rollback-matches-model 40
  (prop/for-all [{:keys [migrations]} sgen/plan
                 ops (gen/vector (gen/elements [:up :down]) 0 30)]
    (with-temp-db
      (fn [conn]
        (let [store (syncopate/store conn)
              ms    (mapv syncopate/->migration migrations)
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
