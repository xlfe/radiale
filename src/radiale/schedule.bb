(ns radiale.schedule
  (:require 
    [clojure.core.async :as async]
    [taoensso.timbre :as timbre]
    [babashka.pods :as pods]
    [clojure.tools.logging :as log]
    [babashka.deps :as deps]))

(deps/add-deps '{:deps {net.xlfe/at-at {:mvn/version "1.3.1"}}})

(require '[overtone.at-at :as aa])

(def s-pool (aa/mk-pool))


(defn run-schedule
 [schedule-again? afn sfn f] 
 (sfn 
   (fn [{:keys [ms] :as n}]
     (afn 
       (or ms n)
       (fn []
         (f)
         (when schedule-again?
           (Thread/sleep 100)
           (run-schedule schedule-again? afn sfn f)))
       s-pool))))

(defn crontab
  [{:keys [millis-crontab]} send-chan {:keys [::params] :as m}]
  (run-schedule 
    true 
    aa/after 
    #(millis-crontab params %)
    #(async/>!! send-chan m)))

(defn solar
  [{:keys [millis-solar]} send-chan {:keys [::params] :as m}]
  (run-schedule 
    true 
    aa/after 
    #(millis-solar params %)
    #(async/>!! send-chan m)))

(defn after
  [_ send-chan {:keys [::seconds] :as m}]
  (run-schedule 
    false 
    aa/after 
    (fn [cb] (cb (* seconds 1000)))
    #(async/>!! send-chan m)))

(defn every
  [_ send-chan {:keys [::seconds] :as m}]
  (run-schedule 
    false 
    aa/every 
    (fn [cb] (cb (* seconds 1000)))
    #(async/>!! send-chan m)))



(comment
  (schedule/run-schedule {:after 5} #(println "hello just once, after 5 seconds"))
  (schedule/run-schedule {:every 10} #(println "hello every 10 seconds"))
  ; note, crontabs that don't specify a specific time might not start right away.......
  (schedule/run-schedule {:crontab {:day_of_week 6 :hour 14 :minute 8 :tz "Australia/Sydney"}} #(println "hello every minute"))
  (schedule/run-schedule {:solar {:event "sunrise" :lat -33.8688 :lon 151.2093 :tz "Australia/Sydney"}} #(println "hello at sunrise!")))









