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
