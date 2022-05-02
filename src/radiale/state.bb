(ns radiale.state
  (:require 
    [clojure.data]
    [clojure.core.async :as async]
    [taoensso.timbre :as timbre]
    [babashka.deps :as deps]))



; Device registry - esp, chromecast, deconz
; Device states
; Device history?

(defn unpack
  [p m max-depth]
  (if (and 
        (>= max-depth (count p))
        (map? m))
   (mapcat
     (fn [[k v]]
       (unpack (conj p k) v max-depth))
     m)
   [[p m]]))
                                                                                                           

(defn watch-state
  [send-chan state*]
  (add-watch state* ::watcher
    (fn [_ _ old-state new-state]
      (let [[prev now _] (clojure.data/diff old-state new-state)]
        (when now
          (doseq [[[domain device property :as path] nv] (unpack [] now 2)]
            ; (println domain device property) 
            ; (println "\t\t" (get-in prev path) "->" nv)                                                                      
            (async/>!! 
              send-chan
              {::domain domain
               ::ident device
               ::prop property
               ::prev (get-in prev path) 
               ::now nv})))))))                                                                      
        ; (async/>!! send-chan {::old m}))))) 









