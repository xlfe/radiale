(ns radiale.deconz
  (:require 
    [clojure.edn :as edn]
    [clojure.string]))



(defonce deconz-config* (atom nil))
(defn log
  [result]
  (if (nil? @deconz-config*)
    (reset! deconz-config* (select-keys result [:lights :sensors]))
    (prn result)))


(comment
  (do
    (python/put-deconz {:type "lights" :id "8" :state {:on false}} log)
    (python/sleep-ms 1000)
    (python/put-deconz {:type "lights" :id "8" :state {:on true :bri 0 :transitiontime 0}} log)
    (python/sleep-ms 1000)
    (python/put-deconz {:type "lights" :id "8" :state {:bri 255 :transitiontime 600}} log)
    (python/sleep-ms 1000)))


  
