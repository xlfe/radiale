(ns radiale.state-test
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer :all]
    [radiale.state :as state]))

;; --- Unit tests for unpack ---
(deftest unpack-test
  (testing "empty map"
    (is
      (= [] (state/unpack [] {} 2))))

  (testing "flat map"
    (is
      (= [[[:a] 1] [[:b] 2]]
         (sort-by
           first
           (state/unpack
             []
             {:a 1
              :b 2}
             2)))))

  (testing "nested map up to max-depth"
    ;; With max-depth=2, unpack continues while (count path) <= 2
    ;; So {:a {:b 1}} with max-depth=2 will recurse to [:a :b] -> 1
    (let [data     {:a {:b 1
                        :c 2}
                    :d 3}
          expected [[[:a :b] 1] [[:a :c] 2] [[:d] 3]]]
      (is
        (= (sort-by first expected) (sort-by first (state/unpack [] data 2))))))

  (testing "map nested up to max-depth exactly"
    ;; With max-depth=2, path [:a :b] has count=2, so 2>=2 is true, continues
    ;; path [:a :b :x] has count=3, so 2>=3 is false, returns [[:a :b :x] 10]
    (let [data     {:a {:b {:x 10}}}
          expected [[[:a :b :x] 10]]]
      (is
        (= expected (state/unpack [] data 2)))))

  (testing "map nested deeper than max-depth"
    ;; With max-depth=1, path [:a] has count=1, so 1>=1 is true, continues
    ;; path [:a :b] has count=2, so 1>=2 is false, returns [[:a :b] {:c 1 :d 2}]
    (let [data     {:a {:b {:c 1
                            :d 2}}}
          expected [[[:a :b]
                     {:c 1
                      :d 2}]]]
      (is
        (= expected (state/unpack [] data 1))))
    (let [data     {:a {:b {:c 1}}}
          expected [[[:a :b] {:c 1}]]]
      (is
        (= expected (state/unpack [] data 1)))))

  (testing "nested map with different depths and max-depth"
    ;; With max-depth=2:
    ;; [:a] -> 1 (not a map, returns [[:a] 1])
    ;; [:b :c] -> 2 (not a map, returns [[:b :c] 2])
    ;; [:b :d :e] -> 3 (path count=3, 2>=3 is false, returns [[:b :d :e] 3])
    ;; Wait, [:b :d] has count=2, 2>=2 true, continues to {:e 3}
    ;; [:b :d :e] has count=3, 2>=3 false, returns [[:b :d :e] 3]
    (let [data      {:a 1
                     :b {:c 2
                         :d {:e 3}}}
          max-depth 2
          expected  [[[:a] 1] [[:b :c] 2] [[:b :d :e] 3]]]
      (is
        (= (sort-by first expected) (sort-by first (state/unpack [] data max-depth))))))

  (testing "unpack with max-depth 0"
    ;; With max-depth=0, path [] has count=0, so 0>=0 is true, continues if map
    ;; path [:a] has count=1, so 0>=1 is false, returns [[:a] {:b 1}]
    (let [data     {:a {:b 1}}
          expected [[[:a] {:b 1}]]]
      (is
        (= expected (state/unpack [] data 0))))))


;; --- Unit tests for watch-state ---
(deftest watch-state-test
  (let [test-state (atom {})
        send-chan  (async/chan 10)
        watch-key  ::test-watcher]

    (state/watch-state send-chan test-state) ; Watcher fn uses watch-key from state.clj

    (try (testing "assoc new key"
           (swap! test-state assoc :foo :bar)
           (let [msg (async/alt!! send-chan ([v] v)
                                  (async/timeout 100) ([] :timeout))]
             (is
               (not= :timeout msg))
             (is
               (= {:radiale.state/domain :foo
                   :radiale.state/ident  nil
                   :radiale.state/prop   nil
                   :radiale.state/prev   nil
                   :radiale.state/now    :bar}
                  (dissoc msg :radiale.state/path))))) ; Path might be complex depending on unpack

         (testing "assoc existing key (change value)"
           (swap! test-state assoc :foo :baz)
           (let [msg (async/alt!! send-chan ([v] v)
                                  (async/timeout 100) ([] :timeout))]
             (is
               (not= :timeout msg))
             (is
               (= {:radiale.state/domain :foo
                   :radiale.state/ident  nil
                   :radiale.state/prop   nil
                   :radiale.state/prev   :bar
                   :radiale.state/now    :baz}
                  (dissoc msg :radiale.state/path)))))

         (testing "assoc-in new nested key (depth 2)"
           (swap! test-state assoc-in [:a :b] :c)
           (let [msg (async/alt!! send-chan ([v] v)
                                  (async/timeout 100) ([] :timeout))]
             (is
               (not= :timeout msg))
             ;; Unpack with max-depth 2 will see [:a :b] as path for value :c
             (is
               (= {:radiale.state/domain :a
                   :radiale.state/ident  :b
                   :radiale.state/prop   nil
                   :radiale.state/prev   nil
                   :radiale.state/now    :c}
                  (dissoc msg :radiale.state/path)))))

         (testing "assoc-in existing nested key (change value, depth 2)"
           (swap! test-state assoc-in [:a :b] :d)
           (let [msg (async/alt!! send-chan ([v] v)
                                  (async/timeout 100) ([] :timeout))]
             (is
               (not= :timeout msg))
             (is
               (= {:radiale.state/domain :a
                   :radiale.state/ident  :b
                   :radiale.state/prop   nil
                   :radiale.state/prev   :c
                   :radiale.state/now    :d}
                  (dissoc msg :radiale.state/path)))))

         (testing "assoc-in new nested key (depth 3)"
           (swap! test-state assoc-in [:x :y :z] :hello)
           (let [msg (async/alt!! send-chan ([v] v)
                                  (async/timeout 100) ([] :timeout))]
             (is
               (not= :timeout msg))
             ;; Unpack with max-depth 2 will see [:x :y] as path for value {:z :hello}
             ;; So domain is :x, ident is :y, prop is nil, now is {:z :hello}
             (is
               (= {:radiale.state/domain :x
                   :radiale.state/ident  :y
                   :radiale.state/prop   :z
                   :radiale.state/prev   nil
                   :radiale.state/now    :hello}
                  (dissoc msg :radiale.state/path)))))

         ;; Note: dissoc doesn't produce a change event because clojure.data/diff
         ;; returns nil for `now` when a key is removed (the key is simply absent
         ;; from the new state). The watch-state function only sends messages when
         ;; `now` is truthy, so removals are not currently tracked.

         (testing "no message if no change"
           (swap! test-state assoc :a {:b :d}) ; no change from previous test state for this path
           (let [msg (async/alt!! send-chan ([v] v)
                                  (async/timeout 100) ([] :timeout))]
             (is
               (= :timeout msg))))

         (finally
           (remove-watch test-state ::state/watcher) ; Use the actual key from state.clj
           (async/close! send-chan)))))
