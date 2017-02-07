(ns cefu.forecast.slb
  (:require [clojure.set :as set]
            [clojure.spec :as spec]
            [clojure.string :as string]
            [clj-time.coerce :as time.coerce]
            [dk.ative.docjure.spreadsheet :as spreadsheet]
            [cefu.spec])
  (:import [org.apache.poi.ss.usermodel Sheet Row Cell]
           [org.joda.time.base BaseDateTime]))

(def slb-ns-name (str *ns*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specs

(spec/def ::header-map (spec/map-of keyword? (spec/and integer? (complement neg?))))

(spec/def ::center string?)
(spec/def ::record-type keyword?)
(spec/def ::qty number?)
(spec/def ::date #(or (inst? %) (instance? BaseDateTime %)))

(spec/def
 ::indexed-row
 (spec/merge
  :cefu/part
  (spec/keys
   :req [::center
         ::record-type
         ::date
         ::qty])))

(def required-columns
  #{::center
    ::record-type
    :part/number
    ::date
    ::qty})

(def optional-columns
  #{:part/description})

(def indexed-row-keys
  (set/union required-columns optional-columns))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility fns

(defn some-index
  "Returns a tuple of the first logical true value of (pred x) and its
  index (starting from 0 or `i` if given) within the collection, else
  `nil`.

      (some-index #{:fred} [:mary :joe :bob :fred :willma])
      ;=> [:fred 3]
  "
  ([pred coll]
   (some-index pred coll 0))
  ([pred coll i]
   (when (seq coll)
     (if-let [v (pred (first coll))]
       [v i]
       (recur pred (next coll) (inc i))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Value coercion/sanitization fns

(defn sanitize-string
  "Returns a string stripped of extraneous whitespace and separators,
  lower cased, kebab'ized (spaces replaced with hyphens), and with some
  common abbreviations applied (e.g. “quantity” becomes “qty”.)

  Returns its argument if given anything other than a string."
  [s]
  (if (string? s)
    (-> s
        string/lower-case
        (string/replace #"[_\-\/\\]" " ")
        (string/replace #"[^a-z ]" "")
        (string/replace #"\s{2,}" " ")
        string/trim
        (string/replace #"quantity" "qty")
        (string/replace #"\s" "-"))
    s))

(defn sanitize-header
  "Returns the namespaced keyword equivilent to the given spreadsheet
  column heading."
  [v]
  (let [str? (string? v)
        v (if str? (sanitize-string v) v)
        part? (and str? (string/starts-with? v "part-"))]
    (case [str? part?]
      [true true]   (apply keyword (string/split v #"-" 2))
      [true false]  (keyword slb-ns-name v)
      [false false] v)))

(def replacement-center
  "Maps SLB “Center” codes to replacement values that should be used to
  enable more effective grouping of forecast entries."
  {"HFE" "RTST"
   "SBT" "SDRM"})

(defn sanitize-value
  "Given a (keyword) column heading, `k`, and a cell value, `v`, returns
  a coerced/sanitized version of the value matching our expectations for
  entries in that column."
  [k v]
  (let [v (if (instance? Cell v) (spreadsheet/read-cell v) v)
        v (if (string? v) (string/trim v) v)]
    (case k
      ::date (time.coerce/to-date-time v)
      ::record-type (keyword (sanitize-string v))
      ::center (let [v (string/upper-case v)] (get replacement-center v v))
      v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Columns headings

(defn row->headings-map
  "Converts the cell values in the given row to namespaced keyword
  equivilents and returns a map of them to their indicies."
  [row]
  (into
   {}
   (map-indexed
    (fn [i c]
      (let [v (if (instance? Cell c) (spreadsheet/read-cell c) c)]
        [(sanitize-header v) i]))
    row)))

(defn missing-columns?
  "Returns the set required column header keys not found in the given
  map, or `nil` if all required keys are present."
  [heading-map]
  (seq
   (set/difference
    required-columns
    (set/intersection
     required-columns
     (set (keys heading-map))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Row parsing

(defn header-row?
  "Returns a map of column headings to column indicies if the given row
  includes the expected column headers, otherwise returns `nil`."
  [row]
  (let [hm (row->headings-map row)]
    (when-not (missing-columns? hm)
      hm)))

(defn index-row
  "Returns a map of sanitized values from the given row using the keys
  from the `heading-map`."
  [heading-map row]
  (let [row (if (instance? Row row) (spreadsheet/cell-seq row) row)]
    (reduce-kv
     (fn [rs k i]
       (assoc rs k (sanitize-value k (nth row i))))
     {}
     heading-map)))

(def forecast-record-types
  #{:planned-order})

(defn forecast-row?
  "Returns true if the given indexed row map (see `index-row`) is for
  one of the `forecast-record-types` and has a positive quantity."
  [indexed-row]
  (and (contains? forecast-record-types (::record-type indexed-row))
       (pos? (::qty indexed-row))))

(spec/fdef forecast-row?
  :args (spec/cat :r ::indexed-row)
  :ret boolean?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Forecast mapping

(def slb-center->ifs-project
  "Maps SLB “center” codes to the IFS Sub Project IDs under which their
  forecast entries are stored."
  {"SDRM" "S004"
   "SDPU" "S005"
   "RTST" "S003"
   "QBT"  "S004"})

(defn forecast-map
  "Builds a nested project forecast map from the given collection of
  indexed rows (see `index-row`.)

  The project forecast will be structured as follows:

      IFS Sub Project ID
        Part
          Date => Quantity

  If the part belongs to a “center” for which we _don’t_ have an IFS
  project mapping then the top level key will be a tuple of
  `[::center <SLB center code>]`."
  [indexed-rows]
  (reduce
   (fn [rs {c ::center d ::date q ::qty :as r}]
     (let [spid (or (slb-center->ifs-project c)
                    [::center c])
           p (select-keys r [:part/number :part/description])]
       (update-in rs [spid p d] (fn [s] (+ (or s 0) q)))))
   {}
   indexed-rows))

(spec/fdef forecast-map
  :args (spec/cat :r ::indexed-row)
  :ret :cefu/forecast-map)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(def ^:dynamic *forecast-sample-size*
  "The number of rows to sample when checking a sheet for a forecast"
  10)

(defn forecast-sheet?
  "Retruns `true` if the given spreadsheet appears to contain a customer
  forecast (determined by sampling a small number of rows from the start
  of the sheet.)"
  [^Sheet sheet]
  (let [rows (spreadsheet/row-seq sheet)
        [hdr offset] (some-index header-row? (take *forecast-sample-size* rows) 1)]
    (when hdr
      (every? #(not= ::spec/invalid
                     (spec/conform ::indexed-row (index-row hdr %)))
              (take *forecast-sample-size* (drop offset rows))))))

(spec/fdef forecast-sheet?
  :args (spec/cat :sheet (partial instance? Sheet))
  :ret (spec/nilable boolean?))

(defn sheet->forecast
  "Returns a nested project forecast map built from the given SV order
  list spreadsheet."
  [^Sheet sheet]
  (let [rows (spreadsheet/row-seq sheet)
        [hdr offset] (some-index header-row? rows 1)]
    (when-not hdr
      (throw (ex-info "could not find header row in sheet" {:sheet sheet})))
    (forecast-map
     (sequence
      (comp
       (map (partial index-row (select-keys hdr indexed-row-keys)))
       (filter forecast-row?))
      (drop offset rows)))))

(spec/fdef sheet->forecast
  :args (spec/cat :sheet (partial instance? Sheet))
  :ret :cefu/forecast-map)
