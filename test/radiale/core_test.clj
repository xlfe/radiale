(ns radiale.core-test
  (:require [clojure.test :refer :all]
            [radiale.core :as rc]
            [radiale.watch :as watch]
            [radiale.state :as state.core] ; Aliased to avoid conflict with local 'state' atoms
            [clojure.core.async :as async :refer [>!! <!! close! chan go timeout]]
            [taoensso.timbre :as timbre]))

;; Fixture to silence Timbre logging during tests
(defn silence-logging-fixture [f]
  (let [original-config timbre/*config*] ; Correctly get the whole config map
    (timbre/set-config! {:min-level :error})
    (f)
    (timbre/set-config! original-config)))

(use-fixtures :once silence-logging-fixture)

;; Mock radiale-map for testing functions that expect it
(def mock-radiale-map
  {:some-pod-fn (fn [& args] {:pod-fn-called-with args})})

;; --- Unit tests for try-fn ---
(deftest try-fn-test
  (let [send-chan (chan 1)
        state-atom (atom {})
        watch-match-msg-calls (atom [])
        log-error-calls (atom [])]

    (with-redefs [watch/match-message (fn [sc sa rm m] (swap! watch-match-msg-calls conj {:sc sc :sa sa :rm rm :m m}))
                  timbre/error (fn [& args] (swap! log-error-calls conj args))]

      (testing "Scenario 1: ::fn is present"
        (let [actual-fn-args (atom nil)
              mock-actual-fn (fn [rd-map s-chan st-a msg-args]
                               (reset! actual-fn-args {:rm rd-map :sc s-chan :sa st-a :ma msg-args}))
              message {::rc/fn mock-actual-fn :data "payload"}]
          (reset! watch-match-msg-calls [])
          (rc/try-fn mock-radiale-map send-chan state-atom message)
          (is (= 1 (count @watch-match-msg-calls)))
          (is (some? @actual-fn-args))
          (is (= mock-radiale-map (:rm @actual-fn-args)))
          (is (= send-chan (:sc @actual-fn-args)))
          (is (= state-atom (:sa @actual-fn-args)))
          (is (= (dissoc message ::rc/fn) (:ma @actual-fn-args)))))

      (testing "Scenario 2: ::then is a function"
        (let [then-fn-args (atom nil)
              actual-fn-args (atom nil)
              another-mock-fn (fn [_ _ _ ma] (reset! actual-fn-args ma))
              mock-then-fn (fn [m] (reset! then-fn-args m) {:key :new-val ::rc/fn another-mock-fn})
              message {::rc/then mock-then-fn :original-data "original"}]
          (reset! watch-match-msg-calls [])
          (rc/try-fn mock-radiale-map send-chan state-atom message)
          (is (= 2 (count @watch-match-msg-calls))) ; Called for original and then for result of mock-then-fn
          (is (some? @then-fn-args))
          (is (= (dissoc message ::rc/then) @then-fn-args))
          (is (some? @actual-fn-args))
          (is (= {:key :new-val} @actual-fn-args)))) ; another-mock-fn gets map without ::fn

      (testing "Scenario 3: ::then is a map"
        (let [actual-fn-args (atom nil)
              another-mock-fn (fn [_ _ _ ma] (reset! actual-fn-args ma))
              message {::rc/then {:key :new-val ::rc/fn another-mock-fn} :original-data "original"}]
          (reset! watch-match-msg-calls [])
          (rc/try-fn mock-radiale-map send-chan state-atom message)
          (is (= 2 (count @watch-match-msg-calls)))
          (is (some? @actual-fn-args))
          (is (= {:key :new-val :original-data "original"} @actual-fn-args))))

      (testing "Scenario 4: ::then is a sequence"
        (let [fn1-calls (atom [])
              fn2-calls (atom [])
              mock-fn1 (fn [_ _ _ ma] (swap! fn1-calls conj ma))
              mock-fn2 (fn [_ _ _ ma] (swap! fn2-calls conj ma))
              message {::rc/then [{:item 1 ::rc/fn mock-fn1} {:item 2 ::rc/fn mock-fn2}] :original-data "seq"}]
          (reset! watch-match-msg-calls [])
          (rc/try-fn mock-radiale-map send-chan state-atom message)
          (is (= 3 (count @watch-match-msg-calls))) ; Original + two from sequence
          (is (= 1 (count @fn1-calls)))
          (is (= {:item 1 :original-data "seq"} (first @fn1-calls)))
          (is (= 1 (count @fn2-calls)))
          (is (= {:item 2 :original-data "seq"} (first @fn2-calls)))))

      (testing "Scenario 5: No ::fn or ::then (valid map, no action)"
        (let [message {:original-data "no-op"}]
          (reset! watch-match-msg-calls [])
          (reset! log-error-calls [])
          (rc/try-fn mock-radiale-map send-chan state-atom message)
          (is (= 1 (count @watch-match-msg-calls)))
          (is (empty? @log-error-calls)))) ; No error should be logged for this case

      (testing "Scenario 6: ::then is invalid type (e.g. string)"
        (let [message {::rc/then "invalid then" :original-data "error-case"}]
          (reset! watch-match-msg-calls [])
          (reset! log-error-calls [])
          (rc/try-fn mock-radiale-map send-chan state-atom message)
          (is (= 1 (count @watch-match-msg-calls)))
          (is (= 1 (count @log-error-calls))) ; timbre/error should be called
          (is (-> @log-error-calls first first :original-data (= "error-case")))))

    (close! send-chan)))


;; --- Unit tests for update-or-add ---
(deftest update-or-add-test
  (let [state* (atom {})]
    (testing "Add new state atom"
      (rc/update-or-add state* :my-device {:val 1})
      (is (contains? @state* :my-device))
      (is (instance? clojure.lang.Atom (@state* :my-device)))
      (is (= {:val 1} @(@state* :my-device))))

    (testing "Update existing state atom"
      (rc/update-or-add state* :my-device {:val 1}) ; Ensure it exists
      (rc/update-or-add state* :my-device {:val 2})
      (is (= {:val 2} @(@state* :my-device))))))


;; --- Unit tests for run's logic ---
(deftest run-logic-test
  (let [mock-state-watch-calls (atom [])
        mock-try-fn-calls (atom [])
        initial-config [{:initial :task1} {:initial :task2}]]

    (with-redefs [state.core/watch-state (fn [sc sa] (swap! mock-state-watch-calls conj {:sc sc :sa sa}))
                  rc/try-fn (fn [rm sc sa m] (swap! mock-try-fn-calls conj {:rm rm :sc sc :sa sa :m m}))]

      (testing "Initial config processing"
        ;; We need to run 'run' in a way that it doesn't loop forever.
        ;; We can't easily mock async/<!! to break the loop without complex setups.
        ;; So, we'll test the initial config processing part, then simulate one message.
        (let [send-chan (chan 1) ; Real channel for this part
              test-instance-state-atom (atom {})] ; The atom 'run' creates internally

          ;; To test the initial config processing, we can call 'run'
          ;; and then immediately close the channel to stop the while loop after it starts.
          ;; This is still a bit integration-y. A cleaner way is to ensure the loop itself
          ;; can be controlled, e.g. by a special message.
          ;; For now, let's focus on the setup and initial calls.
          (let [run-future (future (rc/run initial-config))]
            ;; Allow 'run' to start, process initial config, and hit <!!
            (Thread/sleep 100) ; Give it time to process initial config

            (is (= 1 (count @mock-state-watch-calls))) ; state/watch-state called once

            ;; Check initial config processing
            (is (= 2 (count @mock-try-fn-calls)))
            (is (= {:initial :task1} (:m (nth @mock-try-fn-calls 0))))
            (is (= {:initial :task2} (:m (nth @mock-try-fn-calls 1))))

            ;; Simulate one message being put on the channel and processed
            (reset! mock-try-fn-calls []) ; Clear for next check
            (let [chan-under-test (-> @mock-state-watch-calls first :sc) ; Get the actual channel used by 'run'
                  map-message {:type :map-message}]
              (>!! chan-under-test map-message)
              (Thread/sleep 50) ; Allow loop to process it
              (is (= 1 (count @mock-try-fn-calls)))
              (is (= map-message (:m (first @mock-try-fn-calls)))))

            (reset! mock-try-fn-calls [])
            (let [chan-under-test (-> @mock-state-watch-calls first :sc)
                  seq-message [{:type :seq-msg1} {:type :seq-msg2}]]
              (>!! chan-under-test seq-message)
              (Thread/sleep 50)
              (is (= 2 (count @mock-try-fn-calls)))
              (is (= {:type :seq-msg1} (:m (nth @mock-try-fn-calls 0))))
              (is (= {:type :seq-msg2} (:m (nth @mock-try-fn-calls 1)))))

            ;; Shutdown the 'run' loop by closing its channel from the outside
            (close! (-> @mock-state-watch-calls first :sc))
            @run-future ; Ensure future completes
            )))
    (Thread/sleep 100) ; Ensure any background go-blocks from previous tests complete on send-chan
    ))

;; Note: The 'run' test is more of an integration test for the initial setup
;; and first few messages. Testing the while(true) loop exhaustively in a unit
;; test is non-trivial and often involves more complex async mocking or
;; restructuring the code to make the loop body a testable function.
;; The provided test for `run` focuses on verifying that:
;; 1. `state/watch-state` is called.
;; 2. Initial configuration items are processed by `try-fn`.
;; 3. Messages (map and sequence) put on its internal channel are processed by `try-fn`.
;; It achieves loop control by closing the channel used by `async/<!!`.
