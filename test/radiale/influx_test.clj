(ns radiale.influx-test
  (:require
    [clojure.test :refer :all]
    [radiale.influx :as influx]
    [radiale.state :as state]))

(defn- event->lines [opts event] (map #(.toLineProtocol %) (or (#'influx/event->points opts event) [])))

(defn- line-contains? [line patterns] (every? #(re-find % line) patterns))

(deftest esphome-event-to-point-test
  (let [opts  {::influx/domains [:radiale.esp]}
        event {::state/domain :radiale.esp
               ::state/ident  :outside-garden
               ::state/prop   :temp
               ::state/now    {:state 22.5}}
        lines (event->lines opts event)]
    (is
      (= 1 (count lines)))
    (is
      (line-contains? (first lines) [#"^temp," #"device=outside-garden" #"domain=radiale\.esp" #"value=22\.5"]))))

(deftest deconz-event-to-point-test
  (let [opts  {::influx/domains [:radiale.deconz]}
        event {::state/domain :radiale.deconz
               ::state/ident  :light/bed1-left
               ::state/now    {:bri       254
                               :on        true
                               :reachable false}}
        lines (event->lines opts event)]
    (is
      (= 1 (count lines)))
    (is
      (line-contains?
        (first lines)
        [#"^radiale\.deconz," #"device=light/bed1-left" #"domain=radiale\.deconz" #"bri=254i" #"on=true"
         #"reachable=false"]))))

(deftest whitelist-filter-test
  (let [opts  {::influx/domains     [:radiale.deconz]
               ::influx/allow-props [:bri]}
        event {::state/domain :radiale.deconz
               ::state/ident  :light/bed1-left
               ::state/now    {:bri       254
                               :on        true
                               :reachable false}}
        lines (event->lines opts event)
        line  (first lines)]
    (is
      (= 1 (count lines)))
    (is
      (line-contains? line [#"^radiale\.deconz," #"bri=254i"]))
    (is
      (nil? (re-find #"on=true" line)))
    (is
      (nil? (re-find #"reachable=false" line)))))

(deftest domain-filter-test
  (let [opts  {::influx/domains [:radiale.esp]}
        event {::state/domain :radiale.deconz
               ::state/ident  :light/bed1-left
               ::state/now    {:bri 254}}
        lines (event->lines opts event)]
    (is
      (empty? lines))))
