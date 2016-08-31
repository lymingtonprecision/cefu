(ns cefu.forecast
  (:require [clojure.data :as data]
            [clojure.set :as set]
            [com.rpl.specter :refer [ALL FIRST MAP-VALS]]
            [com.rpl.specter.macros :as specter]))

(def path-to-parts [MAP-VALS ALL FIRST])
(def path-to-dates [MAP-VALS MAP-VALS ALL FIRST])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility fns

(defn flatmap [forecast-map]
  (into
   {}
   (for [[spid ps]  forecast-map
         [part ds]  ps
         [date qty] ds
         :let [part (select-keys part [:part/contract :part/id :part/number])]]
     [[spid part date] qty])))

(defn filter-subprojects [pred forecast-map]
  (reduce
   (fn [rs [spid parts]]
     (if (pred spid parts) (assoc rs spid parts) rs))
   {}
   forecast-map))

(defn filter-parts [pred forecast-map]
  (filter-subprojects
   (fn [_ parts] (seq parts))
   (specter/transform
    [MAP-VALS]
    #(reduce (fn [rs [part fc]] (if (pred part) (assoc rs part fc) rs)) {} %)
    forecast-map)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(defn rekey-parts
  "Replaces each part key, under the sub-projects, in the forecast map
  with the result of `(key-fn part)`."
  [key-fn forecast-map]
  (specter/transform path-to-parts key-fn forecast-map))

(defn rekey-dates
  "Replaces each date key, under the parts, in the forecast map with the
  result of `(key-fn date)`."
  [key-fn forecast-map]
  (specter/transform path-to-dates key-fn forecast-map))

(defn group-by-ided
  "Returns a tuple of `[fcmap-of-parts-without-ids fcmap-of-parts-with-ids]`
  where each element of the tuple consists of the entries from the given
  forecast map that either do not or do have an `:part/id`.

  (Note: the returned values have the same structure as the original
  forecast map.)"
  [forecast-map]
  [(filter-parts #(nil? (:part/id %)) forecast-map)
   (filter-parts #(:part/id %) forecast-map)])

(defn diff
  "Recuresively compares the two forecast maps, `a` and `b`, returning a
  tuple of `[things-only-in-a things-only-in-b things-in-both-that-differ equal-things-in-both]`.

  Each entry in the tuple is a map of the key path from the forecast map
  to the value from the respective forecast. The `key-path`s are always
  sequences of the `[sub-project-id part date]` of the forecast entry
  and the `value` is the quantity forecasted for that date.

  Values for entries in the `things-in-both-that-differ` collection
  differ in that they are tuples of `(value-from-a value-from-b)`.

  The resulting diff is intended to map to the semantics of CRUD
  database operations as `[inserts deletions updates no-ops]`, this
  assumes that `a` is the “new” forecast and `b` is the “old” forecast.

  Example:

      (data/diff
       ;; new
       {\"S001\" {{:part/id \"100104864R02\"}
                  {\"2016-08-31\" 2
                   \"2016-08-27\" 4
                   \"2016-10-07\" 4}}}
       ;; old
       {\"S001\" {{:part/id \"100104864R02\"}
                  {\"2016-08-22\" 4
                   \"2016-08-31\" 4
                   \"2016-08-27\" 4}}})
      ;=> [;; only in “new”
           {[\"S001\" {:part/id \"100104864R02\"} \"2016-10-07\"] 4}
           ;; only in “old”
           {[\"S001\" {:part/id \"100104864R02\"} \"2016-08-22\"] 4}
           ;; in both, different quantities (new old)
           {[\"S001\" {:part/id \"100104864R02\"} \"2016-08-31\"] (2 4)}
           ;; in both, same quantity
           {[\"S001\" {:part/id \"100104864R02\"} \"2016-08-27\"] 4}]
  "
  [a b]
  (let [a (flatmap a)
        b (flatmap b)
        d (data/diff a b)
        ;;
        a-keys (set (keys (first d)))
        b-keys (set (keys (second d)))
        keys-only-in-a (set/difference a-keys b-keys)
        keys-only-in-b (set/difference b-keys a-keys)
        keys-in-both   (set/intersection a-keys b-keys)
        ;;
        only-in-a (select-keys (first d) keys-only-in-a)
        only-in-b (select-keys (second d) keys-only-in-b)
        changed-as (select-keys (first d) keys-in-both)
        changed-bs (select-keys (second d) keys-in-both)
        changed (merge-with (fn [& args] args) changed-as changed-bs)
        in-both (last d)]
    [only-in-a
     only-in-b
     changed
     in-both]))

(def diff-crud-op [:insert :delete :update])

(defn crud-ops
  "Returns a collection of CRUD operation argument sequences from the
  given `diff` result.

  Each entry in the collection will be one of:

      [:insert sub-project-id part date quantity]
      [:update sub-project-id part date adjustment]
      [:delete sub-project-id part date]

  The `adjustment` given for `:update`s is the difference between the
  new and old quantities (e.g. if the quantity has changed from 2 to 4
  it would be 2, if the change was from 10 to 5 it would be -5.)"
  [diff]
  (for [[op xs] (map-indexed (fn [i x] [(get diff-crud-op i) x]) (butlast diff))
        [[spid part date] q] xs
        :let [q (if (= op :update) (apply - q) q)]]
    (if (= op :delete)
      [op spid part date]
      [op spid part date q])))
