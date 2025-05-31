(ns radiale.esp-test
  (:require [clojure.test :refer :all]
            [radiale.esp :as esp]
            [clojure.core.async :as async :refer [>!! <!! chan close! poll! timeout alts!!]]
            [taoensso.timbre :as timbre]))

;; Fixture to silence Timbre logging during tests
(defn silence-logging-fixture [f]
  (let [original-config timbre/*config*]
    (timbre/set-config! {:min-level :fatal}) ; Suppress info/debug logs
    (f)
    (timbre/set-config! original-config)))

(use-fixtures :once silence-logging-fixture)

;; --- Unit tests for keywordize-esp-services ---
(deftest keywordize-esp-services-test
  (let [device-name "my-esp-device"
        services {"12345" {:object_id "light_1" :name "Main Light" :key 12345} ; ESPHome service
                  "67890" {:name "custom_svc" :key 67890}}]     ; User-defined service
    (testing "Keywordizing ESPHome and user-defined services"
      (let [result (esp/keywordize-esp-services device-name services)]
        (is (contains? result :light_1))
        (is (= :light_1 (get result "12345")))
        (is (= {:props {:object_id "light_1" :name "Main Light" :key 12345}}
               (get result :light_1)))

        (is (contains? result :custom_svc))
        (is (= :custom_svc (get result "67890")))
        (is (= {:props {:name "custom_svc" :key 67890}}
               (get result :custom_svc)))))))

;; --- Unit tests for esp-logger ---
(deftest esp-logger-test
  (let [bus (chan) ; Not directly used by all paths, but part of signature
        state* (atom {})
        base-m {}] ; Original message, not deeply inspected by all logger paths

    (testing "Receiving services"
      (reset! state* {})
      (let [services-payload {"abc" {:object_id "switch1" :name "My Switch"}
                              "def" {:name "my_user_service"}}
            msg {:service-name "test-esp" :services services-payload}]
        (with-redefs [esp/keywordize-esp-services (fn [dev-name srvs] {:mocked_switch1 {:props (srvs "abc")}
                                                                     :mocked_user_svc {:props (srvs "def")}})]
          (esp/esp-logger bus state* base-m msg))
        (is (= {:mocked_switch1 {:props {:object_id "switch1" :name "My Switch"}}
                :mocked_user_svc {:props {:name "my_user_service"}}}
               (get-in @state* [:radiale.esp :test-esp])))))

    (testing "Device connection status changed"
      (reset! state* {})
      (esp/esp-logger bus state* base-m {:service-name "test-esp" :connected true})
      (is (true? (get-in @state* [:radiale.esp :test-esp :connected])))
      (esp/esp-logger bus state* base-m {:service-name "test-esp" :connected false})
      (is (false? (get-in @state* [:radiale.esp :test-esp :connected]))))

    (testing "HA state subscription info"
      (reset! state* {})
      (esp/esp-logger bus state* base-m {:service-name "test-esp1" :ha-state-subscribe ["sensor.temp" "value"]})
      (is (contains? (get-in @state* [:radiale.subscription "sensor.temp" "value"])
                     :radiale.esp/test-esp1))) ; Note: esp-logger uses / for ns in keyword

    (testing "Regular state update"
      (reset! state* {:radiale.esp {:test-esp {"111" :the_switch}}}) ; Pre-populate mapping
      (esp/esp-logger bus state* base-m {:service-name "test-esp" :state ["111" "ON"]})
      (is (= "ON" (get-in @state* [:radiale.esp :test-esp :the_switch :state]))))
    (close! bus)))

;; --- Unit tests for discover ---
(deftest discover-test
  (let [bus (chan 10)
        state* (atom {})
        discover-m {:some-config "value"}
        listen-mdns-calls (atom [])
        subscribe-esp-calls (atom [])
        esp-logger-calls (atom [])
        mock-radiale-map {:listen-mdns (fn [opts cb] (swap! listen-mdns-calls conj {:opts opts :cb cb}))
                          :subscribe-esp (fn [opts cb] (swap! subscribe-esp-calls conj {:opts opts :cb cb}))}]

    (with-redefs [esp/esp-logger (fn [b s* m_orig esp_msg] (swap! esp-logger-calls conj {:esp_msg esp_msg :m_orig m_orig}))]
      (esp/discover mock-radiale-map bus state* discover-m)

      (is (= 1 (count @listen-mdns-calls)))
      (let [{:keys [opts cb]} (first @listen-mdns-calls)]
        (is (= {:service-type "_esphomelib._tcp.local."} opts))

        (testing "MDNS 'added' event triggers subscribe-esp"
          (cb {:state-change "added" :service-name "esp1"})
          (is (= 1 (count @subscribe-esp-calls)))
          (let [{subscribe-opts :opts subscribe-cb :cb} (first @subscribe-esp-calls)]
            (is (= {:service-name "esp1"} subscribe-opts))
            ;; Simulate ESPHome message received by the callback passed to subscribe-esp
            (let [esp-device-services {:services {"s1" {:name "light"}}}]
              (subscribe-cb esp-device-services)
              (is (= 1 (count @esp-logger-calls)))
              (is (= esp-device-services (:esp_msg (first @esp-logger-calls))))
              (is (= discover-m (:m_orig (first @esp-logger-calls)))))))

        (testing "MDNS 'updated' event triggers subscribe-esp"
          (reset! subscribe-esp-calls [])
          (reset! esp-logger-calls [])
          (cb {:state-change "updated" :service-name "esp2"})
          (is (= 1 (count @subscribe-esp-calls)))
          (let [{subscribe-opts :opts subscribe-cb :cb} (first @subscribe-esp-calls)]
            (is (= {:service-name "esp2"} subscribe-opts))
            (subscribe-cb {:connected true})
            (is (= {:connected true} (:esp_msg (first @esp-logger-calls))))))

        (testing "MDNS 'removed' event does not trigger subscribe-esp"
          (reset! subscribe-esp-calls [])
          (cb {:state-change "removed" :service-name "esp3"})
          (is (empty? @subscribe-esp-calls)))))))

;; --- Unit tests for esp-base-data ---
(deftest esp-base-data-test
  (let [state* (atom {:radiale.esp {:mydevice {"light_entity_id" {:props {:key "actual_hw_key_123"}}}}})]
    (is (= {:service-name "mydevice" :key "actual_hw_key_123"}
           (esp/esp-base-data state* :mydevice/light_entity_id)))))

;; --- Unit tests for command functions ---
(deftest command-functions-test
  (let [bus (chan 10)
        state* (atom {}) ; Can be empty if esp-base-data is mocked, or setup if not
        mock-pod-fn-calls (atom [])
        mock-esp-base-data-val {:service-name "test-esp" :key "entity_key_from_base"}
        original-message {:some "details"}
        common-test-fn (fn [command-fn pod-fn-kw cmd-specific-payload expected-pod-payload]
                         (reset! mock-pod-fn-calls [])
                         (let [mock-pod-fn (fn [params cb] (swap! mock-pod-fn-calls conj params) (cb {:success true}))
                               radiale-map-subset {pod-fn-kw mock-pod-fn}
                               message-payload (merge original-message cmd-specific-payload)]
                           (with-redefs [esp/esp-base-data (fn [_s _i] mock-esp-base-data-val)
                                         async/>!! (fn [ch msg] (>!! ch msg))]
                             (command-fn radiale-map-subset bus state* message-payload))

                           (is (= 1 (count @mock-pod-fn-calls)))
                           (is (= (merge mock-esp-base-data-val expected-pod-payload) (first @mock-pod-fn-calls)))
                           (is (= message-payload (poll! bus)))))]

    (testing "esp/switch"
      (common-test-fn esp/switch :switch-esp
                      {::esp/ident :test-esp/switch1 ::esp/state true}
                      {:state true}))

    (testing "esp/service"
      (common-test-fn esp/service :service-esp
                      {::esp/ident :test-esp/service1 ::esp/params {:arg1 "val"}}
                      {:params {:arg1 "val"}}))

    (testing "esp/light"
      (common-test-fn esp/light :light-esp
                      {::esp/ident :test-esp/light1 ::esp/params {:brightness 255}}
                      {:params {:brightness 255}}))

    (testing "esp/state (special case, does not use esp-base-data)"
      (reset! mock-pod-fn-calls [])
      (let [mock-pod-state-fn (fn [params cb] (swap! mock-pod-fn-calls conj params) (cb {:success true}))
            radiale-map-subset {:state-esp mock-pod-state-fn}
            message {::esp/ident :test-esp-raw ::esp/entity-id "ha.entity" ::esp/attribute "attr" ::esp/state "val"}]
        (with-redefs [async/>!! (fn [ch msg] (>!! ch msg))]
          (esp/state radiale-map-subset bus state* message))
        (is (= 1 (count @mock-pod-fn-calls)))
        (is (= {:service-name :test-esp-raw :entity_id "ha.entity" :attribute "attr" :state "val"}
               (first @mock-pod-fn-calls)))
        (is (= message (poll! bus)))))
    (close! bus)))
