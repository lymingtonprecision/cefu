(ns user)

(require '[clojure.data :as data]
         '[clojure.set :as set]
         '[clojure.spec :as spec]
         '[clojure.java.jdbc :refer [with-db-transaction]]
         '[clj-time.core :as time]
         '[clj-time.format :as time.format]
         '[dk.ative.docjure.spreadsheet :as spreadsheet]
         '[com.rpl.specter :as specter]
         '[com.rpl.specter.macros :as specter.macros]

         '[cefu.forecast.alc :as alc]
         '[cefu.forecast.slb :as slb]
         '[cefu.forecast.ifs :as ifs]
         '[cefu.forecast :as fc]
         '[cefu.dates :as dates]
         '[cefu.parts :as parts])

(import '[org.apache.poi.ss.usermodel Sheet])

(defn d->s [d]
  (time.format/unparse (time.format/formatters :date) d))

;; Alcatel

(def alc-wb (spreadsheet/load-workbook "m:/tmp/Alcatel Forecast Q1 2017 as of 2017-01-26.xlsx"))

#_
(map (fn [s] [(spreadsheet/sheet-name s)
              (alc/forecast-sheet? s)])
     (spreadsheet/sheet-seq alc-wb))

(def alc-fc (spreadsheet/select-sheet "Sheet1" alc-wb))

#_
(pprint (alc/sheet->forecast alc-fc))

(defn simple-alcatel-forcast
  "Returns a collection of the parts from the given forecast sheet
  using the forecasted quantities from the part rows themselves.

  (This differs from the standard `sheet->forecast` fn by ignoring any
  summary rows for the parts, in affect assuming that the spreadsheet
  only contains a single row for each part.)"
  [sheet]
  (let [rows (map alc/row->values (spreadsheet/row-seq sheet))
        headers (spec/conform ::alc/header-row (first rows))]
    (when (= headers ::spec/invalid)
      (throw
       (ex-info "unable to parse headers from first row"
                {:row (first rows)
                 ::spec/explain (spec/explain-data ::alc/header-row (first rows))})))
    {alc/alc-ifs-project-id
     (reduce
      (fn [rs part]
        (let [fc (alc/map-qtys-to-months
                  (::alc/forecast-years headers)
                  (:forecast/qtys part))
              p (select-keys part [:part/number :part/description])]
          (assoc rs p fc)))
      {}
      (sequence alc/rows->parts (rest rows)))}))

#_
(pprint (simple-alcatel-forcast alc-fc))

;; Schlumberger

(def slb-wb (spreadsheet/load-workbook "m:/tmp/SLB Adjusted Forecast 2017-02-06.xlsx"))

#_
(map (fn [s] [(spreadsheet/sheet-name s)
              (slb/forecast-sheet? s)])
     (spreadsheet/sheet-seq slb-wb))

#_
(def slb-fc (spreadsheet/select-sheet "LPE Supplier Workload Visualisa" slb-wb))
#_
(def slb-fc (spreadsheet/select-sheet "SLBAdjustedForecast " slb-wb))
(def slb-fc (spreadsheet/select-sheet "Sheet1" slb-wb))

(let [rows (map #(->> % spreadsheet/cell-seq (map spreadsheet/read-cell))
                (spreadsheet/row-seq slb-fc))
      hdr (slb/header-row? (first rows))
      fr (slb/index-row hdr (second rows))]
  (pprint
   (map
    #(spec/explain ::slb/indexed-row (slb/index-row hdr %))
    (take 10 (rest rows)))))

#_
(slb/forecast-sheet? slb-fc)

#_
(pprint (slb/sheet->forecast slb-fc))

#_
(def fc (merge (alc/sheet->forecast alc-fc)
               (slb/sheet->forecast slb-fc)))

#_
(def fc (simple-alcatel-forcast alc-fc))
#_
(def fc (slb/sheet->forecast slb-fc))

(def dlt (dates/substitute-table (:database reloaded.repl/system)))
(def plt (parts/lookup-table (:database reloaded.repl/system)))
(def prj (ifs/forecast-map (:database reloaded.repl/system) "LPE004"))

;; steps to using a forecast to update IFS:
;; * re-key the parts so they have IDs

#_
#'cefu.forecast/rekey-parts

;; * error if any parts _don't_ have IDs

#_
#'cefu.forecast/group-by-ided

;; * re-key the dates to all be working dates

#_
#'cefu.forecast/rekey-dates

;; * select the sub projects from IFS for which you have a forecast
;; * merge the forecasts (can occur any time before the next step)
;; * flatten maps so that they are single level (with the keys being
;;   a sequence of the old nested key path)

#_
#'cefu.forecast/flatmap

;; * diff
;; * upserts are the entries only in the forecast not in the project
;; * deletes are the entries in the project not in the forecast that
;;   don't have a key in the upserts
;; * no-ops are the entries in both

#_
#'cefu.forecast/diff

;; what's the diff?
#_
(let [fc (->> fc
              (fc/rekey-parts #(plt (:part/number %) %))
              (fc/rekey-dates #(let [wd (dates/closest-week-day-in-month %)]
                                 (dlt wd wd))))
      [bad-parts good-parts] (fc/group-by-ided fc)
      prj (select-keys prj (set (keys fc)))
      d (fc/diff good-parts prj)]
  (when (seq bad-parts)
    (println "Parts couldn't be ID'd:")
    (pprint bad-parts))
  (println "Diff results:")
  (pprint
   (map (fn [[op spid part date q]]
          [op spid (:part/contract part) (:part/id part) (d->s date) q])
        (fc/crud-ops d))))

;; update!
#_
(let [;; prep
      fc (->> fc
              (fc/rekey-parts #(plt (:part/number %) %))
              (fc/rekey-dates #(let [wd (dates/closest-week-day-in-month %)] (dlt wd wd))))
      [bad-parts good-parts] (fc/group-by-ided fc)
      prj (select-keys prj (set (keys fc)))
      d (fc/diff good-parts prj)
      ;; process
      ops (fc/crud-ops d)
      ops-by-sp-month
      (specter.macros/transform
       [specter/MAP-VALS]
       (fn [xs]
         (sort-by
          (fn [[op _ {part :part/id} date _]]
            [(get {:insert 2 :update 1 :delete 0} op)
             date
             part])
          xs))
       (group-by
        (fn [[op spid _ date _]]
          [spid (time.format/unparse (time.format/formatters :year-month) date)])
        ops))]
  (doseq [[[spid month] ops] ops-by-sp-month]
    (let [tx (:connection (:database reloaded.repl/system))]
      (pprint (str "performing " (count ops) " operations in " spid " for " month))
      (ifs/update-project tx "LPE004" ops)
      #_(.rollback tx)
      (.commit tx))))

(comment
 (.rollback (:connection (:database reloaded.repl/system)))
 (.commit (:connection (:database reloaded.repl/system))))
