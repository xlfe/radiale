(ns radiale.core
  (:require 

    [radiale.watch :as watch]
    [clojure.core.async :as async]
    [taoensso.timbre :as timbre]
    [clojure.test :refer [function?]]
    [clojure.edn :as edn]
    [clojure.core.async :as a]
    [babashka.pods :as pods]))
    ; [radiale.schedule :as schedule]
    ; [radiale.deconz]))

; set log level
; (alter-var-root #'timbre/*config* #(assoc %1 :min-level :info))

(pods/load-pod ["./pod-xlfe-radiale.py"])
(require '[pod.xlfe.radiale :as radiale])

(def radiale-map
  {:listen-mdns     radiale/listen-mdns
   :listen-mqtt     radiale/listen-mqtt
   :listen-deconz   radiale/listen-deconz
   :millis-solar    radiale/millis-solar
   :millis-crontab  radiale/millis-crontab
   :put-deconz      radiale/put-deconz
   :mdns-info       radiale/mdns-info
   :subscribe-esp   radiale/subscribe-esp
   :sleep-ms        radiale/sleep-ms
   :switch-esp      radiale/switch-esp
   :light-esp       radiale/light-esp})


(defn try-fn
  [send-chan m]
  (watch/match-message send-chan m)
  (if-let [fn- (::fn m)]

    ; if there is a ::fn specified, call it now after removing it from the map
    (fn- radiale-map send-chan (dissoc m ::fn))

    ; if not, check for a ::then
    ; If so, merge the map and try again for a fn
    (when-let [then (::then m)]
      (cond
        (fn? then)
        (try-fn send-chan (then m))

        (map? then)
        (try-fn send-chan (merge m then))
        
        (sequential? then)
        (doseq [t then]
          (try-fn send-chan (merge m t)))
    
        :else (timbre/error m)))))

(defn run
  [config]
  {:pre [(sequential? config)]}
  (let [send-chan (a/chan 64)]

    (doseq [m config]
      (try-fn send-chan m))

    (while true
      (let [msg (async/<!! send-chan)]

        ; (timbre/debug msg)
        (cond
          (map? msg)
          (try-fn send-chan msg)
          
          (sequential? msg)
          (doseq [m msg]
            (try-fn send-chan m))

          :else (timbre/error msg))))))
                              
(defn slurp-edn
  [])


; (let [{:keys[host deconz-key mqtt-pass]} (-> ".secrets" slurp edn/read-string)])
  ; (radiale/listen-mdns {:service-type "_googlecast._tcp.local."} mdns-get-info))
  ; (radiale/listen-mqtt {:host host :username "homeassistant" :password mqtt-pass} log))
  ; (radiale/listen-deconz {:host host :api-key deconz-key} log))




