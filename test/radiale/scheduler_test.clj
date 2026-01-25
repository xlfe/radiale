(ns radiale.scheduler-test
  "Tests for the core.async-based scheduler module."
  (:require
    [clojure.core.async :as async :refer [<!! alts!! timeout]]
    [clojure.test :refer :all]
    [radiale.scheduler :as sched]))

(deftest mk-pool-test
  (testing "mk-pool creates an atom with empty jobs map"
    (let [pool (sched/mk-pool)]
      (is
        (instance? clojure.lang.Atom pool))
      (is
        (= {:jobs {}} @pool)))))

(deftest after-test
  (testing "after schedules function to run after delay"
    (let [pool   (sched/mk-pool)
          result (atom nil)
          job    (sched/after 50 #(reset! result :done) pool)]
      ;; Job should be registered
      (is
        (= 1 (count (sched/scheduled-jobs pool))))
      (is
        (= :after (:type job)))
      ;; Wait for job to complete
      (Thread/sleep 100)
      (is
        (= :done @result))
      ;; Job should be cleaned up
      (is
        (= 0 (count (sched/scheduled-jobs pool))))))

  (testing "after can be cancelled"
    (let [pool   (sched/mk-pool)
          result (atom nil)
          job    (sched/after 100 #(reset! result :done) pool)]
      ;; Cancel immediately
      (sched/kill job)
      ;; Wait past the scheduled time
      (Thread/sleep 150)
      ;; Function should not have been called
      (is
        (nil? @result)))))

(deftest every-test
  (testing "every schedules function to run repeatedly"
    (let [pool    (sched/mk-pool)
          counter (atom 0)
          job     (sched/every 30 #(swap! counter inc) pool)]
      ;; Job should be registered
      (is
        (= 1 (count (sched/scheduled-jobs pool))))
      (is
        (= :every (:type job)))
      ;; Wait for a few executions
      (Thread/sleep 100)
      ;; Should have run at least 2-3 times
      (is
        (>= @counter 2))
      ;; Kill the job
      (sched/kill job)
      (let [final-count @counter]
        ;; Wait more and verify it stopped
        (Thread/sleep 50)
        (is
          (= final-count @counter)))))

  (testing "every can be cancelled"
    (let [pool    (sched/mk-pool)
          counter (atom 0)
          job     (sched/every 30 #(swap! counter inc) pool)]
      ;; Cancel immediately
      (sched/kill job)
      ;; Wait past the scheduled time
      (Thread/sleep 100)
      ;; Function should not have been called (or called very few times)
      (is
        (<= @counter 1)))))

(deftest kill-test
  (testing "kill returns nil"
    (let [pool (sched/mk-pool)
          job  (sched/after 1000 #(println "never") pool)]
      (is
        (nil? (sched/kill job)))))

  (testing "kill with nil job doesn't throw"
    (is
      (nil? (sched/kill nil))))

  (testing "kill with job without cancel-ch doesn't throw"
    (is
      (nil? (sched/kill {})))))

(deftest stop-all-test
  (testing "stop-all cancels all jobs"
    (let [pool    (sched/mk-pool)
          results (atom [])]
      ;; Schedule multiple jobs
      (sched/after 100 #(swap! results conj :a) pool)
      (sched/after 100 #(swap! results conj :b) pool)
      (sched/every 50 #(swap! results conj :c) pool)
      ;; Should have 3 jobs
      (is
        (= 3 (count (sched/scheduled-jobs pool))))
      ;; Stop all
      (sched/stop-all pool)
      ;; Wait past the scheduled time
      (Thread/sleep 150)
      ;; No functions should have been called (or very few)
      (is
        (<= (count @results) 1)))))

(deftest scheduled-jobs-test
  (testing "scheduled-jobs returns empty seq for empty pool"
    (let [pool (sched/mk-pool)]
      (is
        (empty? (sched/scheduled-jobs pool)))))

  (testing "scheduled-jobs returns all active jobs"
    (let [pool (sched/mk-pool)]
      (sched/after 1000 #() pool)
      (sched/after 1000 #() pool)
      (sched/every 1000 #() pool)
      (is
        (= 3 (count (sched/scheduled-jobs pool))))
      (sched/stop-all pool))))
