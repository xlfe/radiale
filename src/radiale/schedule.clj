(ns radiale.schedule
  (:require
    [babashka.pods :as pods]
    [clojure.core.async :as async]
    [clojure.tools.logging :as log]
    [radiale.core :as rc]
    [radiale.scheduler :as sched]
    [taoensso.timbre :as timbre]))

(def s-pool (sched/mk-pool))


(defn run-schedule
  [schedule-again? sched-fn-name sched-fn call-fn state* unique desc]

  (when-let [existing (get-in @state* [:radiale.schedule :unique unique])]
    (sched/kill existing))
  (sched-fn
    (fn [{:keys [ms]
          :as   n}]
      (when desc
        (timbre/info "SCHEDULING" desc "in" (long (/ (or ms n) 1000)) "s"))
      (let [delay-ms      (or ms n)
            sched-fn-impl (case sched-fn-name
                            :after sched/after
                            :every sched/every)
            jobinfo       (sched-fn-impl
                            delay-ms
                            (fn []
                              (swap! state* assoc-in [:radiale.schedule :unique unique] nil)
                              (call-fn)
                              (when schedule-again?
                                (Thread/sleep 100)
                                (run-schedule schedule-again? sched-fn-name sched-fn call-fn state* unique desc)))
                            s-pool)]
        (when unique
          (swap! state* assoc-in [:radiale.schedule :unique unique] jobinfo))))))

(defn crontab
  [{:keys [millis-crontab]} send-chan state*
   {:keys [::params ::at-most-once ::rc/desc]
    :as   m}]
  (run-schedule true :after #(millis-crontab params %) #(async/>!! send-chan m) state* at-most-once desc))

(defn solar
  [{:keys [millis-solar]} send-chan state*
   {:keys [::params ::at-most-once ::rc/desc]
    :as   m}]
  (run-schedule true :after #(millis-solar params %) #(async/>!! send-chan m) state* at-most-once desc))

(defn after
  [_ send-chan state*
   {:keys [::seconds ::at-most-once ::rc/desc]
    :as   m}]
  (run-schedule
    false
    :after
    (fn [cb]
      (cb (* seconds 1000)))
    #(async/>!! send-chan m)
    state*
    at-most-once
    desc))

(defn every
  [_ send-chan state*
   {:keys [::seconds ::at-most-once ::rc/desc]
    :as   m}]
  (run-schedule
    false
    :every
    (fn [cb]
      (cb (* seconds 1000)))
    #(async/>!! send-chan m)
    state*
    at-most-once
    desc))

(defn only-if
  [{:keys [astral-now]} send-chan state*
   {:keys [::location ::criteria ::when-true]
    :as   m}]
  (let [an (keyword "radiale.schedule" (astral-now location))]
    (when (criteria an)
      (async/>!! send-chan when-true))))


(defn cancel
  [_ _ state* {:keys [::at-most-once]}]
  (when-let [job (get-in @state* [:radiale.schedule :unique at-most-once])]
    (sched/kill job)
    (swap! state* assoc-in [:radiale.schedule :unique at-most-once] nil)))






(comment
  (schedule/run-schedule {:after 5} #(println "hello just once, after 5 seconds"))
  (schedule/run-schedule {:every 10} #(println "hello every 10 seconds"))
  ; note, crontabs that don't specify a specific time might not start right away.......
  (schedule/run-schedule
    {:crontab {:day_of_week 6
               :hour        14
               :minute      8
               :tz          "Australia/Sydney"}}
    #(println "hello every minute"))
  (schedule/run-schedule
    {:solar {:event "sunrise"
             :lat   -33.8688
             :lon   151.2093
             :tz    "Australia/Sydney"}}
    #(println "hello at sunrise!")))









