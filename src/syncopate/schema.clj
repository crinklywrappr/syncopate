(ns syncopate.schema
  "Declarative schema operations for Datalevin migrations.

  A schema step carries exactly one operation, along two orthogonal axes:

    existence axis (does the attribute exist at all):
      :schema/create - map of attr -> definition to add NEW attributes.
                       Auto-invertible (its :down drops them again), which powers
                       the auto-`:down` sugar in `syncopate.core`.
      :schema/remove - collection of attrs to drop. Datalevin won't drop an attr
                       that still has datoms, so we retract them first. NOT
                       auto-invertible (the data, and prior definitions, are gone)
                       — supply an explicit :down or set :irreversible?.

    definition axis (change an existing attribute's property keys):
      :schema/alter  - map of attr -> definition, merged (patched) into the
                       existing definition via `update-schema`. The attribute
                       keeps existing. NOT auto-invertible (the prior definition
                       is unknown) — supply an explicit :down (itself a
                       :schema/alter describing the inverse change).

  `:schema/create` and `:schema/alter` apply identically at run time
  (`update-schema conn <map>`); they differ only in declared reversibility. We
  never inspect the live schema to guess intent — the declared key IS the intent."
  (:require [datalevin.core :as d]
            [taoensso.trove :as trove]))

(defn- attrs-map?
  "A `:schema/create` or `:schema/alter` value: a map of attribute keyword ->
  definition map."
  [m]
  (and (map? m) (every? (fn [[a d]] (and (keyword? a) (map? d))) m)))

(defn- attr-coll?
  "A `:schema/remove` value: a collection of attribute keywords."
  [xs]
  (and (coll? xs) (every? keyword? xs)))

(defn legacy-schema-step?
  "True if `step` uses the obsolete bare `:schema` key, which has been replaced by
  `:schema/create` (new attributes) and `:schema/alter` (modify existing ones).
  Detected so callers can give an actionable upgrade error rather than a cryptic
  \"unrecognised step\"."
  [step]
  (and (map? step) (contains? step :schema)))

(defn schema-delta?
  "True if `step` is a well-formed schema operation: a map carrying exactly one of
  `:schema/create` / `:schema/alter` (attr keyword -> definition map) or
  `:schema/remove` (collection of attr keywords). Malformed or ambiguous shapes
  are rejected so they surface early rather than as a cryptic Datalevin error."
  [step]
  (and (map? step)
       (let [create? (contains? step :schema/create)
             alter?  (contains? step :schema/alter)
             remove? (contains? step :schema/remove)]
         (and (= 1 (count (filter true? [create? alter? remove?])))
              (or (not create?) (attrs-map? (:schema/create step)))
              (or (not alter?)  (attrs-map? (:schema/alter step)))
              (or (not remove?) (attr-coll? (:schema/remove step)))))))

(defn additive?
  "True if `step` is a `:schema/create` — the only operation we can automatically
  invert (its :down removes the just-created attributes)."
  [step]
  (and (schema-delta? step)
       (contains? step :schema/create)))

(defn invert
  "Invert a `:schema/create`: creating attrs becomes removing them. Only valid for
  `:schema/create`; `:schema/alter` and `:schema/remove` are not auto-invertible,
  so callers must guard with `additive?` first."
  [{create :schema/create}]
  {:schema/remove (vec (keys create))})

(defn- apply-remove!
  "Retract every datom of each attr in `remove`, then drop the attrs from the
  schema — so no data is silently orphaned and `update-schema` won't reject the
  drop."
  [conn remove]
  (let [db          (d/db conn)
        retractions (vec (for [a     remove
                               [e v] (d/q '[:find ?e ?v
                                            :in $ ?a
                                            :where [?e ?a ?v]]
                                          db a)]
                           [:db/retract e a v]))]
    (when (seq retractions)
      (trove/log! {:level :debug :id :syncopate/schema-retract
                   :msg  (format "Retracting %d datom(s) before dropping attr(s)"
                                 (count retractions))
                   :data {:attrs (vec remove) :count (count retractions)}})
      (d/transact! conn retractions)))
  (d/update-schema conn nil (set remove)))

(defn apply-delta!
  "Apply a single schema operation to Datalog connection `conn`.

  `:schema/create` and `:schema/alter` both merge their attr->definition map via
  `update-schema` (datalevin patches existing definitions). `:schema/remove`
  retracts every datom of each attr before dropping it."
  [conn step]
  (cond
    (contains? step :schema/create)
    (do (trove/log! {:level :debug :id :syncopate/schema-delta
                     :msg  "Applying schema operation"
                     :data {:op :create :attrs (vec (keys (:schema/create step)))}})
        (d/update-schema conn (:schema/create step)))

    (contains? step :schema/alter)
    (do (trove/log! {:level :debug :id :syncopate/schema-delta
                     :msg  "Applying schema operation"
                     :data {:op :alter :attrs (vec (keys (:schema/alter step)))}})
        (d/update-schema conn (:schema/alter step)))

    (contains? step :schema/remove)
    (do (trove/log! {:level :debug :id :syncopate/schema-delta
                     :msg  "Applying schema operation"
                     :data {:op :remove :attrs (vec (:schema/remove step))}})
        (apply-remove! conn (:schema/remove step))))
  conn)
