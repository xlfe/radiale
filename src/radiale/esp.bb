(ns radiale.esp
  (:require
    [clojure.core.async :as async]
    [clojure.string]
    [taoensso.timbre :as timbre]))

(defonce esp-registry* (atom {}))


(defn keywordize-esp-services
  [service-name services]
  (reduce-kv 
    (fn [m k v]
      (let [service-ident (keyword service-name (:object_id v))]
        (assert (nil? (get m k)))
        (assert (nil? (get m service-ident)))
        (timbre/info "Discovered ESP-Home service:" service-ident)
        (merge m {k service-ident
                  service-ident v})))
    {}
    services))


(defn esp-logger
  [bus m {:keys [service-name services state connected]}]
  (if services
    (swap! esp-registry* merge (keywordize-esp-services service-name services))
    (let [er      @esp-registry*]
      (if (some? connected)

        (doseq [service-ident (filter #(= service-name (namespace %)) (keys er))]
          (async/>!! bus (merge m {::connected connected 
                                   ::service (get er service-ident)
                                   ::ident service-ident})))
             
        (let [
              [k state]     state
              service-ident (get er (keyword k))
              service       (get er service-ident)]

          (async/>!! bus (merge m {::state state 
                                   ::service service
                                   ::ident service-ident}))))))) 


(defn discover
  [{:keys [listen-mdns mdns-info subscribe-esp]} bus m]
  (listen-mdns 
   {:service-type "_esphomelib._tcp.local."} 
   (fn [{:keys [state-change service-name] :as mdns-state}]
     (let [service-name (first (clojure.string/split service-name #"\."))]
        (when (or 
                (= state-change "updated")
                (= state-change "added"))
          (subscribe-esp {:service-name service-name}
            (partial esp-logger bus m)))))))



(defn esp-base-data
  [ident]
  {:service-name (namespace ident) 
   :key (get-in @esp-registry* [ident :key])})
   
(defn switch
  [{:keys [switch-esp]} bus {:keys [::ident ::state]}]
  (switch-esp (merge 
                (esp-base-data ident)
                {:state state})
    (fn [r]
      (prn r))))
      

  
(defn light
  [{:keys [light-esp]} bus {:keys [::ident ::params]}]

  ; params:
    ; state: Optional[bool] = None,
    ; brightness: Optional[float] = None,
    ; color_mode: Optional[int] = None,
    ; color_brightness: Optional[float] = None,
    ; rgb: Optional[Tuple[float, float, float]] = None,
    ; white: Optional[float] = None,
    ; color_temperature: Optional[float] = None,
    ; cold_white: Optional[float] = None,
    ; warm_white: Optional[float] = None,
    ; transition_length: Optional[float] = None,
    ; flash_length: Optional[float] = None,
    ; effect: Optional[str] = None,])

  (light-esp (merge 
                 (esp-base-data ident)
                 {:params params})
      (fn [r]
        (timbre/debug ident params r))))

