(ns cefu.dates
  (:require [clj-time.core :as time]
            [clj-time.coerce :as time.coerce]
            [clj-time.predicates :refer [weekend? saturday? sunday?]]
            [yesql.core :refer [defquery]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility fns

(defn map-exceptions [rows]
  (doall
   (reduce
    (fn [rs r]
      (assoc rs
             (time.coerce/to-date-time (:exception r))
             (time.coerce/to-date-time (:substitute r))))
    {}
    rows)))

(defn push-pull-to-closest-weekday-in-month
  "If the given date falls on a weekend, returns the closest weekday to
  that date that falls within the same month by either “pushing” it
  forward to the following Monday (preferred) or “pulling” it back to
  the preceding Friday.

  Returns the same date if it is already a weekday."
  [d]
  (let [dow (time/day-of-week d)
        push-to-next-weekday (case dow
                               7 1 ;; sunday
                               6 2 ;; saturday
                               0)
        pull-to-next-weekday (case dow
                               7 2 ;; sunday
                               6 1 ;; saturday
                               0)
        days-till-month-end (- (time/number-of-days-in-the-month d) (time/day d))]
    (if (< days-till-month-end push-to-next-weekday)
      (time/minus d (time/days pull-to-next-weekday))
      (time/plus  d (time/days push-to-next-weekday)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query

(defquery non-working-day-substitues "cefu/queries/non_working_day_substitutes.sql")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(defn substitute-table
  "Returns a map of `exception-date`s to substitute dates that should be
  used in their stead.

  Exceptions encompass public holidays, business closures, etc."
  [db-spec]
  (non-working-day-substitues
   {}
   {:connection db-spec
    :result-set-fn map-exceptions}))

(defn closest-week-day-in-month
  "We don’t delivery on weekends so any forecast that falls on a weekend
  should be pushed forward/pulled back to the closest week day in the
  same month.

  The dates should be kept to the same month so as to maintain the sales
  distribution when viewed at that level, as is often the case with
  forecasts."
  [d]
  (if (weekend? d)
    (push-pull-to-closest-weekday-in-month d)
    d))
