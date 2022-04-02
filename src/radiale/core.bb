(ns radiale.core
  (:require 

    [radiale.watch :as watch]
    [clojure.core.async :as async]
    [taoensso.timbre :as timbre]
    [clojure.test :refer [function?]]
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
  (let [clean-m (dissoc m ::fn ::then)]
    (if-let [fn- (::fn m)]

      ; if there is a ::fn specified, call it now after removing it from the map
      (fn- radiale-map send-chan (dissoc m ::fn))

      ; if not, check for a ::then
      ; If so, merge the map and try again for a fn
      (when-let [then (::then m)]
        (cond
          (fn? then)
          (try-fn send-chan (then clean-m))

          (map? then)
          (try-fn send-chan (merge clean-m then))
        
          (sequential? then)
          (doseq [t then]
            (try-fn send-chan (merge clean-m t)))
    
          :else (timbre/error m))))))

(defn run
  [config]
  {:pre [(sequential? config)]}
  (let [send-chan (a/chan 64)]

    (doseq [m config]
      (try-fn send-chan m))

    (while true
      (let [msg (async/<!! send-chan)]

        (doseq [[k v] msg]
          (timbre/debug k v))

        (timbre/debug "\n\n")

        (cond
          (map? msg)
          (try-fn send-chan msg)
          
          ; (sequential? msg)
          ; (doseq [m msg]
            ; (try-fn send-chan m))

          :else (timbre/error msg))))))
                              





