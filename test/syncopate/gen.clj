(ns syncopate.gen
  "test.check generators for Syncopate migration plans. Support namespace — no
  tests live here."
  (:require [clojure.test.check.generators :as gen]))

(def attr
  "A distinct-friendly qualified attribute keyword, e.g. :gen/a42."
  (gen/fmap #(keyword "gen" (str "a" %)) gen/nat))

(def value-type
  (gen/elements [:db.type/string :db.type/long :db.type/boolean
                 :db.type/keyword :db.type/double]))

(def attr->def
  "A non-empty map of distinct attrs → attribute definitions."
  (gen/fmap (partial into {})
            (gen/not-empty
             (gen/vector-distinct-by
              first
              (gen/tuple attr (gen/fmap (fn [vt] {:db/valueType vt}) value-type))
              {:max-elements 12}))))

;; ---------------------------------------------------------------------------
;; Schema deltas and migration steps (for the pure/unit generative tests)
;; ---------------------------------------------------------------------------

(def additive-delta
  "A purely-additive schema delta: {:schema {attr def ...}}."
  (gen/fmap (fn [m] {:schema m}) attr->def))

(def removal-delta
  "A removal-only delta: {:schema/remove [attr ...]}."
  (gen/fmap (fn [as] {:schema/remove (vec as)})
            (gen/not-empty (gen/set attr {:max-elements 12}))))

(def mixed-delta
  "A delta with both :schema and :schema/remove."
  (gen/fmap (fn [[a r]] (merge a r)) (gen/tuple additive-delta removal-delta)))

(def tx-step
  "A raw-transaction step: {:tx [...]}."
  (gen/fmap (fn [n] {:tx (vec (repeat n {}))}) (gen/choose 0 6)))

(def fn-symbol
  "A fully-qualified symbol naming a migration fn."
  (gen/fmap #(symbol "my.app" (str "f" %)) gen/nat))

(def core-var
  (gen/elements [#'clojure.core/identity #'clojure.core/inc
                 #'clojure.core/str #'clojure.core/vec]))

(def anon-fn
  (gen/elements [(fn [_] nil) (fn [_] 1) (fn [_] :ok)]))

(def non-delta
  "Values that are neither a recognized step map nor a symbol/var/fn."
  (gen/one-of [gen/small-integer gen/string-alphanumeric gen/keyword
               (gen/return {}) (gen/return {:foo 1})]))

(def ^:private not-a-map
  (gen/one-of [gen/small-integer gen/string-alphanumeric gen/keyword]))

(def ^:private malformed-schema-val
  "A `:schema` value of the wrong shape."
  (gen/one-of [not-a-map
               (gen/fmap #(into {} (for [[k v] %] [k (str v)])) attr->def)   ; attr -> non-map
               (gen/fmap #(into {} (for [[_ v] %] [(str (gensym "a")) v])) attr->def)])) ; non-kw key

(def ^:private malformed-remove-val
  "A `:schema/remove` value of the wrong shape."
  (gen/one-of [not-a-map
               (gen/vector gen/small-integer 1 4)
               (gen/vector gen/string-alphanumeric 1 4)]))

(def malformed-delta
  "Maps that name :schema / :schema/remove but with wrong-shaped values."
  (gen/one-of [(gen/fmap (fn [v] {:schema v}) malformed-schema-val)
               (gen/fmap (fn [v] {:schema/remove v}) malformed-remove-val)]))

(def step
  "Any migration step shape — drives the step-phase / step-summary specs."
  (gen/one-of [fn-symbol core-var anon-fn tx-step
               additive-delta removal-delta mixed-delta non-delta malformed-delta]))

(def migration-spec
  "A valid input to `->migration`: additive :up (so :down auto-derives), with an
  occasional :transaction?/:irreversible? override."
  (gen/let [id    (gen/fmap str gen/nat)
            up    (gen/vector additive-delta 1 4)
            extra (gen/one-of [(gen/return {})
                               (gen/return {:transaction? false})
                               (gen/return {:irreversible? true})])]
    (merge {:id id :up up} extra)))

;; ---------------------------------------------------------------------------
;; Migration plans (for the stateful specs)
;; ---------------------------------------------------------------------------

(defn- group->migration [i group]
  {:id (format "m%03d" i)
   :up [{:schema (into {} group)}]})

(def plan
  "A migration plan: a vector of additive migration specs with pairwise-distinct
  attrs and zero-padded sequential ids, plus the full attr->def map for assertions.
  Each migration carries one multi-attr additive `:schema` step."
  (gen/let [a->d attr->def
            k    (gen/choose 1 (count a->d))]
    (let [group-sz (max 1 (long (Math/ceil (/ (count a->d) (double k)))))
          groups   (partition-all group-sz (seq a->d))]
      {:migrations (vec (map-indexed group->migration groups))
       :attr->def  a->d})))
