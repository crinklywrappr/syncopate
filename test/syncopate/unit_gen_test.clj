(ns syncopate.unit-gen-test
  "Generative *unit* tests over Syncopate's pure functions (no database)."
  (:require [clojure.test :refer [is deftest]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [syncopate.core :as core]
            [syncopate.gen :as sgen]
            [syncopate.schema :as schema]))

;; private fns under test
(def ^:private as-steps     #'core/as-steps)
(def ^:private step-phase   #'core/step-phase)
(def ^:private step-summary #'core/step-summary)

;; ---------------------------------------------------------------------------
;; syncopate.schema/invert
;; ---------------------------------------------------------------------------

(defspec invert-removes-exactly-the-added-attrs 500
  (prop/for-all [d sgen/additive-delta]
    (= (set (:schema/remove (schema/invert d)))
       (set (keys (:schema d))))))

(defspec invert-yields-a-non-additive-delta 500
  (prop/for-all [d sgen/additive-delta]
    (let [inv (schema/invert d)]
      (and (schema/schema-delta? inv)
           (not (schema/additive? inv))))))

;; ---------------------------------------------------------------------------
;; predicates: additive? / schema-delta?
;; ---------------------------------------------------------------------------

(defspec additive-implies-schema-delta 500
  (prop/for-all [d sgen/additive-delta]
    (and (schema/additive? d) (schema/schema-delta? d))))

(defspec removal-and-mixed-are-schema-deltas-not-additive 500
  (prop/for-all [d (gen/one-of [sgen/removal-delta sgen/mixed-delta])]
    (and (schema/schema-delta? d) (not (schema/additive? d)))))

(defspec non-delta-is-not-a-schema-delta 500
  (prop/for-all [x sgen/non-delta]
    (and (not (schema/schema-delta? x)) (not (schema/additive? x)))))

;; ---------------------------------------------------------------------------
;; core/as-steps
;; ---------------------------------------------------------------------------

(deftest as-steps-nil-is-empty
  (is (= [] (as-steps nil))))

(defspec as-steps-passes-vectors-through 500
  (prop/for-all [v (gen/vector sgen/step)]
    (= v (as-steps v))))

(defspec as-steps-wraps-non-vectors 500
  (prop/for-all [x sgen/step]                       ; `step` never yields a vector/nil
    (= [x] (as-steps x))))

(defspec as-steps-always-returns-a-vector 500
  (prop/for-all [x (gen/one-of [(gen/return nil) sgen/step (gen/vector sgen/step)])]
    (vector? (as-steps x))))

;; ---------------------------------------------------------------------------
;; core/step-phase classification
;; ---------------------------------------------------------------------------

(defspec step-phase-classifies-fns 500
  (prop/for-all [s (gen/one-of [sgen/fn-symbol sgen/core-var sgen/anon-fn])]
    (= :fn (step-phase s))))

(defspec step-phase-classifies-tx 500
  (prop/for-all [s sgen/tx-step]
    (= :tx (step-phase s))))

(defspec step-phase-classifies-schema 500
  (prop/for-all [s (gen/one-of [sgen/additive-delta sgen/removal-delta sgen/mixed-delta])]
    (= :schema (step-phase s))))

(defspec step-phase-classifies-unknown 500
  (prop/for-all [s sgen/non-delta]
    (= :unknown (step-phase s))))

;; ---------------------------------------------------------------------------
;; core/step-summary — consistency with step-phase + per-kind detail
;; ---------------------------------------------------------------------------

(defspec step-summary-phase-matches-step-phase 1000
  (prop/for-all [s sgen/step]
    (= (:phase (step-summary s)) (step-phase s))))

(defspec step-summary-symbol 500
  (prop/for-all [s sgen/fn-symbol]
    (= {:phase :fn :fn s} (step-summary s))))

(defspec step-summary-var 500
  (prop/for-all [v sgen/core-var]
    (= {:phase :fn :fn (symbol (str (:ns (meta v))) (str (:name (meta v))))}
       (step-summary v))))

(defspec step-summary-tx 500
  (prop/for-all [s sgen/tx-step]
    (= {:phase :tx :tx-count (count (:tx s))} (step-summary s))))

(defspec step-summary-additive 500
  (prop/for-all [d sgen/additive-delta]
    (= {:phase :schema :add (vec (keys (:schema d))) :remove []}
       (step-summary d))))

;; ---------------------------------------------------------------------------
;; core/->migration invariants
;; ---------------------------------------------------------------------------

(defspec migration-id-is-a-string 500
  (prop/for-all [spec sgen/migration-spec]
    (string? (:id (core/->migration spec)))))

(defspec migration-transaction-default 500
  (prop/for-all [spec sgen/migration-spec]
    (= (get spec :transaction? true) (:transaction? (core/->migration spec)))))

(defspec migration-irreversible-is-boolean 500
  (prop/for-all [spec sgen/migration-spec]
    (boolean? (:irreversible? (core/->migration spec)))))

(defspec migration-preserves-explicit-down 500
  (prop/for-all [id   (gen/fmap str gen/nat)
                 up   (gen/vector sgen/step 1 3)
                 down (gen/vector sgen/step 0 3)]
    (= down (:down (core/->migration {:id id :up up :down down})))))

(defspec migration-irreversible-has-nil-down 500
  (prop/for-all [id (gen/fmap str gen/nat)
                 up (gen/vector sgen/step 1 3)]
    (nil? (:down (core/->migration {:id id :up up :irreversible? true})))))

(defspec migration-missing-id-throws 200
  (prop/for-all [up (gen/vector sgen/additive-delta 1 3)]
    (try (core/->migration {:up up}) false
         (catch clojure.lang.ExceptionInfo _ true))))
