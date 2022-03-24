(ns radiale.watch
  (:require
    [clojure.core.async :as async]
    [taoensso.timbre :as timbre]))


(defonce watches* (atom []))


(defn match-message
  [send-chan m]
  (doseq [{:keys [::on] :as o}  @watches*]

    (cond 
      (fn? on)
      (when-let [nm (on m)]
        (async/>!! send-chan (merge o nm))))))


(defn on
  [rm send-chan m]
  (swap! watches* conj m))
  

