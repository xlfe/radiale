(ns radiale.watch-test
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer :all]
    [radiale.watch :as watch]))

;; Fixture to reset watches* atom before each test
(defn reset-watches-fixture
  [f]
  (reset! watch/watches* [])
  (f))

(use-fixtures :each reset-watches-fixture)

;; --- Unit tests for on ---
(deftest on-test
  (testing "adding a new watch"
    (is
      (empty? @watch/watches*))
    (let [watch-fn  (fn [s m]
                      (when (:match m)
                        :new-message))
          watch-def {:id        :test-watch1
                     ::watch/on watch-fn}]
      (watch/on nil nil nil watch-def) ; state*, bus, radiale-map are not used by 'on' itself
      (is
        (= 1 (count @watch/watches*)))
      (let [added-watch (first @watch/watches*)]
        (is
          (= :test-watch1 (:id added-watch)))
        (is
          (= watch-fn (::watch/on added-watch)))))))

;; --- Unit tests for match-message ---
(deftest match-message-test
  (let [send-chan          (async/chan 10)
        state*             (atom {})
        radiale-map        {} ; Mock radiale-map, not used by simple ::on fns here
        processed-messages (atom [])]

    ;; Helper to run match-message and capture output from send-chan
    (defn- run-and-capture
      [msg]
      (reset! processed-messages [])
      (async/go-loop [] ; Consume from channel to prevent blocking match-message if chan is full
        (when-let [v (async/<! send-chan)]
          (swap! processed-messages conj v)
          (recur)))
      (watch/match-message send-chan state* radiale-map msg)
      ;; Give a brief moment for async operations if any (though these ::on are sync)
      (Thread/sleep 10) ; Adjust if needed, for real async ::on you'd need better sync
      @processed-messages)

    (testing "no watches, no messages"
      (let [results (run-and-capture {:type :some-event})]
        (is
          (empty? results))))

    (testing "one watch, matches and returns a message"
      (watch/on
        nil
        nil
        nil
        {:id        :watch1
         ::watch/on (fn [_ _ _ m]
                      (when (= (:event m) :match-this)
                        {:response "matched_watch1"}))})
      (let [results (run-and-capture {:event :match-this})]
        (is
          (= [{:response "matched_watch1"}] results))))

    (testing "one watch, does not match"
      (watch/on
        nil
        nil
        nil
        {:id        :watch2
         ::watch/on (fn [_ _ _ m]
                      (when (= (:event m) :match-this)
                        {:response "matched_watch2"}))})
      (let [results (run-and-capture {:event :dont-match-this})]
        (is
          (empty? results))))

    (testing "one watch, matches but ::on returns nil"
      (watch/on
        nil
        nil
        nil
        {:id        :watch3
         ::watch/on (fn [_ _ _ m]
                      (when (= (:event m) :match-this)
                        nil))})
      (let [results (run-and-capture {:event :match-this})]
        (is
          (empty? results))))

    (testing "multiple watches, one matches"
      (watch/on
        nil
        nil
        nil
        {:id        :watchA
         ::watch/on (fn [_ _ _ m]
                      (when (= (:type m) :typeA)
                        {:resp "A"}))})
      (watch/on
        nil
        nil
        nil
        {:id        :watchB
         ::watch/on (fn [_ _ _ m]
                      (when (= (:type m) :typeB)
                        {:resp "B"}))})
      (watch/on
        nil
        nil
        nil
        {:id        :watchC
         ::watch/on (fn [_ _ _ m]
                      (when (= (:type m) :typeC)
                        {:resp "C"}))})

      (let [results (run-and-capture {:type :typeB})]
        (is
          (= [{:resp "B"}] results))))

    (testing "multiple watches, multiple match and return messages"
      (watch/on
        nil
        nil
        nil
        {:id        :watchM1
         ::watch/on (fn [_ _ _ m]
                      (when (:multi m)
                        {:val 1}))})
      (watch/on
        nil
        nil
        nil
        {:id        :watchM2
         ::watch/on (fn [_ _ _ m]
                      (when (:multi m)
                        {:val 2}))})
      (let [results (run-and-capture {:multi true})]
        ;; Order might not be guaranteed, so check as a set
        (is
          (= #{{:val 1} {:val 2}} (set results)))))

    (testing "watch ::on function uses state and radiale-map"
      (let [test-state (atom {:counter 10})
            test-rmap  {:multiplier 3}]
        (watch/on
          nil
          nil
          nil
          {:id        :watch_with_state
           ::watch/on (fn [s r _ m] ; state, radiale-map, original-message, matched-message (same here)
                        (when (= (:trigger m) :use-state)
                          {:current-counter (:counter @s)
                           :multiplied      (* (:counter @s) (:multiplier r))}))})
        (let [results (run-and-capture {:trigger :use-state})]
          (is
            (= [{:current-counter 10
                 :multiplied      30}]
               results)))))

    (async/close! send-chan)))
