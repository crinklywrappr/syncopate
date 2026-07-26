(ns syncopate.example
  "Data-transform functions referenced by symbol from the example EDN
  migrations. In a real project these would live in your app's codebase."
  (:require [datalevin.core :as d]
            [clojure.string :as str]))

(defn split-user-names
  "Split each :user/name \"Given Family\" into :user/given-name / :user/family-name."
  [conn]
  (let [rows (d/q '[:find ?e ?name :where [?e :user/name ?name]] (d/db conn))
        tx   (for [[e name] rows
                   :let [[g f] (str/split name #"\s+" 2)]]
               {:db/id e :user/given-name g :user/family-name (or f "")})]
    (when (seq tx) (d/transact! conn (vec tx)))))

(defn join-user-names
  "Recombine :user/given-name / :user/family-name back into :user/name."
  [conn]
  (let [rows (d/q '[:find ?e ?g ?f
                    :where [?e :user/given-name ?g] [?e :user/family-name ?f]]
                  (d/db conn))
        tx   (for [[e g f] rows]
               {:db/id e :user/name (str/trim (str g " " f))})]
    (when (seq tx) (d/transact! conn (vec tx)))))
