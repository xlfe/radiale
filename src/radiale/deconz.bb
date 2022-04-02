(ns radiale.deconz
  (:require 
    [taoensso.timbre :as timbre]
    [clojure.core.async :as async]
    [clojure.string]))



(defonce deconz-config* (atom nil))
(def known-service-types #{:lights :sensors :groups :scenes})

; unlike esp devices, deconz device names are likely to contain spaces, so just use the ID 
; noting that this is not stable

(defn store-deconz-config
  [result]
  (doseq [[t s] result]
    (when (t known-service-types)
      (doseq [[k v] s]
        (let [ident (keyword (str (name t) "-" (name k)))] 
          (swap! deconz-config* assoc ident (merge {::service t ::id (name k)} v))
          (timbre/debug "Discovered Deconz service" 
                        ident
                        "name:" (:name v)))))))

(defn state-change
  [{:keys [r state id] :as e} bus m]
  (let [ident (keyword (str r "-" id))
        config  (get @deconz-config* ident)]

    (if state
      (if (nil? config)
        (timbre/error "event received for UNKOWN DEVICE" e)
        (async/>!! bus (merge m 
                              {::state state
                               ::service config
                               ::ident ident})))
      (swap! deconz-config* update ident merge (:attr e)))))


(defn discover
  [{:keys [listen-deconz]} bus {:keys [::api-key ::host] :as m}]
  (listen-deconz 
    {:api-key api-key :host host} 
    (fn [result]
      (if (nil? @deconz-config*)
        (store-deconz-config result)
        (state-change result bus (dissoc m ::api-key ::host))))))


(defn get-config
  [ident]
  (let [{:keys [::id ::service]}  (get @deconz-config* ident)]
    {:id id :type service}))

(defn put
  [{:keys [put-deconz]} bus {:keys [::state ::ident] :as m}]
  (doseq [i (if (sequential? ident) ident [ident])]
    (doseq [s (if (sequential? state) state [state])]
      (put-deconz 
        (merge (get-config i) 
               {:state s})
        (fn [r]
          (timbre/debug i s r)))))
              
  (async/>!! bus m)) 

; (put-deconz {:type "lights" :id "8" :state {:on false}} log)
; (put-deconz {:type "lights" :id "8" :state {:on true :bri 0 :transitiontime 0}} log)
; (put-deconz {:type "lights" :id "8" :state {:bri 255 :transitiontime 600}} log)))


  
