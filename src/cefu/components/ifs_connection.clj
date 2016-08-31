(ns cefu.components.ifs-connection
  (:require [com.stuartsierra.component :as component]
            [clojure.java.jdbc :as jdbc]))

(defrecord IfsConnection [host sid user password]
  component/Lifecycle
  (start [this]
    (if (:connection this)
      this
      (assoc this
             :connection (jdbc/get-connection {:dbtype "oracle:thin"
                                               :dbname sid
                                               :database (str host "/" sid)
                                               :user user
                                               :password password
                                               :autoCommit false}))))
  (stop [this]
    (when-let [c (:connection this)] (.close c))
    (dissoc this :connection))

  java.io.Closeable
  (close [this]
    (component/stop this)))

(defn make-connection
  ([]
   (map->IfsConnection {}))
  ([host sid user password]
   (->IfsConnection host sid user password)))
