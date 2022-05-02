(ns radiale.watch
  (:require
    [clojure.core.async :as async]
    [taoensso.timbre :as timbre]))


(defonce watches* (atom []))


(defn match-message
  [send-chan state* m]
  (doseq [{:keys [::on] :as o}  @watches*]

    (cond 
      (fn? on)
      (when-let [nm (on state* m)]
        (async/>!! send-chan nm)))))


(defn on
  [rm send-chan _ m]
  (swap! watches* conj m))
  

