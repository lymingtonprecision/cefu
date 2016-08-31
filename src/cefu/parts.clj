(ns cefu.parts
  (:require [yesql.core :refer [defquery]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility fns

(defn namespace-keys [ns m]
  (reduce
   (fn [rs [k v]]
     (assoc rs (keyword ns (name k)) v))
   {}
   m))

(defn index-by
  ([]
   (index-by identity identity))
  ([key-fn]
   (index-by key-fn identity))
  ([key-fn val-fn]
   (fn [coll]
     (reduce
      (fn [rs x] (assoc rs (key-fn x) (val-fn x)))
      {}
      coll)))
  ([key-fn val-fn coll]
   ((index-by key-fn val-fn) coll)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query

(defquery parts "cefu/queries/parts.sql")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(defn lookup-table
  "Returns a map of customer part numbers to part records.

  The part records include the:

  * `:part/id` – the parts unique ID in the database.
  * `:part/contract` – the contract under which the part exists
  * `:part/number` – the customer assigned number for the part

  Each part is mapped to the _latest_ revision currently in the database."
  [db-spec]
  (parts
   {}
   {:connection db-spec
    :result-set-fn (index-by :number (partial namespace-keys "part"))}))
