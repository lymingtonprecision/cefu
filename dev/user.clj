(ns user
  (:require [clojure.pprint :refer [pprint]]
            [com.stuartsierra.component :as component]
            [reloaded.repl
             :refer [system init start stop clear go reset reset-all]]
            [taoensso.timbre :as timbre
             :refer [trace debug info warn error fatal report]]

            [dk.ative.docjure.spreadsheet :as spreadsheet]

            [cefu.utc]
            [cefu.config :as config]
            [cefu.system :as system]))

(defn dev-system []
  (let [c (config/from [(config/default-path)
                        (clojure.java.io/file ".secrets.edn")]
                       :default)
        s (system/make-system)]
    (system/configure s c)))

(timbre/set-level! :debug)
(cefu.utc/switch-to-utc!)
(reloaded.repl/set-init! #'user/dev-system)

(comment
 (reset)
 (reset-all)
 (clear)
 (init)
 (stop)
 (start)
 (pprint system))
