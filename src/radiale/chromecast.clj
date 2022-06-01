(ns radiale.chromecast
  (:require 
    [radiale.core :as rc]
    [taoensso.timbre :as timbre]))

(defn discover
  [{:keys [listen-mdns mdns-info subscribe-chromecast]} bus state* m]
  (listen-mdns 
    {:service-type "_googlecast._tcp.local."}
    (fn [{:keys [state-change service-name] :as mdns-state}]
      (mdns-info (select-keys mdns-state [:service-name :service-type])
        (fn [mi]
          (let [info (select-keys mi [:properties :addresses :service-name :server])]
            (swap! state* assoc-in [:radiale.chromecast (get-in mi [:properties :id]) :props] info)))))))
                   ; (when (= "Kitchen display" (get-in mi [:properties :fn]))
                   ; (timbre/info info)))))))
            ; (subscribe-chromecast 
              ; info 
              ; (fn [msg] (prn msg)))))))))
                                   
          





