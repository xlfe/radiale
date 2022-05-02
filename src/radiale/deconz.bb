(ns radiale.deconz
  (:require 
    [taoensso.timbre :as timbre]
    [clojure.core.async :as async]
    [clojure.string]))



(def known-service-types {:lights :light
                          :sensors :sensor})



; unlike esp devices, deconz device names are likely to contain spaces, so just use the ID 
; noting that this is not stable

(defn deconz-ident
  [device-type props]
  [
      (keyword (subs device-type 0 (- (count device-type) 1)))
      (keyword (:name props))])

(defn store-deconz-config
  [state* result]
  (doseq [[t s] result]
    (when (get known-service-types t)
      (doseq [[id {:keys [state] :as props}] s]
        (let [[device ident] (deconz-ident (name t) props)
              uid   (:uniqueid props)]
          (timbre/debug "Discovered Deconz service" ident)
          (swap! state* assoc-in [:radiale.deconz device ident :props] (merge 
                                                                         (dissoc props :state)
                                                                         {:service (name t)
                                                                          :id id}))
          (swap! state* assoc-in [:radiale.deconz device ident :state] state)
          (swap! state* assoc-in [:radiale.deconz device uid] ident))))))

(defn state-change
  [{:keys [uniqueid r state attr] :as e} bus state* m]
  (when-let [device (some->> r keyword (get known-service-types) name)]
    (when-let[ident (get-in @state* [:radiale.deconz device uniqueid])]
      (if state
        (swap! state* update-in [:radiale.deconz device ident :state] merge state)
        (swap! state* update-in [:radiale.deconz device ident :props] merge attr)))))


(defn discover
  [{:keys [listen-deconz]} bus state* {:keys [::api-key ::host] :as m}]
  (listen-deconz 
    {:api-key api-key :host host} 
    (fn [result]
      (if (nil? (get-in @state* [:radiale.deconz]))
        (store-deconz-config state* result)
        (state-change result bus state* (dissoc m ::api-key ::host))))))


(defn get-config
  [state* ident]
  (let [device (keyword (namespace ident))
        ident  (keyword (name ident))
        {:keys [:id :service]}  (get-in @state* [:radiale.deconz device ident :props])]
    {:id id :type service}))

(defn put
  [{:keys [put-deconz]} bus state* {:keys [::state ::ident] :as m}]
  (doseq [i (if (sequential? ident) ident [ident])]
    (doseq [s (if (sequential? state) state [state])]
      (put-deconz 
        (merge (get-config state* i) 
               {:state s})
        (fn [r]
          (timbre/debug i s r)))))
              
  (async/>!! bus m)) 

; (put-deconz {:type "lights" :id "8" :state {:on false}} log)
; (put-deconz {:type "lights" :id "8" :state {:on true :bri 0 :transitiontime 0}} log)
; (put-deconz {:type "lights" :id "8" :state {:bri 255 :transitiontime 600}} log)))


  
