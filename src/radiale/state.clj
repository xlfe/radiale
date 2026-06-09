(ns radiale.state
  (:require
    [clojure.core.async :as async]
    [clojure.data]
    [taoensso.timbre :as timbre]))



; Device registry - esp, chromecast, deconz
; Device states
; Device history?

(defn unpack
  [p m max-depth]
  (if (and
        (>= max-depth (count p))
        (map? m))
    (mapcat (fn [[k v]]
              (unpack (conj p k) v max-depth))
      m)
    [[p m]]))


(defn watch-state
  [send-chan state*]
  (add-watch
    state*
    ::watcher
    (fn [_ _ old-state new-state]
      (let [[prev now _] (clojure.data/diff old-state new-state)]
        (when now
          (doseq [[[domain device property :as path] nv] (unpack [] now 2)]
            ; (println domain device property)
            ; (println "\t\t" (get-in prev path) "->" nv)
            (let [msg {::domain domain
                       ::ident  device
                       ::prop   property
                       ::prev   (get-in prev path)
                       ::now    nv}]
              (if (async/offer! send-chan msg)
                (timbre/trace
                  "State change queued"
                  {:domain domain
                   :ident  device
                   :prop   property})
                (timbre/warn
                  "State change channel full, dropping event"
                  {:domain domain
                   :ident  device
                   :prop   property})))))))))
        ; (async/>!! send-chan {::old m}))))) 
