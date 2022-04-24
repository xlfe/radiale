(ns radiale.esp
  (:require
    [clojure.core.async :as async]
    [clojure.string]
    [taoensso.timbre :as timbre]))

(defonce esp-registry* (atom {}))
(def ESP-MDNS "_esphomelib._tcp.local.")


(defn keywordize-esp-services
  [service-name services]
  (reduce-kv 
    (fn [m k v]
      (timbre/debug k v)
      (let [service-ident (keyword service-name (or 
                                                  (:object_id v)
                                                  (:name v)))] ;user-defined service
        (assert (nil? (get m k)))
        (assert (nil? (get m service-ident)))
        (timbre/info "Discovered ESP-Home service:" service-ident)
        (merge m {k service-ident
                  service-ident v})))
    {}
    services))


(defn esp-logger
  [bus m {:keys [service-name services state connected ha-state-subscribe]}]
  (if services
    (swap! esp-registry* merge (keywordize-esp-services service-name services))
    (let [er      @esp-registry*]
      (cond 

        (some? connected)
        (doseq [service-ident (filter #(= service-name (namespace %)) (keys er))]
          (async/>!! bus (merge m {::connected connected 
                                   ::service (get er service-ident)
                                   ::ident service-ident})))
             
        (some? ha-state-subscribe)
        (timbre/debug "SUBSCRIBE" service-name ha-state-subscribe)

        :else
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
   {:service-type ESP-MDNS} 
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
  [{:keys [switch-esp]} bus {:keys [::ident ::state] :as m}]
  (switch-esp (merge 
                (esp-base-data ident)
                {:state state})
    (fn [r]
      (prn r)))
  (async/>!! bus m)) 
      

  
(defn service
  [{:keys [service-esp]} bus {:keys [::ident ::params] :as m}]

  (service-esp (merge 
                   (esp-base-data ident)
                   {:params params})
      (fn [r]
        (timbre/debug ident params r)))
  (async/>!! bus m)) 

  
(defn light
  [{:keys [light-esp]} bus {:keys [::ident ::params] :as m}]

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
        (timbre/debug ident params r)))
  (async/>!! bus m)) 

  
(defn state
  [{:keys [state-esp]} bus {:keys [::ident ::entity-id ::attribute ::state] :as m}]

  (state-esp {:service-name ident
              :entity_id entity-id
              :attribute attribute
              :state state}
      (fn [r]
        (timbre/debug ident entity-id attribute state r)))
  (async/>!! bus m)) 

