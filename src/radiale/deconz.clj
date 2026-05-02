(ns radiale.deconz
  (:require
    [clojure.core.async :as async]
    [clojure.string]
    [radiale.state :as state]
    [taoensso.timbre :as timbre]))

(defn store-deconz-config
  [service-type-namespaces state* result]
  (doseq [[api-type s] result]
    (when-let [namespace-kw (get service-type-namespaces (keyword api-type))]
      (doseq [[id
               {:keys [state]
                :as   props}]
              s]
        (let [ident (keyword (name namespace-kw) (:name props))
              uid   (:uniqueid props)]
          (timbre/debug "discovered service" ident)
          (timbre/debug props)
          (swap! state* assoc-in
            [:radiale.deconz ident :props]
            (merge
              (dissoc props :state)
              {:service api-type ; Store the API type (e.g., "lights") not the namespace
               :id      id}))
          (swap! state* assoc-in [:radiale.deconz ident :state] state)
          (swap! state* assoc-in [:radiale.deconz :by-uniqueid uid] ident))))))

(defn state-change
  [service-type-namespaces
   {:as   e
    :keys [uniqueid r state attr]} bus state* m]
  (when-let [device
             (some->> r
                      keyword
                      (get service-type-namespaces))]
    (when-let [ident (get-in @state* [:radiale.deconz :by-uniqueid uniqueid])]
      (if state
        (do (swap! state* assoc-in [:radiale.deconz ident :state] state)
            (async/>!!
              bus
              {::ident ident
               ::state state}))
        (swap! state* update-in [:radiale.deconz ident :props] merge attr)))))


(defn discover
  [{:keys [listen-deconz]} bus state*
   {:keys [::api-key ::host ::service-type-namespaces]
    :as   m}]
  (listen-deconz
    {:api-key api-key
     :host    host}
    (fn [result]
      (if-let [rc (:radialeconfig result)]
        (store-deconz-config service-type-namespaces state* rc)
        (state-change service-type-namespaces result bus state* (dissoc m ::api-key ::host))))))


(defn get-config
  [state* ident]
  (let [{:keys [:id :service]} (get-in @state* [:radiale.deconz ident :props])]
    {:id   id
     :type service}))

(defn put
  [{:keys [put-deconz]} bus state*
   {:keys [::state ::ident]
    :as   m}]
  (doseq [i (if (sequential? ident) ident [ident])]
    (doseq [s (if (sequential? state) state [state])]
      (put-deconz
        (merge (get-config state* i) {:state s})
        (fn [r]
          (timbre/debug i s r)))))

  (async/>!! bus m))


;; Watch-handler factory for deconz button-style sensors.
;; 
;; Resolves a state-diff bug: deconz sensor :state events frequently arrive
;; with only :lastupdated changing (e.g. two presses of the same button in a
;; row). `clojure.data/diff` then strips :buttonevent from the watch event's
;; ::state/now, so handlers that read `(get now :buttonevent)` would miss the
;; second press. Gating on :lastupdated and reading :buttonevent from @state*
;; sees every press.
(defn on-press
  [sensor-ident press-code build-cmds]
  (fn [state* {:keys [::state/domain ::state/ident ::state/prop ::state/now]}]
    (when (and
            (= domain :radiale.deconz)
            (= ident sensor-ident)
            (= prop :state)
            (:lastupdated now))
      (let [be (get-in @state* [:radiale.deconz sensor-ident :state :buttonevent])]
        (when (= press-code be)
          (build-cmds state*))))))


; (put-deconz {:type "lights" :id "8" :state {:on false}} log)
; (put-deconz {:type "lights" :id "8" :state {:on true :bri 0 :transitiontime 0}} log)
; (put-deconz {:type "lights" :id "8" :state {:bri 255 :transitiontime 600}} log)))


