(ns radiale.deconz
  (:require 
    [taoensso.timbre :as timbre]
    [clojure.core.async :as async]
    [clojure.string]))

(defn store-deconz-config
  [service-type-namespaces state* result]
  (doseq [[t s] result]
    (when-let [t (get service-type-namespaces t)]
      (doseq [[id {:keys [state] :as props}] s]
        (let [ident (keyword (name t) (:name props))
              uid   (:uniqueid props)]
          (timbre/debug "discovered service" ident)
          (timbre/debug props)
          (swap! state* assoc-in [ident :props] (merge 
                                                  (dissoc props :state)
                                                  {:service (name t)
                                                   :id id}))
          (swap! state* assoc-in [ident :state] state)
          (swap! state* assoc-in [uid] ident))))))

(defn state-change
  [service-type-namespaces {:as e :keys [uniqueid r state attr]} bus state* m]
  (when-let [device (some->> r keyword (get service-type-namespaces))]
    (when-let [ident (get-in @state* [uniqueid])]
      (if state
        (do
          (swap! state* assoc-in [ident :state] state)
          (async/>!! bus {::ident ident ::state state})) 
        (swap! state* update-in [ident :props] merge attr)))))


(defn discover
  [{:keys [listen-deconz]} bus state* {:keys [::api-key ::host ::service-type-namespaces] :as m}]
  (listen-deconz 
    {:api-key api-key :host host} 
    (fn [result]
      (if-let [rc (:radialeconfig result)]
        (store-deconz-config service-type-namespaces state* rc)
        (state-change service-type-namespaces result bus state* (dissoc m ::api-key ::host))))))


(defn get-config
  [state* ident]
  (let [{:keys [:id :service]}  (get-in @state* [ident :props])]
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


  
