(ns cefu.utc)

(defonce local-tz (java.util.TimeZone/getDefault))

(defn switch-to-utc! []
  (java.util.TimeZone/setDefault
   (java.util.TimeZone/getTimeZone "UTC")))

(defn reset-to-local! []
  (java.util.TimeZone/setDefault local-tz))
