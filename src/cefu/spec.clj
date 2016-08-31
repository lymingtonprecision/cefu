(ns cefu.spec
  (:require [clojure.spec :as spec]))

(spec/def :ifs/sub-project-id (spec/and string? #(re-matches #"^S\d{3}$" %)))

(def part-id-regex #"^(100\d{6})R(0[1-9]|[1-9]\d)")

(spec/def :part/contract string?)
(spec/def :part/id (spec/and string? #(re-matches part-id-regex %)))
(spec/def :part/number string?)
(spec/def :part/description (spec/nilable string?))

(spec/def
 :cefu/part
 (spec/keys :req [:part/number]
            :opt [:part/contract :part/id :part/description]))

(spec/def :cefu/forecast (spec/map-of inst? (spec/and integer? pos?)))

(spec/def
 :cefu/forecast-map
 (spec/map-of :ifs/sub-project-id (spec/map-of ::part ::forecast)))
