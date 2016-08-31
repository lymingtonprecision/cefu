(defproject lymingtonprecision/cefu "0.1.0-SNAPSHOT"
  :description "LPE Customer Excel Forecast Uploader"
  :url "https://github.com/lymingtonprecision/cefu"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.9.0-alpha11"]

                 ;; configuration
                 [aero "1.0.0"]

                 ;; general system libs
                 [com.stuartsierra/component "0.3.1"]
                 [clj-time "0.12.0"]
                 [com.rpl/specter "0.12.0"]

                 ;; database
                 [org.clojure/java.jdbc "0.6.1"]
                 [org.clojars.zentrope/ojdbc "11.2.0.3.0"]
                 [yesql "0.5.3"]

                 ;; excel
                 [dk.ative/docjure "1.10.0"]

                 ;;;; logging
                 ;; use logback as the main Java logging implementation
                 [ch.qos.logback/logback-classic "1.1.7"]
                 [ch.qos.logback/logback-core "1.1.7"]
                 ;; with SLF4J as the main redirect
                 [org.slf4j/slf4j-api "1.7.21"]
                 [org.slf4j/jcl-over-slf4j "1.7.21"]
                 [org.slf4j/log4j-over-slf4j "1.7.21"]
                 [org.apache.logging.log4j/log4j-to-slf4j "2.6.2"]
                 ;; and timbre for our own logging
                 [com.taoensso/timbre "4.7.3"]]

  :profiles
  {:dev {:dependencies [[reloaded.repl "0.2.2"]
                        [org.clojure/test.check "0.9.0"]]
         :source-paths ["dev"]}}

  :repl-options {:init-ns user}

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version"
                   "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["change" "version"
                   "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]])
