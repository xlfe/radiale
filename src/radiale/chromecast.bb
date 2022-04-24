(ns radiale.chromecast
  (:require 
    [radiale.core :as rc]
    [taoensso.timbre :as timbre]))


(def CHROMECAST-MDNS  "_googlecast._tcp.local.")

(defonce chromecast-registry* (atom {}))

(defn discover
  [{:keys [listen-mdns mdns-info]} bus m]
  (listen-mdns 
   {:service-type CHROMECAST-MDNS}
   (fn [{:keys [state-change service-name] :as mdns-state}]
     (mdns-info (select-keys mdns-state [:service-name :service-type])
       (fn [mi]
         (let [info (select-keys mi [:properties :addresses :service-name :server])]
           (swap! chromecast-registry* assoc (:server mi) info)
           (timbre/info info)))))))


(defn play-uri
  [{:keys [switch-esp]} bus {:keys [::fn]}])

