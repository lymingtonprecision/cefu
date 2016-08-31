(ns cefu.forecast.alc
  (:require [clojure.spec :as spec]
            [clj-time.core :as time]
            [clj-time.format :as time.format]
            [dk.ative.docjure.spreadsheet :as spreadsheet]
            [cefu.spec])
  (:import [org.apache.poi.ss.usermodel Sheet Row Cell]))

(def alc-ifs-project-id "S001")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specs

(def short-month-names
  #{"Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"})

(def year-total-regex #"^(\d{4}) Total$")

(spec/def ::short-month-names short-month-names)
(spec/def ::total-for-year (spec/and string? #(re-matches year-total-regex %)))
(spec/def ::quantities (spec/+ (spec/nilable number?)))

(spec/def
  ::forecast-year
  (spec/cat
   ::months (spec/+ ::short-month-names)
   ::year ::total-for-year))

(spec/def
  ::header-row
  (spec/cat
   ::material-number-header (spec/and string? #(re-matches #"(?i)Material Number" %))
   ::material-description-header (spec/and string? #(re-matches #"(?i)Material Description" %))
   ::forecast-years (spec/+ ::forecast-year)))

(spec/def
  ::part-row
  (spec/cat
   :part/number :part/number
   :part/description :part/description
   :forecast/qtys ::quantities))

(def summary-row-types
  {"PlOrd." :part/planned-orders
   "ShipNt" :part/shipped-orders})

(def summary-row-labels
  (set (keys summary-row-types)))

(spec/def
  ::summary-row-label
  (spec/and string? summary-row-labels))

(spec/def
  ::summary-row
  (spec/cat
   :order-summary/id ::summary-row-label
   :order-summary/notes (spec/nilable string?)
   :order-summary/qtys ::quantities))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coercion fns

(defn cell-value
  "Returns the Clojure value of the given spreadsheet cell.

  **Note:** trims string values."
  [^Cell c]
  (let [v (spreadsheet/read-cell c)]
    (if (string? v) (clojure.string/trim v) v)))

(defn row->values
  "Returns a sequence of the cell values from the given row."
  [^Row row]
  (map cell-value (spreadsheet/cell-seq row)))

(def year-month-formatter
  (time.format/formatter "yyyy 'Total' MMM"))

(defn forecast-months
  "Returns a collection of `DateTime` instances for each month present
  in the given forecast year record (using the last day of the month as
  the distinct
  date.)

      (forecast-months
       {::months [\"Jul\" \"Aug\" \"Sep\" \"Oct\" \"Nov\" \"Dec\"]
        ::year \"2016 Total\"})
      ;=> [#object[DateTime \"2016-07-31\"]
           #object[DateTime \"2016-08-31\"]
           #object[DateTime \"2016-09-30\"]
           #object[DateTime \"2016-10-31\"]
           #object[DateTime \"2016-11-30\"]
           #object[DateTime \"2016-12-31\"]]
  "
  [{months ::months year ::year}]
  (mapv
   (fn [m]
     (time/last-day-of-the-month-
      (time.format/parse year-month-formatter (str year " " m))))
   months))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conformed Record Utility fns

(defn has-planned-orders?
  "Returns `true` if the part contains at least one planned order entry
  with a positive quantity."
  [part]
  (some #(and % (pos? %)) (:part/planned-orders part)))

(defn map-qtys-to-months
  "Maps the given sequence of quantities to the months in `forecast-years`
  (a sequence of conformed month headings from the sheet from which the
  quantities were read.)

  Totals are discarded as are those entries for months without
  a forecasted quantity.

      (map-qtys-to-months
       [{:months [\"Jul\" \"Aug\" \"Sep\" \"Oct\" \"Nov\" \"Dec\"]
         :year \"2016 Total\"}
        {:months [\"Jan\" \"Feb\" \"Mar\" \"Apr\" \"May\" \"Jun\"]
         :year \"2017 Total\"}]
       [nil nil 1 2 nil nil 3 nil 4 2 nil 5 nil 11])
       ;=> {#object[DateTime \"2016-09-30\"] 1
            #object[DateTime \"2016-10-31\"] 2
            #object[DateTime \"2017-02-28\"] 4
            #object[DateTime \"2017-03-31\"] 2
            #object[DateTime \"2017-05-31\"] 5}}
  "
  [forecast-years qtys]
  (let [months (->> forecast-years
                    (map (comp #(conj % 'T) forecast-months))
                    flatten
                    vec)]
    (some->> (interleave months qtys)
             (partition 2)
             (remove (fn [[k v]] (or (nil? v) (zero? v) (= k 'T))))
             (mapv vec)
             (into {}))))

(defn assoc-order-summary-to-part
  "`assoc`s the quantities from the given order summary row into the
  given part record under the key corresponding to the summary type (see
  `summary-row-types`.)"
  [part order-summary]
  (assoc part
         (summary-row-types (:order-summary/id order-summary))
         (:order-summary/qtys order-summary)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Spreadsheet data wrangling

(defn parse-row
  "Returns either a part or summary row record depending on the content
  of the given row or `:clojure.spec/invalid` if the row conforms to
  neither type.

  `row` can be either the raw spreadsheet `Row` as returned from Apache
  POI or a sequence of `Cell`s or already extracted (Clojure) values."
  [row]
  (let [vs (if (instance? Row row)
             (row->values row)
             (map
              (fn [v] (if (instance? Cell v) (cell-value v) v))
              (seq row)))
        s (if (summary-row-labels (first vs))
            ::summary-row
            ::part-row)]
    (if s
      (spec/conform s vs)
      ::spec/invalid)))

(defn rows->parts
  "Transducer that reduces sequences of rows from a forecast sheet into
  part records.

  Values are returned from the transducer when the _next_ part is
  encountered (or the input ends.) Depending on the rows encountered
  each part may have
  entries for:

  * `:part/planned-orders`
  * `:part/shipped-orders`

  Each of which, if present, is a collection of the quantities from the
  row of that type adjacent to the part."
  [xf]
  (let [part (volatile! nil)]
    (fn
      ([]   (xf))
      ([rs] (xf (if-let [v @part] (xf rs v) rs)))
      ([rs row]
       (let [parsed-row (parse-row row)
             valid? (and parsed-row (not= parsed-row ::spec/invalid))
             part-row? (and valid? (contains? parsed-row :part/number))]
         (case [valid? part-row?]
           [true true]   (let [prior @part
                               rs (if prior (xf rs prior) rs)]
                           (vreset! part parsed-row)
                           rs)
           [true false]  (do
                           (vswap! part assoc-order-summary-to-part parsed-row)
                           rs)
           [false false] rs))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(def ^:dynamic *forecast-sample-size*
  "The number of rows to sample when checking a sheet for a forecast"
  10)

(defn forecast-sheet?
  "Retruns `true` if the given spreadsheet appears to contain a customer
  forecast (determined by sampling a small number of rows from the start
  of the
  sheet.)"
  [^Sheet sheet]
  (let [rows (map row->values
                  (take *forecast-sample-size* (spreadsheet/row-seq sheet)))
        has-header? (spec/valid? ::header-row (first rows))]
    (when has-header?
      (every? (fn [r]
                (let [x (parse-row r)]
                  (and x (not= x ::spec/invalid))))
              (rest rows)))))

(spec/fdef forecast-sheet?
  :args (spec/cat :sheet (partial instance? Sheet))
  :ret (spec/nilable boolean?))

(defn sheet->forecast
  "Returns a collection of the parts from the given forecast sheet that
  have planned orders (i.e. actual forecast entries) with their
  `:forecast/qtys` set to a map of forecast dates to planned order
  quantities.

  Each part will **only** contain forecast entries for dates on which
  they have forecasted orders and **only** those parts with _any_
  forecast are returned."
  [^Sheet sheet]
  (let [rows (map row->values (spreadsheet/row-seq sheet))
        headers (spec/conform ::header-row (first rows))]
    (when (= headers ::spec/invalid)
      (throw
       (ex-info "unable to parse headers from first row"
                {:row (first rows)
                 ::spec/explain (spec/explain-data ::header-row (first rows))})))
    {alc-ifs-project-id
     (reduce
      (fn [rs part]
        (if (has-planned-orders? part)
          (let [fc (map-qtys-to-months
                    (::forecast-years headers)
                    (:part/planned-orders part))
                p (select-keys part [:part/number :part/description])]
            (assoc rs p fc))
          rs))
      {}
      (sequence rows->parts (rest rows)))}))

(spec/fdef sheet->forecast
  :args (spec/cat :sheet (partial instance? Sheet))
  :ret :cefu/forecast-map)
