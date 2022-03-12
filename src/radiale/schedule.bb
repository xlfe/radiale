(ns radiale.schedule
  (:require 
    [taoensso.timbre :as timbre]
    [babashka.pods :as pods]
    [clojure.tools.logging :as log]
    [babashka.deps :as deps]))

(deps/add-deps '{:deps {net.xlfe/at-at {:mvn/version "1.3.1"}}})

(require '[overtone.at-at :as aa])

(def s-pool (aa/mk-pool))


(defn run-schedule
  [rmc rms rsms {:keys [after every crontab solar] :as schedule} f]
  (let [[schedule-again? afn sfn] 
        (cond 
          after 
          [false aa/after (fn [cb] 
                            (cb (* after 1000)))]

          every 
          [false aa/every (fn [cb] 
                            (cb (* every 1000)))]

          crontab 
          [true aa/after #(rmc crontab %)]

          solar 
          [true aa/after #(rms solar %)])]
                  

    (sfn 
      (fn [{:keys [ms] :as n}]
        (timbre/debug schedule (or ms n))
        (afn 
          (or ms n)
          (fn []
            (f)
            ()
            (when schedule-again?
              (rsms 1000)
              (run-schedule rmc rms rsms schedule f)))
          s-pool
          :desc 
          (prn-str schedule))))))

(defn crontab
  [k radiale params])

(defn solar
  [k radiale params])

(defn after
  [k radiale params])

(defn every
  [k radiale params])



(comment
  (schedule/run-schedule {:after 5} #(println "hello just once, after 5 seconds"))
  (schedule/run-schedule {:every 10} #(println "hello every 10 seconds"))
  ; note, crontabs that don't specify a specific time might not start right away.......
  (schedule/run-schedule {:crontab {:day_of_week 6 :hour 14 :minute 8 :tz "Australia/Sydney"}} #(println "hello every minute"))
  (schedule/run-schedule {:solar {:event "sunrise" :lat -33.8688 :lon 151.2093 :tz "Australia/Sydney"}} #(println "hello at sunrise!")))









