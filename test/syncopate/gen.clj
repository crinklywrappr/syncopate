(ns syncopate.gen
  "test.check generators for Syncopate migration plans. Support namespace — no
  tests live here."
  (:require [clojure.test.check.generators :as gen]))

(def attr
  "A distinct-friendly qualified attribute keyword, e.g. :gen/a42."
  (gen/fmap #(keyword "gen" (str "a" %)) gen/nat))

(def value-type
  "Datalevin datalog value types usable with a bare `{:db/valueType _}` in the
  stateful specs. Excludes `:db.type/tuple` (needs companion keys) and
  `:db.type/vec`/`:db.type/idoc` (embedding/JSON-index machinery that degrades
  under the specs' repeated add/remove churn)."
  (gen/elements [:db.type/string :db.type/long :db.type/boolean :db.type/keyword
                 :db.type/double :db.type/float :db.type/symbol :db.type/instant
                 :db.type/uuid :db.type/bigint :db.type/bigdec :db.type/ref
                 :db.type/bytes]))

(def attr-def
  "An attribute definition: usually a `:db/valueType` (sometimes omitted → EDN
  blob), with an occasional valid `:db/cardinality` / `:db/unique`. All combos
  are probe-verified as accepted by `update-schema`; `:db/unique` is never paired
  with cardinality-many."
  (gen/let [vt   (gen/frequency [[4 value-type] [1 (gen/return nil)]])
            card (gen/elements [nil :db.cardinality/one :db.cardinality/many])
            uniq (gen/frequency [[5 (gen/return nil)]
                                 [1 (gen/return :db.unique/identity)]
                                 [1 (gen/return :db.unique/value)]])]
    (cond-> {}
      vt   (assoc :db/valueType vt)
      card (assoc :db/cardinality card)
      (and uniq (not= card :db.cardinality/many)) (assoc :db/unique uniq))))

(def attr->def
  "A non-empty map of distinct attrs → attribute definitions."
  (gen/fmap (partial into {})
            (gen/not-empty
             (gen/vector-distinct-by first (gen/tuple attr attr-def)
                                     {:max-elements 12}))))

;; ---------------------------------------------------------------------------
;; Schema deltas and migration steps (for the pure/unit generative tests)
;; ---------------------------------------------------------------------------

(def broad-value-type
  "All 16 Datalevin datalog value types — for PURE specs only (no DB), so the
  exotic types (`:db.type/tuple`/`vec`/`idoc`) get exercised in classification
  without the schema-churn hang they cause against a real store."
  (gen/elements [:db.type/string :db.type/long :db.type/boolean :db.type/keyword
                 :db.type/double :db.type/float :db.type/symbol :db.type/instant
                 :db.type/uuid :db.type/bigint :db.type/bigdec :db.type/ref
                 :db.type/bytes :db.type/tuple :db.type/vec :db.type/idoc]))

(def broad-attr-def
  "Like `attr-def` but over all 16 value types (pure specs only)."
  (gen/let [vt   (gen/frequency [[4 broad-value-type] [1 (gen/return nil)]])
            card (gen/elements [nil :db.cardinality/one :db.cardinality/many])
            uniq (gen/frequency [[5 (gen/return nil)]
                                 [1 (gen/return :db.unique/identity)]
                                 [1 (gen/return :db.unique/value)]])]
    (cond-> {}
      vt   (assoc :db/valueType vt)
      card (assoc :db/cardinality card)
      (and uniq (not= card :db.cardinality/many)) (assoc :db/unique uniq))))

(def broad-attr->def
  (gen/fmap (partial into {})
            (gen/not-empty
             (gen/vector-distinct-by first (gen/tuple attr broad-attr-def)
                                     {:max-elements 12}))))

(def additive-delta
  "A purely-additive schema delta: {:schema {attr def ...}}. Uses the broad all-16
  type space — pure-only (nothing stateful consumes `additive-delta`)."
  (gen/fmap (fn [m] {:schema m}) broad-attr->def))

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
;; Data-carrying migrations (Tier 1): value generators per Datalevin type
;; ---------------------------------------------------------------------------

(def data-value-type
  "Value types with easy value generation + equality (subset of `value-type`)."
  (gen/elements [:db.type/string :db.type/long :db.type/boolean :db.type/keyword
                 :db.type/double :db.type/uuid :db.type/instant :db.type/bigint
                 :db.type/bigdec :db.type/symbol]))

(defn value-for
  "A generator of values matching Datalevin value type `vt`."
  [vt]
  (case vt
    :db.type/string  gen/string-alphanumeric
    :db.type/long    gen/large-integer
    :db.type/boolean gen/boolean
    :db.type/keyword gen/keyword
    :db.type/double  (gen/double* {:infinite? false :NaN? false})
    :db.type/uuid    gen/uuid
    :db.type/instant (gen/fmap #(java.util.Date. (long %)) gen/large-integer)
    :db.type/bigint  (gen/fmap bigint gen/large-integer)
    :db.type/bigdec  (gen/fmap bigdec gen/large-integer)
    :db.type/symbol  (gen/fmap #(symbol (str "v" %)) gen/nat)))

(def clean-name
  "A \"First Last\" name of two alphanumeric words — splits on whitespace and
  rejoins with a single space exactly, so split/join fn migrations round-trip."
  (gen/fmap (fn [[a b]] (str a " " b))
            (gen/tuple (gen/not-empty gen/string-alphanumeric)
                       (gen/not-empty gen/string-alphanumeric))))

(defn- rich-migration
  "A migration touching one distinct attr `:gen/aI`. Either purely additive (auto
  `:down`) or data-carrying: adds the attr, inserts entities via a `:tx` step, and
  removes it in `:down` (so `apply-delta!`'s retract-then-drop runs with data
  present). Yields `{:spec … :attr … :count …}` (count = expected datoms)."
  [i]
  (gen/let [kind (gen/elements [:additive :data])
            vt   data-value-type
            vals (gen/vector (value-for vt) 0 5)
            txn? (gen/frequency [[3 (gen/return true)] [1 (gen/return false)]])]
    (let [a    (keyword "gen" (str "a" i))
          id   (format "r%03d" i)
          spec (if (= kind :data)
                 {:id   id
                  :up   [{:schema {a {:db/valueType vt}}}
                         {:tx (mapv (fn [v] {a v}) vals)}]
                  :down [{:schema/remove [a]}]}
                 {:id id :up [{:schema {a {:db/valueType vt}}}]})]
      {:spec  (cond-> spec (not txn?) (assoc :transaction? false)) ; exercise the non-atomic path
       :attr  a
       :count (if (= kind :data) (count vals) 0)})))

(def rich-plan
  "A plan mixing additive and data-carrying migrations over distinct attrs, plus a
  model of each migration's attr + expected datom count."
  (gen/let [n    (gen/choose 1 6)
            migs (apply gen/tuple (map rich-migration (range n)))]
    {:migrations (mapv :spec migs)
     :model      (mapv #(select-keys % [:attr :count]) migs)}))

;; ---------------------------------------------------------------------------
;; Cross-migration churn (Tier 4a): add / remove / re-add attrs over a pool,
;; emitting only model-valid ops (add absent, remove present).
;; ---------------------------------------------------------------------------

(def churn-plan
  "A sequence of migrations over a small attr pool where an attr may be added,
  removed (explicit `:down` re-adds), then re-added. Only valid ops are emitted
  (add when absent, remove when present). Returns the migrations + the final live
  attr set + the touched attr pool."
  (gen/let [pool (gen/choose 1 4)
            raw  (gen/vector (gen/tuple (gen/elements [:add :remove])
                                        (gen/choose 0 3))
                             0 14)]
    (let [attrs (mapv #(keyword "gen" (str "c" %)) (range pool))
          def*  {:db/valueType :db.type/long}
          step  (fn [{:keys [live migs i]} [op idx]]
                  (let [a  (nth attrs (mod idx pool))
                        id (format "c%03d" i)]
                    (cond
                      (and (= op :add) (not (live a)))
                      {:live (conj live a) :i (inc i)
                       :migs (conj migs {:id id :up [{:schema {a def*}}]})}
                      (and (= op :remove) (live a))
                      {:live (disj live a) :i (inc i)
                       :migs (conj migs {:id id :up [{:schema/remove [a]}]
                                         :down [{:schema {a def*}}]})}
                      :else {:live live :i i :migs migs})))
          {:keys [live migs]} (reduce step {:live #{} :migs [] :i 0} raw)]
      {:migrations migs :final-live live :attrs (set attrs)})))

;; ---------------------------------------------------------------------------
;; Heterogeneous ids (Tier 4b): stress applied-id ordering / sort with varied,
;; non-padded, non-numeric ids (applied in id order, as loaders/ragtime do).
;; ---------------------------------------------------------------------------

(def distinct-ids
  "A vector of distinct arbitrary string ids, in arbitrary (set) order — used to
  apply migrations *out of id order* and confirm applied-id order tracks
  application order."
  (gen/fmap vec
            (gen/not-empty (gen/set (gen/not-empty gen/string-alphanumeric)
                                    {:max-elements 8}))))

(def weird-id-plan
  "Additive migrations with distinct, arbitrary string ids, ordered by id (as
  `load-directory` would present them). Returns the migrations + the id-sorted ids."
  (gen/let [ids (gen/not-empty
                 (gen/set (gen/not-empty gen/string-alphanumeric) {:max-elements 8}))]
    (let [sorted (vec (sort ids))
          migs   (map-indexed
                  (fn [i id]
                    {:id id
                     :up [{:schema {(keyword "gen" (str "w" i)) {:db/valueType :db.type/long}}}]})
                  sorted)]
      {:migrations (vec migs) :sorted-ids sorted})))

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
