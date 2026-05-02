(ns radiale.watchdog
  "A generic 'kicked-deadline' state machine.

   Multiple event sources push a deadline forward; a scheduled callback
   fires the off-action when the deadline arrives. Use cases: motion-driven
   lights, auto-off heated rails, garage-open warning, anything that wants
   'on for X minutes, extended by additional events, with a hard cap'.

   Configuration map (passed to extend!/clear!/bump!):
     ::key          unique key for this watchdog (also used as schedule
                    at-most-once key)
     ::on-cmd       rc command map (or sequence) to run when the deadline
                    is set or extended
     ::off-cmd      rc command map to run when the deadline expires or is
                    cleared
     ::step-ms      extend!: how much each call adds to the deadline
     ::max-ms       extend!: cap measured from now-ms
     ::bump-ms      bump!:   minimum remaining-time floor

   State is kept at [:radiale.watchdog <key>] in the shared atom."
  (:require
    [clojure.core.async :as async]
    [radiale.core :as rc]
    [radiale.schedule :as schedule]))


(defn state-path
  [key]
  [:radiale.watchdog key])


(defn compute-extend-target
  "extend! semantics: each call adds step-ms to the current deadline (or to
   now if expired), capped at now+max-ms. Floor at now handles stale state
   left over from a previously-fired schedule."
  [now-ms current-deadline step-ms max-ms]
  (let [base (max now-ms (or current-deadline 0))]
    (min (+ now-ms max-ms) (+ base step-ms))))


(defn compute-bump-target
  "bump! semantics: ensure deadline is at least now+bump-ms; never shorten."
  [now-ms current-deadline bump-ms]
  (max (or current-deadline 0) (+ now-ms bump-ms)))


(defn- on-and-schedule-off
  [key on-cmd off-cmd seconds]
  (conj
    (vec (if (sequential? on-cmd) on-cmd [on-cmd]))
    {::rc/fn                 schedule/after
     ::rc/desc               (str "watchdog off " key)
     ::schedule/at-most-once key
     ::schedule/seconds      seconds
     ::rc/then               off-cmd}))


(defn extend!
  "Push the deadline forward by step-ms (capped at now+max-ms), turning the
   on-cmd on and (re)scheduling the off-cmd. Returns the rc command vector."
  [state* {:keys [::key ::step-ms ::max-ms ::on-cmd ::off-cmd]} now-ms]
  (let [target (compute-extend-target now-ms (get-in @state* (state-path key)) step-ms max-ms)]
    (swap! state* assoc-in (state-path key) target)
    (on-and-schedule-off key on-cmd off-cmd (long (/ (- target now-ms) 1000)))))


(defn clear!
  "Cancel any pending schedule and emit the off-cmd immediately."
  [state* {:keys [::key ::off-cmd]}]
  (swap! state* assoc-in (state-path key) nil)
  [{::rc/fn                 schedule/cancel
    ::schedule/at-most-once key} off-cmd])


(defn bump!
  "Like extend! but only if the resulting deadline would be later than the
   current one. Returns nil when the bump wouldn't extend."
  [state* {:keys [::key ::bump-ms ::on-cmd ::off-cmd]} now-ms]
  (let [cur    (get-in @state* (state-path key))
        target (compute-bump-target now-ms cur bump-ms)]
    (when (or (nil? cur) (> target cur))
      (swap! state* assoc-in (state-path key) target)
      (on-and-schedule-off key on-cmd off-cmd (long (/ (- target now-ms) 1000))))))


(defn bump-cmd
  "rc-fn wrapper for bump!, suitable as a schedule/only-if when-true target.
   Reads the configuration map from the message itself."
  [_ send-chan state* config]
  (doseq [c (bump! state* config (System/currentTimeMillis))]
    (async/>!! send-chan c)))
