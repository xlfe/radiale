(ns radiale.watch
  (:require
    [clojure.core.async :as async]
    [taoensso.timbre :as timbre]))


(defonce watches* (atom []))


(defn match-message
  [send-chan state* m]
  (doseq [{:keys [::on :id]
           :as   o}
          @watches*]

    (cond
      (fn? on)
      (try (when-let [nm (on state* m)]
             (if (async/offer! send-chan nm)
               (timbre/trace "Watch handler queued message" {:watch-id id})
               (timbre/warn "Watch handler channel full, dropping message" {:watch-id id})))
           (catch Exception e (timbre/error e "Watch handler failed" {:watch-id id})))

      :else
      (timbre/warn
        "Invalid watch handler"
        {:watch-id id
         :on-type  (type on)}))))


(defn on
  [rm send-chan _ m]
  (swap! watches* conj m))


