(ns radiale.state-test
  (:require [clojure.test :refer :all]
            [radiale.state :as state]
            [clojure.core.async :as async]))

;; --- Unit tests for unpack ---
(deftest unpack-test
  (testing "empty map"
    (is (= [] (state/unpack [] {} 2))))

  (testing "flat map"
    (is (= [[[:a] 1] [[:b] 2]] (sort-by first (state/unpack [] {:a 1 :b 2} 2)))))

  (testing "nested map up to max-depth"
    (let [data {:a {:b 1 :c 2} :d 3}
          expected [[[:a :b] 1] [[:a :c] 2] [[:d] 3]]]
      (is (= (sort-by first expected) (sort-by first (state/unpack [] data 2))))))

  (testing "map nested up to max-depth exactly"
    (let [data {:a {:b {:x 10}}} ; :x is at depth 2 from :a's perspective
          expected [[[:a :b :x] 10]]]
      (is (= expected (state/unpack [] data 3)))))

  (testing "map nested deeper than max-depth"
    (let [data {:a {:b {:c 1 :d 2}}}
          expected [[[:a :b] {:c 1 :d 2}]]] ; :b is at depth 1, its value is taken as a whole
      (is (= expected (state/unpack [] data 1))))
    (let [data {:a {:b {:c 1}}}
          expected [[[:a :b] {:c 1}]]]
      (is (= expected (state/unpack [] data 1)))))

  (testing "nested map with different depths and max-depth"
    (let [data {:a 1 :b {:c 2 :d {:e 3}}}
          max-depth 2
          expected [[[:a] 1] [[:b :c] 2] [[:b :d] {:e 3}]]] ; :d is at depth 1, its value {:e 3} taken as whole
      (is (= (sort-by first expected) (sort-by first (state/unpack [] data max-depth))))))

  (testing "unpack with max-depth 0"
    (let [data {:a {:b 1}}
          expected [[[] {:a {:b 1}}]]]
        (is (= expected (state/unpack [] data 0))))))


;; --- Unit tests for watch-state ---
(deftest watch-state-test
  (let [test-state (atom {})
        send-chan (async/chan 10)
        watch-key ::test-watcher]

    (state/watch-state send-chan test-state) ; Watcher fn uses watch-key from state.clj

    (try
      (testing "assoc new key"
        (swap! test-state assoc :foo :bar)
        (let [msg (async/alt!! send-chan ([v] v) (async/timeout 100) ([] :timeout))]
          (is (not= :timeout msg))
          (is (= {:radiale.state/domain :foo, :radiale.state/ident nil, :radiale.state/prop nil, :radiale.state/prev nil, :radiale.state/now :bar}
                 (dissoc msg :radiale.state/path))))) ; Path might be complex depending on unpack

      (testing "assoc existing key (change value)"
        (swap! test-state assoc :foo :baz)
        (let [msg (async/alt!! send-chan ([v] v) (async/timeout 100) ([] :timeout))]
          (is (not= :timeout msg))
          (is (= {:radiale.state/domain :foo, :radiale.state/ident nil, :radiale.state/prop nil, :radiale.state/prev :bar, :radiale.state/now :baz}
                 (dissoc msg :radiale.state/path)))))

      (testing "assoc-in new nested key (depth 2)"
        (swap! test-state assoc-in [:a :b] :c)
        (let [msg (async/alt!! send-chan ([v] v) (async/timeout 100) ([] :timeout))]
          (is (not= :timeout msg))
          ;; Unpack with max-depth 2 will see [:a :b] as path for value :c
          (is (= {:radiale.state/domain :a, :radiale.state/ident :b, :radiale.state/prop nil, :radiale.state/prev nil, :radiale.state/now :c}
                 (dissoc msg :radiale.state/path)))))

      (testing "assoc-in existing nested key (change value, depth 2)"
        (swap! test-state assoc-in [:a :b] :d)
        (let [msg (async/alt!! send-chan ([v] v) (async/timeout 100) ([] :timeout))]
          (is (not= :timeout msg))
          (is (= {:radiale.state/domain :a, :radiale.state/ident :b, :radiale.state/prop nil, :radiale.state/prev :c, :radiale.state/now :d}
                 (dissoc msg :radiale.state/path)))))

      (testing "assoc-in new nested key (depth 3)"
        (swap! test-state assoc-in [:x :y :z] :hello)
        (let [msg (async/alt!! send-chan ([v] v) (async/timeout 100) ([] :timeout))]
          (is (not= :timeout msg))
          ;; Unpack with max-depth 2 will see [:x :y] as path for value {:z :hello}
          ;; So domain is :x, ident is :y, prop is nil, now is {:z :hello}
          (is (= {:radiale.state/domain :x, :radiale.state/ident :y, :radiale.state/prop :z, :radiale.state/prev nil, :radiale.state/now :hello}
                 (dissoc msg :radiale.state/path)))))

      (testing "dissoc key"
        (swap! test-state dissoc :foo)
        (let [msg (async/alt!! send-chan ([v] v) (async/timeout 100) ([] :timeout))]
          (is (not= :timeout msg))
          ;; For dissoc, :now will be nil for the key that was removed at the top level
          (is (= {:radiale.state/domain :foo, :radiale.state/ident nil, :radiale.state/prop nil, :radiale.state/prev :baz, :radiale.state/now nil}
                 (dissoc msg :radiale.state/path)))))

      (testing "no message if no change"
        (swap! test-state assoc :a {:b :d}) ; no change from previous test state for this path
        (let [msg (async/alt!! send-chan ([v] v) (async/timeout 100) ([] :timeout))]
          (is (= :timeout msg))))

      (finally
        (remove-watch test-state ::state/watcher) ; Use the actual key from state.clj
        (async/close! send-chan)))))
