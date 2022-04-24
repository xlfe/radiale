(ns radiale.core
  (:require 

    [radiale.watch :as watch]
    [radiale.esp :as esp]
    [radiale.state :as state]
    [clojure.core.async :as async]
    [taoensso.timbre :as timbre]
    [clojure.test :refer [function?]]
    [clojure.core.async :as a]
    [babashka.pods :as pods]
    [babashka.deps :as deps]))
    ; [radiale.schedule :as schedule]
    ; [radiale.deconz]))

; set log level
; (alter-var-root #'timbre/*config* #(assoc %1 :min-level :info))

(deps/add-deps '{:deps {djblue/portal {:mvn/version "0.23.0"}}})

(pods/load-pod ["./pod-xlfe-radiale.py"])
(require '[pod.xlfe.radiale :as radiale])
(require '[portal.api :as p])

(defonce state* (atom {}))
; (def portal (p/open {:port 8821}))
; (reset! portal state*)
; (add-tap #'p/submit) ; Add portal as a tap> target




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
   :light-esp       radiale/light-esp
   :service-esp     radiale/service-esp
   :state-esp       radiale/state-esp})


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

(defn update-or-add
  [ident state]
  (if-let [a* (ident @state*)]
    (reset! a* state)
    (swap! state* assoc ident (atom state))))


(defn run
  [config]
  {:pre [(sequential? config)]}
  (let [send-chan (a/chan 64)]

    (doseq [m config]
      (try-fn send-chan m))

    (prn @state/state*)
    (while true
      (let [msg (async/<!! send-chan)]

        (let [{:keys [::esp/state ::esp/ident]} msg]
          (when ident
            (update-or-add ident state)
            (timbre/debug ident state)))

        (cond
          (map? msg)
          (try-fn send-chan msg)

          (sequential? msg)
          (doseq [m msg]
            (try-fn send-chan m))

          :else (timbre/error msg))))))
                              





