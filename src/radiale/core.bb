(ns radiale.core
  (:require 

    [taoensso.timbre :as timbre]
    [clojure.edn :as edn]
    [clojure.core.async :as a]
    [babashka.pods :as pods]))
    ; [radiale.schedule :as schedule]
    ; [radiale.deconz]))

; set log level
; (alter-var-root #'timbre/*config* #(assoc %1 :min-level :info))

(pods/load-pod ["./pod-xlfe-radiale.py"])
(require '[pod.xlfe.radiale :as radiale])

(defn get-dispatch-fn
  [k]
  (when (qualified-keyword? k)
    (requiring-resolve (symbol (namespace k) (name k)))))
      

(defn run
  [config]
  (let [bus (a/chan)]
    (doseq [[k v] config]
      (doseq [[k v] v]
        (prn  k) 
        (prn (get-dispatch-fn k)))))
  
  ; k radiale params

  (while true
    (radiale/sleep-ms 500)))

(def rm
  {:listen-mdns     radiale/listen-mdns
   :listen-mqtt     radiale/listen-mqtt
   :listen-deconz   radiale/listen-deconz
   :millis-solar    radiale/millis-solar
   :millis-crontab  radiale/millis-crontab
   :put-deconz      radiale/put-deconz
   :mdns-info       radiale/mdns-info
   :subscribe-esp   radiale/subscribe-esp
   :sleep-ms        radiale/sleep-ms
   :switch-esp      radiale/switch-esp})



; (let [{:keys[host deconz-key mqtt-pass]} (-> ".secrets" slurp edn/read-string)])
  ; (radiale/listen-mdns {:service-type "_googlecast._tcp.local."} mdns-get-info))
  ; (radiale/listen-mdns {:service-type "_esphomelib._tcp.local."} mdns-get-info))
  ; (radiale/listen-mqtt {:host host :username "homeassistant" :password mqtt-pass} log))
  ; (radiale/listen-deconz {:host host :api-key deconz-key} log))




