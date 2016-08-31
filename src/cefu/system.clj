(ns cefu.system
  (:require [com.stuartsierra.component :as component]
            [cefu.components.ifs-connection :refer [make-connection]]))

(defn make-system []
  (component/system-map
    :database (make-connection)))

(defn configure [system config]
  (merge-with merge system config))

(def start component/start)
(def stop component/stop)
