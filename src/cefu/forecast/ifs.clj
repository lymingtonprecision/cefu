(ns cefu.forecast.ifs
  (:require [clj-time.coerce :as time.coerce]
            [clojure.java.jdbc :refer [with-db-connection]]
            [yesql.core :refer [defquery]]
            [yesql.util :refer [slurp-from-classpath]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility fns

(defn add-to-forecast-map [forecast-map row]
  (let [spid (:sub_project_id row)
        d (time.coerce/to-date-time (:date row))
        p {:part/contract (:site row)
           :part/id (:part_id row)
           :part/number (:part_number row)}
        q (double (:qty row))]
    (update-in forecast-map [spid p d] (fn [s] (+ (or s 0) q)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queries

(defquery current-demands "cefu/queries/current_project_demands.sql")
(def -remove-demand! (slurp-from-classpath "cefu/queries/remove_demand.sql"))
(def -insert-demand! (slurp-from-classpath "cefu/queries/insert_demand.sql"))
(def -increase-demand! (slurp-from-classpath "cefu/queries/increase_demand.sql"))
(def -decrease-demand! (slurp-from-classpath "cefu/queries/decrease_demand.sql"))
(def -remove-empty-activities! (slurp-from-classpath "cefu/queries/remove_empty_activities.sql"))

(defn prepare-adjustment [db-con sql]
  (.prepareCall db-con sql))

(defn execute-adjustment!
  [prepared-call project-id sub-project-id part-id date qty]
  (doto prepared-call
    (.setString ":project_id" project-id)
    (.setString ":sub_project_id" sub-project-id)
    (.setString ":part_id" part-id)
    (.setDate ":date" (time.coerce/to-sql-date date)))
  (when qty (.setDouble prepared-call ":qty" (double qty)))
  (.executeUpdate prepared-call))

(defn remove-empty-activities
  [db-con project-id]
  (let [stmt (doto (.prepareCall db-con -remove-empty-activities!)
               (.setString ":project_id" project-id))]
    (.executeUpdate stmt)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(defn forecast-map
  "Returns a map of the sub-projects, the parts on them, and their demands
  from the specified project.

      {'sub-project-id
       {'part-record
        {'date 'qty
         '…    'qty}
        '… 'demands}
       '… 'parts-and-demands}

  Each part record consists of the:

  * `:part/id` – the parts unique ID in the database.
  * `:part/contract` – the contract under which the part exists
  * `:part/number` – the customer assigned number for the part"
  [db-spec project-id]
  (current-demands
   {:project_id project-id}
   {:connection db-spec
    :result-set-fn #(reduce add-to-forecast-map {} %)}))

(defn update-project
  "Updates the specified project in IFS by applying the given CRUD
  operations to it.

  The CRUD ops must be a collection of sequences as produced by
  `cefu.forecast/crud-ops`.

  Any empty activities for periods prior to the current month will be
  removed from the project after performing the updates."
  [db-con project-id crud-ops]
  (let [stmts (reduce
               (fn [rs [k v]] (assoc rs k (prepare-adjustment db-con v)))
               {}
               {:insert -insert-demand!
                :increase -increase-demand!
                :decrease -decrease-demand!
                :delete -remove-demand!})]
    (doseq [[op spid part date qty] crud-ops
            :let [update (when (= op :update) (if (pos? qty) :increase :decrease))
                  qty (if update (Math/abs qty) qty)
                  op (or update op)
                  s (stmts op)]]
      (execute-adjustment! s project-id spid (:part/id part) date qty))
    (remove-empty-activities db-con project-id)))
