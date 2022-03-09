(ns radiale.example
  (:require 
    [babashka.pods :as pods]
    [babashka.deps :as deps]
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.string]))

(deps/add-deps '{:deps {overtone/at-at {:mvn/version "1.2.0"}}})
(pods/load-pod ["./pod-xlfe-radiale.py"])
(require '[pod.xlfe.radiale :as radiale]
         '[overtone.at-at :as aa])

(def prn pprint/pprint)
(def s-pool (aa/mk-pool))



(defn run-schedule
  [{:keys [after every crontab solar] :as schedule} f]
  (let [[schedule-again? afn sfn] 
        (cond 
          after 
          [false aa/after (fn [cb] 
                            (cb (* after 1000)))]

          every 
          [false aa/every (fn [cb] 
                            (cb (* every 1000)))]

          crontab 
          [true aa/after #(radiale/millis-crontab crontab %)]

          solar 
          [true aa/after #(radiale/millis-solar solar %)])]
                  

    (sfn 
      (fn [{:keys [ms] :as n}]
        (afn 
          (or ms n)
          (fn []
            (f)
            ()
            (when schedule-again?
              (radiale/sleep-ms 1000)
              (run-schedule schedule f)))
          s-pool)))))
        
        



(run-schedule {:after 5} 
              #(println "hello just once, after 5 seconds"))

(run-schedule {:every 10} 
              #(println "hello every 10 seconds"))

(run-schedule {:crontab {:minute "*" :tz "Australia/Sydney"}} 
              #(println "hello every minute"))

(run-schedule {:solar {:event "sunrise" :lat 33.8688 :lon 151.2093 :tz "Australia/Sydney"}} 
              #(println "hello at sunrise!"))




(defonce esp-registry* (atom {}))


(defn keywordize-esp-services
  [service-name services]
  (reduce-kv 
    (fn [m k v]
      (let [service-ident (keyword service-name (:object_id v))]
        (assert (nil? (get m k)))
        (assert (nil? (get m service-ident)))

        (merge m {k service-ident
                  service-ident v})))
    {}
    services))


(defn esp-logger
  [{:keys [service-name services state]}]
  (let [service-name (first (clojure.string/split service-name #"\."))]
    (if services
      (swap! esp-registry* merge (keywordize-esp-services service-name services))
      (let [
            [k state]     state
            er            @esp-registry*
            service-ident (get er (keyword k))
            service       (get er service-ident)
            details       (assoc service :state state)]

        (prn {service-ident details})))))


(defn esp-switch
  [service-ident state]
  (let [data {:service-name (namespace service-ident) 
              :key (get-in @esp-registry* [service-ident :key])
              :state state}]
        
    (prn data)

    (radiale/switch-esp 
      data 
      (fn [response]
        (prn response)))))
                  
          


(defn mdns-info
  [{:keys [service-type] :as mdns-info}]
  ; (prn {:mdns-info mdns-info})
  (when (= service-type "_esphomelib._tcp.local.")
    (radiale/subscribe-esp mdns-info esp-logger)))


(defn mdns-get-info
  [{:keys [state-change] :as mdns-state}]
  ; (prn {:mdns-state mdns-state})
  (when (= state-change "added")
    (radiale/mdns-info mdns-state mdns-info)))


(defonce deconz-config* (atom nil))
(defn log
  [result]
  (if (nil? @deconz-config*)
    (reset! deconz-config* (select-keys result [:lights :sensors]))
    (prn result)))



(let [{:keys[host deconz-key mqtt-pass]} (-> ".secrets" slurp edn/read-string)])
  ; (radiale/listen-mdns {:service-type "_googlecast._tcp.local."} mdns-get-info))
  ; (radiale/listen-mdns {:service-type "_esphomelib._tcp.local."} mdns-get-info))
  ; (radiale/listen-mqtt {:host host :username "homeassistant" :password mqtt-pass} log))
  ; (radiale/listen-deconz {:host host :api-key deconz-key} log))


(comment
  (do
    (radiale/put-deconz {:type "lights" :id "8" :state {:on false}} log)
    (radiale/sleep-ms 1000)
    (radiale/put-deconz {:type "lights" :id "8" :state {:on true :bri 0 :transitiontime 0}} log)
    (radiale/sleep-ms 1000)
    (radiale/put-deconz {:type "lights" :id "8" :state {:bri 255 :transitiontime 600}} log)
    (radiale/sleep-ms 1000)))

(comment
  (do
    (radiale/sleep-ms 1000)
    (radiale/sleep-ms 1000)
    (radiale/sleep-ms 1000)
    (radiale/sleep-ms 1000)
    (radiale/sleep-ms 1000)
    (radiale/sleep-ms 1000)
    (radiale/sleep-ms 1000)
    (prn "go switch!")
    (prn (get @esp-registry* :switch_a/switch_a))
    (esp-switch :switch_a/switch_a true)))


(while true
  (radiale/sleep-ms 1000))
  
