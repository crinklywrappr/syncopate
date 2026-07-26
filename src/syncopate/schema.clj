(ns syncopate.schema
  "Declarative schema deltas for Datalevin migrations.

  A delta is a map with either or both of:
    :schema        - map of attr -> attribute-definition to add or update,
                     applied via `datalevin.core/update-schema`.
    :schema/remove - collection of attrs to drop. Datalevin won't drop an attr
                     that still has datoms, so we retract them first.

  Additive deltas (`:schema` only) are invertible, which is what powers the
  auto-`:down` sugar in `syncopate.core`."
  (:require [datalevin.core :as d]
            [taoensso.trove :as trove]))

(defn- attrs-map?
  "A `:schema` value: a map of attribute keyword -> definition map."
  [m]
  (and (map? m) (every? (fn [[a d]] (and (keyword? a) (map? d))) m)))

(defn- attr-coll?
  "A `:schema/remove` value: a collection of attribute keywords."
  [xs]
  (and (coll? xs) (every? keyword? xs)))

(defn schema-delta?
  "True if `step` is a well-formed declarative schema delta: a map carrying a
  `:schema` (attr keyword -> definition map) and/or a `:schema/remove`
  (collection of attr keywords). Malformed shapes are rejected so they surface as
  an \"unrecognised step\" early rather than as a cryptic Datalevin error."
  [step]
  (and (map? step)
       (or (contains? step :schema) (contains? step :schema/remove))
       (or (not (contains? step :schema))        (attrs-map? (:schema step)))
       (or (not (contains? step :schema/remove)) (attr-coll? (:schema/remove step)))))

(defn additive?
  "True if `step` is a purely additive schema delta (a `:schema` with no
  `:schema/remove`) — the only shape we can automatically invert."
  [step]
  (and (schema-delta? step)
       (not (contains? step :schema/remove))))

(defn invert
  "Invert a purely additive schema delta: adding attrs becomes removing them.
  Removals cannot be auto-inverted (the prior attribute definitions are
  unknown), so callers must guard with `additive?` first."
  [{add :schema}]
  {:schema/remove (vec (keys add))})

(defn apply-delta!
  "Apply a schema delta to Datalog connection `conn`.

  Removals retract every datom of each attr before the attr is dropped from the
  schema, so no data is silently orphaned and `update-schema` won't reject the
  drop."
  [conn {add :schema remove :schema/remove}]
  (trove/log! {:level :debug :id :syncopate/schema-delta
               :msg  "Applying schema delta"
               :data {:add (vec (keys add)) :remove (vec remove)}})
  (when (seq remove)
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
        (d/transact! conn retractions))))
  (d/update-schema conn (or add {}) (set remove))
  conn)
