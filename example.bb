(require '[babashka.pods :as pods])
(pods/load-pod ["./pod-xlfe-radiale.py"])
(require '[pod.xlfe.radiale :as radiale])

(defonce esp-registry* (atom {}))

(defn esp-logger
  [{:keys [service-name services state]}]
  (if services
    (swap! esp-registry* assoc service-name services)
    (let [services (get @esp-registry* service-name)
          [k state] state
          service (get services (keyword k))
          details (assoc service :state state)]
      (prn service-name (select-keys details [:object_id :state])))))

(defn mdns-info
  [{:keys [service-type] :as mdns-info}]
  (when (= service-type "_esphomelib._tcp.local.")
    (radiale/subscribe-esp mdns-info esp-logger)))


(defn mdns-get-info
  [{:keys [state-change] :as mdns-state}]
  (prn mdns-state)
  (when (= state-change "added")
    (radiale/mdns-info mdns-state mdns-info)))


(defn log
  [result]
  (prn result))

(let [{:keys[host deconz-key mqtt-pass]} (-> ".secrets" slurp edn/read-string)]
  (radiale/listen-mdns {:service-type "_googlecast._tcp.local."} mdns-get-info)
  (radiale/listen-mdns {:service-type "_esphomelib._tcp.local."} mdns-get-info)
  (radiale/listen-mqtt {:host host :username "homeassistant" :password mqtt-pass} log)
  (radiale/listen-deconz {:host host :api-key deconz-key} log))

(while true
  (radiale/sleep-ms 1000))
