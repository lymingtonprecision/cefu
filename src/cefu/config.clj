(ns cefu.config
  (:require [clojure.java.io :as io]
            [aero.core :as aero]))

(defn remove-nil-vals [m]
  (persistent!
    (reduce
      (fn [m [k v]]
        (cond
          (nil? v) (dissoc! m k)
          (map? v) (let [v (remove-nil-vals v)]
                     (if (seq v)
                       (assoc! m k v)
                       (dissoc! m k)))
          :else m))
      (transient m)
      m)))

(defn read-config
  ([path]
   (aero/read-config path))
  ([path profile]
   (aero/read-config path {:profile profile})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(defn default-path []
  (io/resource "config.edn"))

(defn from
  ([sources]
   (from sources :default))
  ([sources profile]
   (->> (if (vector? sources) sources [sources])
        (remove nil?)
        (map #(if (map? %) % (read-config % profile)))
        (map remove-nil-vals)
        (apply merge-with merge))))
