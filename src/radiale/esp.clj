(ns radiale.esp
  (:require
    [clojure.core.async :as async]
    [clojure.string]
    [clojure.set]
    [taoensso.timbre :as timbre]))



(defn keywordize-esp-services
  [device-name services]
  (reduce-kv 
    (fn [m k v]
      (let [service-ident (keyword (or 
                                     (:object_id v) ; esphome service
                                     (:name v)))]   ;user-defined service
        (timbre/info "Discovered ESP-Home service:" device-name service-ident)
        (merge m {(name k) service-ident
                  service-ident {:props v}})))
    {}
    services))


(defn esp-logger
  [bus state* m {:keys [service-name services state connected ha-state-subscribe]}]
  (let [service-name (keyword service-name)]
    (if services
      (swap! state* assoc-in [:radiale.esp service-name] (keywordize-esp-services service-name services))
      (let [er      (:radiale.esp @state*)]
        (cond 

          ; device connection status changed
          (some? connected)
          (swap! state* assoc-in [:radiale.esp service-name :connected] connected)
             
          (some? ha-state-subscribe)
          (swap! state* update-in (into [:radiale.subscription] ha-state-subscribe) 
                 clojure.set/union (set [:radiale.esp service-name]))

          :else
          (let [
                [k state]     state
                service-ident (get-in er [service-name k])]
            (swap! state* assoc-in [:radiale.esp service-name service-ident :state] state)))))))

(defn discover
  [{:keys [listen-mdns mdns-info subscribe-esp]} bus state* m]
  (listen-mdns 
   {:service-type  "_esphomelib._tcp.local."}
   (fn [{:keys [state-change service-name] :as mdns-state}]
      (when (or 
              (= state-change "updated")
              (= state-change "added"))
        (subscribe-esp {:service-name service-name}
          (partial esp-logger bus state* m))))))



(defn esp-base-data
  [state* ident]
  {:service-name (namespace ident) 
   :key (get-in @state* [:radiale.esp (keyword (namespace ident)) (keyword (name ident)) :props :key])})
   
(defn switch
  [{:keys [switch-esp]} bus state* {:keys [::ident ::state] :as m}]
  (switch-esp (merge 
                (esp-base-data state* ident)
                {:state state})
    (fn [r]
      (prn r)))
  (async/>!! bus m)) 
      

  
(defn service
  [{:keys [service-esp]} bus state* {:keys [::ident ::params] :as m}]

  (service-esp (merge 
                   (esp-base-data state* ident)
                   {:params params})
      (fn [r]
        (timbre/debug ident params r)))
  (async/>!! bus m)) 

  
(defn light
  [{:keys [light-esp]} bus state* {:keys [::ident ::params] :as m}]

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
                 (esp-base-data state* ident)
                 {:params params})
      (fn [r]
        (timbre/debug ident params r)))
  (async/>!! bus m)) 

  
(defn state
  [{:keys [state-esp]} bus state* {:keys [::ident ::entity-id ::attribute ::state] :as m}]

  (state-esp {:service-name ident
              :entity_id entity-id
              :attribute attribute
              :state state}
      (fn [r]
        (timbre/debug ident entity-id attribute state r)))
  (async/>!! bus m)) 

