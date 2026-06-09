(ns radiale.schedule-test
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer :all]
    [radiale.schedule :as sched]
    [radiale.scheduler :as scheduler]
    [taoensso.timbre :as timbre])) ; For mocking info

;; Fixture to silence Timbre logging during tests, can be useful
(defn silence-logging-fixture
  [f]
  (let [original-level timbre/*config*]
    (timbre/set-config! {:min-level :error}) ; Set to a level that won't show info/debug
    (f)
    (timbre/set-config! original-level)))

(use-fixtures :once silence-logging-fixture)

;; --- Unit tests for run-schedule ---
(deftest run-schedule-test
  (let [mock-sched-fn-called (atom false)
        mock-call-fn-called (atom false)
        mock-scheduler-after-called (atom false)
        mock-scheduler-every-called (atom false)
        mock-scheduler-kill-called (atom false)
        state* (atom {})
        desc "test-description"]

    (with-redefs [scheduler/after (fn [ms f _pool]
                                    (reset! mock-scheduler-after-called true)
                                    (f) ; call function immediately
                                    {:id "mock-job"}) ; return a mock job
                  scheduler/every (fn [ms f _pool]
                                    (reset! mock-scheduler-every-called true)
                                    (f)
                                    {:id "mock-job"})
                  scheduler/kill  (fn [job]
                                    (reset! mock-scheduler-kill-called job))]

      (testing "schedule-again? is false with :after"
        (reset! mock-sched-fn-called false)
        (reset! mock-call-fn-called false)
        (reset! mock-scheduler-after-called false)
        (let [sched-fn (fn [cb]
                         (reset! mock-sched-fn-called true)
                         (cb {:ms 100}))
              call-fn  #(reset! mock-call-fn-called true)]
          (sched/run-schedule false :after sched-fn call-fn state* nil desc))
        (is
          @mock-sched-fn-called)
        (is
          @mock-call-fn-called)
        (is
          @mock-scheduler-after-called))

      ;; Note: Testing schedule-again? = true is complex due to the recursive nature.
      ;; The function calls itself after scheduler/after completes. With our mocked scheduler/after
      ;; that calls f immediately, this would lead to infinite recursion if not controlled.
      ;; For now, we skip this test as the schedule-again? = false case is tested above.


      (testing "unique job is killed if it exists"
        (reset! mock-sched-fn-called false)
        (reset! mock-call-fn-called false)
        (reset! mock-scheduler-kill-called nil)
        (let [unique-id :my-unique-job
              sched-fn  (fn [cb]
                          (reset! mock-sched-fn-called true)
                          (cb {:ms 100}))
              call-fn   #(reset! mock-call-fn-called true)]
          (swap! state* assoc-in [:radiale.schedule :unique unique-id] {:id "dummy-job-id"})
          (sched/run-schedule false :after sched-fn call-fn state* unique-id desc)
          (is
            @mock-sched-fn-called)
          (is
            @mock-call-fn-called)
          (is
            (= {:id "dummy-job-id"} @mock-scheduler-kill-called))
          ;; After scheduling, the new job is stored in state (the mock returns {:id "mock-job"})
          ;; The state is set to nil inside the callback, but then immediately set to the new job
          (is
            (= {:id "mock-job"} (get-in @state* [:radiale.schedule :unique unique-id])))))

    )))

;; Note: Testing timbre/info logging is difficult because timbre uses macros.
;; The run-schedule function's logging behavior is an implementation detail.
;; The core functionality (scheduling, killing existing jobs) is tested above.

;; --- Mock Pod Functions ---
(def mock-radiale-map
  {:millis-crontab (fn [params cb]
                     (cb {:ms 1000}))
   :millis-solar   (fn [params cb]
                     (cb {:ms 2000}))
   :astral-now     (fn [location]
                     "day")})


;; --- Unit tests for crontab and solar ---
(deftest crontab-solar-common-test
  (let [send-chan       (async/chan 1)
        state           (atom {})
        params-map      {:some "param"}
        desc            "test-desc"
        at-most-once-id "test-job-123"]
    (doseq [{:keys [sched-under-test pod-fn-key sched-fn-name-expected]} [{:sched-under-test       sched/crontab
                                                                           :pod-fn-key             :millis-crontab
                                                                           :sched-fn-name-expected :after}
                                                                          {:sched-under-test       sched/solar
                                                                           :pod-fn-key             :millis-solar
                                                                           :sched-fn-name-expected :after}]]
      (testing (str "Testing " (if (= pod-fn-key :millis-crontab) "crontab" "solar"))
        (let [run-schedule-called (atom nil)
              m {::sched/params       params-map
                 ::sched/at-most-once at-most-once-id
                 :radiale.core/desc   desc
                 :some-other-key      "val"}]
          (with-redefs [sched/run-schedule (fn [sa? sched-fn-name sfn cfn st u d]
                                             (reset! run-schedule-called {:schedule-again? sa?
                                                                          :sched-fn-name   sched-fn-name
                                                                          :sched-fn        sfn
                                                                          :call-fn         cfn
                                                                          :state           st
                                                                          :unique          u
                                                                          :desc            d})
                                             (sfn
                                               (fn [res]
                                                 (cfn)))) ; Call sched-fn and then call-fn
                        async/>!! (fn [ch msg]
                                    (async/put! ch msg))] ; Capture message to channel

            (sched-under-test mock-radiale-map send-chan state m)

            (let [run-schedule-args @run-schedule-called]
              (is
                (not (nil? run-schedule-args)))
              (is
                (:schedule-again? run-schedule-args)) ; schedule-again? is true
              (is
                (= sched-fn-name-expected (:sched-fn-name run-schedule-args)))
              (is
                (= state (:state run-schedule-args)))
              (is
                (= at-most-once-id (:unique run-schedule-args)))
              (is
                (= desc (:desc run-schedule-args))))

            ;; Test call-fn (it should put the original message on the channel)
            (let [result-msg (async/<!! send-chan)]
              (is
                (= m result-msg)))))))))


;; --- Unit tests for after and every ---
(deftest after-every-common-test
  (let [send-chan       (async/chan 1)
        state           (atom {})
        seconds-val     60
        desc            "test-desc-ae"
        at-most-once-id "test-job-ae-123"]
    (doseq [{:keys [sched-under-test sched-fn-name-expected schedule-again-expected]} [{:sched-under-test sched/after
                                                                                        :sched-fn-name-expected :after
                                                                                        :schedule-again-expected false}
                                                                                       {:sched-under-test sched/every
                                                                                        :sched-fn-name-expected :every
                                                                                        :schedule-again-expected
                                                                                        false}]] ; scheduler/every handles repetition itself
      (testing (str "Testing " (if (= sched-under-test sched/after) "after" "every"))
        (let [run-schedule-called (atom nil)
              m {::sched/seconds      seconds-val
                 ::sched/at-most-once at-most-once-id
                 :radiale.core/desc   desc}]
          (with-redefs [sched/run-schedule (fn [sa? sched-fn-name sfn cfn st u d]
                                             (reset! run-schedule-called {:schedule-again? sa?
                                                                          :sched-fn-name   sched-fn-name
                                                                          :sched-fn        sfn
                                                                          :call-fn         cfn
                                                                          :state           st
                                                                          :unique          u
                                                                          :desc            d})
                                             (sfn
                                               (fn [res]
                                                 (cfn)))) ; Call sched-fn and then call-fn
                        async/>!! (fn [ch msg]
                                    (async/put! ch msg))]

            (sched-under-test mock-radiale-map send-chan state m) ; mock-radiale-map is not used by after/every's sched-fn

            (let [run-schedule-args @run-schedule-called]
              (is
                (not (nil? run-schedule-args)))
              (is
                (= schedule-again-expected (:schedule-again? run-schedule-args)))
              (is
                (= sched-fn-name-expected (:sched-fn-name run-schedule-args)))
              (is
                (= state (:state run-schedule-args)))
              (is
                (= at-most-once-id (:unique run-schedule-args)))
              (is
                (= desc (:desc run-schedule-args)))

              ;; Test sched-fn (it should call the callback with calculated millis)
              (let [sched-fn-callback-result (atom nil)]
                ((:sched-fn run-schedule-args)
                  (fn [res]
                    (reset! sched-fn-callback-result res)))
                (is
                  (= (* seconds-val 1000) @sched-fn-callback-result))))

            ;; Test call-fn (it should put the original message on the channel)
            (let [result-msg (async/<!! send-chan)]
              (is
                (= m result-msg)))))))))


;; --- Unit tests for only-if ---
(deftest only-if-test
  (let [send-chan        (async/chan 1)
        state            (atom {}) ; Not used by only-if directly but part of signature
        location-val     "TestLocation"
        when-true-map    {:action "do-this"}
        astral-now-calls (atom [])
        async-put-calls  (atom [])]

    (with-redefs [async/>!! (fn [ch msg]
                              (swap! async-put-calls conj msg)
                              (async/put! ch msg))]

      (testing "criteria matches"
        (reset! astral-now-calls [])
        (reset! async-put-calls [])
        (let [mocked-astral-now (fn [loc]
                                  (swap! astral-now-calls conj loc)
                                  "day") ; Returns "day"
              m {::sched/location  location-val
                 ::sched/criteria  #{:radiale.schedule/day}
                 ::sched/when-true when-true-map}]
          (sched/only-if (assoc mock-radiale-map :astral-now mocked-astral-now) send-chan state m)
          (is
            (= [location-val] @astral-now-calls))
          (is
            (= [when-true-map] @async-put-calls))
          (is
            (= when-true-map (async/<!! send-chan)))))

      (testing "criteria does not match"
        (reset! astral-now-calls [])
        (reset! async-put-calls [])
        (let [mocked-astral-now (fn [loc]
                                  (swap! astral-now-calls conj loc)
                                  "night") ; Returns "night"
              m {::sched/location  location-val
                 ::sched/criteria  #{:radiale.schedule/day}
                 ::sched/when-true when-true-map}]
          (sched/only-if (assoc mock-radiale-map :astral-now mocked-astral-now) send-chan state m)
          (is
            (= [location-val] @astral-now-calls))
          (is
            (empty? @async-put-calls))
          ;; Channel should be empty, use timeout to check
          (let [[val ch] (async/alts!! [(async/timeout 50) send-chan])]
            (is
              (not= ch send-chan))))))
    (async/close! send-chan)))


;; --- Unit tests for cancel ---
(deftest cancel-test
  (let [kill-calls (atom [])]
    (with-redefs [scheduler/kill (fn [job]
                                   (swap! kill-calls conj job)
                                   nil)]

      (testing "no-op when no existing job"
        (reset! kill-calls [])
        (let [state* (atom {})]
          (sched/cancel nil nil state* {::sched/at-most-once :nothing-here})
          (is
            (empty? @kill-calls))
          (is
            (nil? (get-in @state* [:radiale.schedule :unique :nothing-here])))))

      (testing "kills existing job and clears state path"
        (reset! kill-calls [])
        (let [job-id "job-42"
              state* (atom {})]
          (swap! state* assoc-in [:radiale.schedule :unique :outdoor-off] {:id job-id})
          (sched/cancel nil nil state* {::sched/at-most-once :outdoor-off})
          (is
            (= [{:id job-id}] @kill-calls))
          (is
            (nil? (get-in @state* [:radiale.schedule :unique :outdoor-off]))))))))
