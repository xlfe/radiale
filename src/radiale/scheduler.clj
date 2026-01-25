(ns radiale.scheduler
  "A simple scheduler using core.async, compatible with Babashka.
   Provides similar functionality to overtone/at-at but using only
   core.async primitives (go blocks and timeout channels)."
  (:require
    [clojure.core.async :as async :refer [<! >! chan close! go go-loop timeout]]))

(defn mk-pool
  "Create a scheduler pool. In this implementation, the pool is just
   an atom tracking active jobs for potential cancellation."
  []
  (atom {:jobs {}}))

(defn- generate-id "Generate a unique job ID." [] (str (gensym "job-")))

(defn after
  "Schedule a function to run after `ms` milliseconds.
   Returns a job map that can be passed to `kill` to cancel.
   
   Arguments:
   - ms: delay in milliseconds
   - f: function to call (no arguments)
   - pool: scheduler pool from mk-pool"
  [ms f pool]
  (let [id        (generate-id)
        cancel-ch (chan)
        job       {:id           id
                   :type         :after
                   :cancel-ch    cancel-ch
                   :scheduled-at (System/currentTimeMillis)
                   :delay-ms     ms}]
    ;; Register the job
    (swap! pool assoc-in [:jobs id] job)

    ;; Start the async scheduling
    (go
      (let [[_ ch] (async/alts! [(timeout ms) cancel-ch])]
        (when (not= ch cancel-ch)
          ;; Timeout fired, not cancelled
          (try (f) (catch Exception e (println "Scheduler error in job" id ":" (.getMessage e)))))
        ;; Clean up
        (swap! pool update :jobs dissoc id)))

    job))

(defn every
  "Schedule a function to run every `ms` milliseconds.
   Returns a job map that can be passed to `kill` to cancel.
   
   Arguments:
   - ms: interval in milliseconds
   - f: function to call (no arguments)  
   - pool: scheduler pool from mk-pool"
  [ms f pool]
  (let [id        (generate-id)
        cancel-ch (chan)
        job       {:id           id
                   :type         :every
                   :cancel-ch    cancel-ch
                   :scheduled-at (System/currentTimeMillis)
                   :interval-ms  ms}]
    ;; Register the job
    (swap! pool assoc-in [:jobs id] job)

    ;; Start the repeating async loop
    (go-loop []
      (let [[_ ch] (async/alts! [(timeout ms) cancel-ch])]
        (when (not= ch cancel-ch)
          ;; Timeout fired, not cancelled
          (try (f) (catch Exception e (println "Scheduler error in job" id ":" (.getMessage e))))
          (recur))))
    ;; When loop exits (cancelled), clean up
    (go (<! cancel-ch) (swap! pool update :jobs dissoc id))

    job))

(defn kill
  "Cancel a scheduled job.
   
   Arguments:
   - job: the job map returned by `after` or `every`"
  [job]
  (when-let [cancel-ch (:cancel-ch job)]
    (close! cancel-ch))
  nil)

(defn stop-all
  "Stop all jobs in a pool."
  [pool]
  (doseq [[_ job] (:jobs @pool)]
    (kill job)))

(defn scheduled-jobs
  "Return a sequence of all currently scheduled jobs in the pool."
  [pool]
  (vals (:jobs @pool)))

(comment
  ;; Usage examples:

  ;; Create a pool
  (def pool (mk-pool))

  ;; Schedule something to run after 2 seconds
  (def job1 (after 2000 #(println "Hello after 2 seconds!") pool))

  ;; Schedule something to run every second
  (def job2 (every 1000 #(println "Tick!" (System/currentTimeMillis)) pool))

  ;; Cancel a job
  (kill job2)

  ;; See what's scheduled
  (scheduled-jobs pool)

  ;; Stop everything
  (stop-all pool))
