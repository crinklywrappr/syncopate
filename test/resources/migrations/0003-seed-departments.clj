;; A .clj migration: steps may be real functions, evaluated when loaded.
{:up   [{:schema/create {:dept/name {:db/valueType :db.type/string
                                    :db/unique    :db.unique/identity}}}
        (fn [conn]
          (datalevin.core/transact! conn [{:dept/name "Engineering"}
                                          {:dept/name "Sales"}]))]
 :down [(fn [conn]
          (let [es (datalevin.core/q '[:find [?e ...] :where [?e :dept/name]]
                                     (datalevin.core/db conn))]
            (when (seq es)
              (datalevin.core/transact! conn (mapv #(vector :db/retractEntity %) es)))))
        {:schema/remove [:dept/name]}]}
