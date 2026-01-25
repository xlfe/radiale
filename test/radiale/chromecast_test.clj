(ns radiale.chromecast-test
  (:require
    [clojure.core.async :as async :refer [chan close!]]
    [clojure.test :refer :all]
    [radiale.chromecast :as chromecast]
    [taoensso.timbre :as timbre]))

;; Fixture to silence Timbre logging during tests
(defn silence-logging-fixture
  [f]
  (let [original-config timbre/*config*]
    (timbre/set-config! {:min-level :fatal}) ; Suppress info/debug logs
    (f)
    (timbre/set-config! original-config)))

(use-fixtures :once silence-logging-fixture)

;; --- Unit tests for discover ---
(deftest discover-test
  (let [bus (chan) ; Not used by the active parts of discover, but part of signature
        state* (atom {})
        discover-config-m {} ; Initial config map for discover, not heavily used by current logic

        listen-mdns-calls (atom [])
        mdns-info-calls (atom [])
        subscribe-chromecast-calls (atom []) ; For the commented-out part
        timbre-info-calls (atom [])

        ;; Mock pod functions
        mock-listen-mdns (fn [opts callback-fn]
                           (swap! listen-mdns-calls conj
                             {:opts     opts
                              :callback callback-fn}))

        mock-mdns-info (fn [select-keys-opts callback-fn]
                         (swap! mdns-info-calls conj
                           {:opts     select-keys-opts
                            :callback callback-fn}))

        mock-subscribe-chromecast (fn [info callback-fn] ; For the commented-out part
                                    (swap! subscribe-chromecast-calls conj
                                      {:info     info
                                       :callback callback-fn}))

        mock-radiale-map {:listen-mdns          mock-listen-mdns
                          :mdns-info            mock-mdns-info
                          :subscribe-chromecast mock-subscribe-chromecast}]

    (with-redefs [;; If timbre/info was used directly, mock it here.
                  ;; The current code uses it inside a commented out (when (= "Kitchen display" ...))
                  ;; so we don't strictly need to mock it unless that condition is met.
                  ;; However, if any info logging is expected, it should be mocked.
                  taoensso.timbre/info (fn [& args]
                                         (swap! timbre-info-calls conj args))]

      (chromecast/discover mock-radiale-map bus state* discover-config-m)

      (testing "listen-mdns is called correctly"
        (is
          (= 1 (count @listen-mdns-calls)))
        (let [{:keys [opts callback]} (first @listen-mdns-calls)]
          (is
            (= {:service-type "_googlecast._tcp.local."} opts))
          (is
            (fn? callback))

          (testing "MDNS callback triggers mdns-info"
            ;; Simulate MDNS callback being invoked
            (let [mdns-state {:state-change "added"
                              :service-name "MyChromecast"
                              :service-type "_googlecast._tcp.local."}]
              (callback mdns-state)
              (is
                (= 1 (count @mdns-info-calls)))
              (let [{info-opts     :opts
                     info-callback :callback}
                    (first @mdns-info-calls)]
                (is
                  (= (select-keys mdns-state [:service-name :service-type]) info-opts))
                (is
                  (fn? info-callback))

                (testing "mdns-info callback updates state"
                  ;; Simulate mdns-info callback being invoked
                  (let [mock-mdns-info-data {:properties   {:id "cc123"
                                                            :fn "Living Room TV"}
                                             :addresses    ["192.168.1.100"]
                                             :service-name "MyChromecast"
                                             :server       "chromecast-server.local."}]
                    (info-callback mock-mdns-info-data)

                    (let [expected-chromecast-id "cc123"
                          expected-props         {:properties   {:id "cc123"
                                                                 :fn "Living Room TV"}
                                                  :addresses    ["192.168.1.100"]
                                                  :service-name "MyChromecast"
                                                  :server       "chromecast-server.local."}]
                      (is
                        (= expected-props (get-in @state* [:radiale.chromecast expected-chromecast-id :props]))))

                    ;; Test the (when (= "Kitchen display" ...)) part by changing fn
                    (let [mock-mdns-info-kitchen {:properties   {:id "cc456"
                                                                 :fn "Kitchen display"}
                                                  :addresses    ["192.168.1.101"]
                                                  :service-name "KitchenChromecast"
                                                  :server       "kitchen-cc.local."}]
                      (info-callback mock-mdns-info-kitchen)
                      ;; This would also update state, check if timbre/info was called
                      ;; The timbre/info is inside a commented out (when) block in the original code,
                      ;; but if that (when) was active and its condition met, info would be called.
                      ;; As the (when) is commented, timbre-info-calls should be empty unless something else logs.
                      ;; If the (when) was: (when (= "Kitchen display" (get-in mi [:properties :fn])) (timbre/info info))
                      ;; then with the above mock-mdns-info-kitchen, it would have been called.
                      ;; For now, assuming the (when) is effectively bypassed.
                      (is
                        (empty? @timbre-info-calls)
                        "Timbre/info should not be called as the relevant 'when' is commented out or condition not met by default tests.")

                      ;; Verify state for kitchen display is also updated
                      (let [expected-chromecast-id "cc456"
                            expected-props         {:properties   {:id "cc456"
                                                                   :fn "Kitchen display"}
                                                    :addresses    ["192.168.1.101"]
                                                    :service-name "KitchenChromecast"
                                                    :server       "kitchen-cc.local."}]
                        (is
                          (= expected-props
                             (get-in @state* [:radiale.chromecast expected-chromecast-id :props])))))))))))))
    ;; End of nested testing blocks - closes testing "listen-mdns", with-redefs, let, deftest
    (close! bus)))
