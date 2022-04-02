(ns radiale.schedule
  (:require 
    [clojure.core.async :as async]
    [taoensso.timbre :as timbre]
    [babashka.pods :as pods]
    [radiale.core :as rc]
    [clojure.tools.logging :as log]
    [babashka.deps :as deps]))

(deps/add-deps '{:deps {net.xlfe/at-at {:mvn/version "1.3.1"}}})

(require '[overtone.at-at :as aa])

(def s-pool (aa/mk-pool))


(defn run-schedule
 [schedule-again? afn sfn f desc] 
 (sfn 
   (fn [{:keys [ms] :as n}]
     (when desc
       (timbre/info "SCHEDULING" desc "in" (long (/ (or ms n) 1000)) "s"))
     (afn 
       (or ms n)
       (fn []
         (f)
         (when schedule-again?
           (Thread/sleep 100)
           (run-schedule schedule-again? afn sfn f desc)))
       s-pool))))

(defn crontab
  [{:keys [millis-crontab]} send-chan {:keys [::params ::rc/desc] :as m}]
  (run-schedule 
    true 
    aa/after 
    #(millis-crontab params %)
    #(async/>!! send-chan m)
    desc))

(defn solar
  [{:keys [millis-solar]} send-chan {:keys [::params ::rc/desc] :as m}]
  (run-schedule 
    true 
    aa/after 
    #(millis-solar params %)
    #(async/>!! send-chan m)
    desc))

(defn after
  [_ send-chan {:keys [::seconds ::rc/desc] :as m}]
  (run-schedule 
    false 
    aa/after 
    (fn [cb] (cb (* seconds 1000)))
    #(async/>!! send-chan m)
    desc))

(defn every
  [_ send-chan {:keys [::seconds ::rc/desc] :as m}]
  (run-schedule 
    false 
    aa/every 
    (fn [cb] (cb (* seconds 1000)))
    #(async/>!! send-chan m)
    desc))



(comment
  (schedule/run-schedule {:after 5} #(println "hello just once, after 5 seconds"))
  (schedule/run-schedule {:every 10} #(println "hello every 10 seconds"))
  ; note, crontabs that don't specify a specific time might not start right away.......
  (schedule/run-schedule {:crontab {:day_of_week 6 :hour 14 :minute 8 :tz "Australia/Sydney"}} #(println "hello every minute"))
  (schedule/run-schedule {:solar {:event "sunrise" :lat -33.8688 :lon 151.2093 :tz "Australia/Sydney"}} #(println "hello at sunrise!")))









