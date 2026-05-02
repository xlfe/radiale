(ns radiale.deconz-test
  (:require
    [clojure.core.async :as async :refer [<!! >!! alts!! chan close! poll! timeout]]
    [clojure.test :refer :all]
    [radiale.deconz :as deconz]
    [radiale.state :as state]
    [taoensso.timbre :as timbre]))

;; Fixture to silence Timbre logging during tests
(defn silence-logging-fixture
  [f]
  (let [original-config timbre/*config*]
    (timbre/set-config! {:min-level :fatal}) ; Set to a level that won't show info/debug
    (f)
    (timbre/set-config! original-config)))

(use-fixtures :once silence-logging-fixture)

;; --- Mock Pod Functions Vars (to be redefined in tests) ---
;; These are just placeholders for binding; actual pod functions are in pod.xlfe.radiale
;; We will use with-redefs on the actual var names like pod.xlfe.radiale/listen-deconz

;; --- Unit tests for store-deconz-config ---
(deftest store-deconz-config-test
  (let [state*        (atom {})
        service-type-namespaces {:lights  :radiale.light
                                 :sensors :radiale.sensor}
        config-result {"lights"  {"1" {:name     "Light 1"
                                       :uniqueid "uid-l1"
                                       :state    {:on false}}
                                  "2" {:name     "Light 2"
                                       :uniqueid "uid-l2"
                                       :state    {:on true}}}
                       "sensors" {"10" {:name     "Sensor 1"
                                        :uniqueid "uid-s10"
                                        :state    {:open true}}}}]
    (deconz/store-deconz-config service-type-namespaces state* config-result)

    (testing "Light 1 config"
      (let [ident (keyword "radiale.light" "Light 1")] ; keyword with space in name
        (is
          (= {:name     "Light 1"
              :uniqueid "uid-l1"
              :service  "lights" ; API type (plural) for Deconz REST API
              :id       "1"}
             (get-in @state* [:radiale.deconz ident :props])))
        (is
          (= {:on false} (get-in @state* [:radiale.deconz ident :state])))
        (is
          (= ident (get-in @state* [:radiale.deconz :by-uniqueid "uid-l1"])))))

    (testing "Light 2 config"
      (let [ident (keyword "radiale.light" "Light 2")]
        (is
          (= {:name     "Light 2"
              :uniqueid "uid-l2"
              :service  "lights" ; API type (plural) for Deconz REST API
              :id       "2"}
             (get-in @state* [:radiale.deconz ident :props])))
        (is
          (= {:on true} (get-in @state* [:radiale.deconz ident :state])))
        (is
          (= ident (get-in @state* [:radiale.deconz :by-uniqueid "uid-l2"])))))

    (testing "Sensor 10 config"
      (let [ident (keyword "radiale.sensor" "Sensor 1")]
        (is
          (= {:name     "Sensor 1"
              :uniqueid "uid-s10"
              :service  "sensors" ; API type (plural) for Deconz REST API
              :id       "10"}
             (get-in @state* [:radiale.deconz ident :props])))
        (is
          (= {:open true} (get-in @state* [:radiale.deconz ident :state])))
        (is
          (= ident (get-in @state* [:radiale.deconz :by-uniqueid "uid-s10"])))))))

;; --- Unit tests for state-change ---
(deftest state-change-test
  (let [bus (chan 10)
        state* (atom
                 {:radiale.deconz {:by-uniqueid          {"uid-l1" :radiale.light/Light1}
                                   :radiale.light/Light1 {:props {:name     "Light1"
                                                                  :uniqueid "uid-l1"}
                                                          :state {:on false}}}})
        service-type-namespaces {:lights :radiale.light}
        original-message {:some "data"}]

    (testing "Event contains new state"
      (let [event {:e        "changed"
                   :r        "lights"
                   :id       "1"
                   :uniqueid "uid-l1"
                   :state    {:on  true
                              :bri 200}}]
        (deconz/state-change service-type-namespaces event bus state* original-message)
        (is
          (= {:on  true
              :bri 200}
             (get-in @state* [:radiale.deconz :radiale.light/Light1 :state])))
        (let [bus-msg (poll! bus)]
          (is
            (some? bus-msg))
          (is
            (= :radiale.light/Light1 (::deconz/ident bus-msg)))
          (is
            (= {:on  true
                :bri 200}
               (::deconz/state bus-msg))))))

    (testing "Event contains new attr (no state)"
      (let [event {:e        "changed"
                   :r        "lights"
                   :id       "1"
                   :uniqueid "uid-l1"
                   :attr     {:reachable true}}]
        (deconz/state-change service-type-namespaces event bus state* original-message)
        (is
          (= {:name      "Light1"
              :uniqueid  "uid-l1"
              :reachable true} ; Merged
             (get-in @state* [:radiale.deconz :radiale.light/Light1 :props])))
        (is
          (nil? (poll! bus)))
        "No message should be sent for attr-only changes"))

    (testing "Event for unknown uniqueid"
      (let [initial-state @state*
            event         {:e        "changed"
                           :r        "lights"
                           :id       "2"
                           :uniqueid "uid-unknown"
                           :state    {:on true}}]
        (deconz/state-change service-type-namespaces event bus state* original-message)
        (is
          (= initial-state @state*))
        "State should not change"
        (is
          (nil? (poll! bus)))))

    (testing "Event for unknown resource type 'r'"
      (let [initial-state @state*
            event         {:e        "changed"
                           :r        "unknown_resource"
                           :id       "1"
                           :uniqueid "uid-l1"
                           :state    {:on true}}]
        (deconz/state-change service-type-namespaces event bus state* original-message)
        (is
          (= initial-state @state*))
        "State should not change"
        (is
          (nil? (poll! bus)))))
    (close! bus)))

;; --- Unit tests for discover ---
(deftest discover-test
  (let [bus (chan 10)
        state* (atom {})
        discover-opts {::deconz/api-key                 "testkey"
                       ::deconz/host                    "deconz.host"
                       ::deconz/service-type-namespaces {}}
        listen-deconz-calls (atom [])
        store-config-calls (atom [])
        state-change-calls (atom [])]

    (let [mock-listen-deconz (fn [opts cb]
                               (swap! listen-deconz-calls conj
                                 {:opts opts
                                  :cb   cb}))]
      (with-redefs [deconz/store-deconz-config (fn [stn s* res]
                                                 (swap! store-config-calls conj
                                                   {:stn stn
                                                    :s*  s*
                                                    :res res}))
                    deconz/state-change        (fn [stn e b s* m]
                                                 (swap! state-change-calls conj
                                                   {:stn stn
                                                    :e   e
                                                    :b   b
                                                    :s*  s*
                                                    :m   m}))]

        (deconz/discover {:listen-deconz mock-listen-deconz} bus state* discover-opts)

        (is
          (= 1 (count @listen-deconz-calls)))
        (let [{:keys [opts cb]} (first @listen-deconz-calls)]
          (is
            (= {:api-key "testkey"
                :host    "deconz.host"}
               opts))

          (testing "Callback with initial config"
            (let [initial-config-map {:lights {"1" {:name "L1"}}}]
              (cb {:radialeconfig initial-config-map})
              (is
                (= 1 (count @store-config-calls)))
              (is
                (= initial-config-map (:res (first @store-config-calls))))
              (is
                (empty? @state-change-calls))))

          (testing "Callback with state change event"
            (let [event-map {:e "changed"
                             :r "sensors"}]
              (cb event-map)
              (is
                (= 1 (count @state-change-calls)))
              (is
                (= event-map (:e (first @state-change-calls))))
              (is
                (= (dissoc discover-opts ::deconz/api-key ::deconz/host) (:m (first @state-change-calls)))))))))
    (close! bus)))

;; --- Unit tests for get-config ---
(deftest get-config-test
  (let [state* (atom
                 {:radiale.deconz {:my-light {:props {:id      "light-id-01"
                                                      :service "lights"}}}})]
    (is
      (= {:id   "light-id-01"
          :type "lights"}
         (deconz/get-config state* :my-light)))))

;; --- Integration test: store-deconz-config -> get-config -> put ---
;; This test verifies the full flow works correctly with the Deconz API
(deftest store-config-then-put-uses-correct-api-type-test
  (testing "When a light is discovered and then controlled, the API type should be 'lights' (plural)"
    (let [state*        (atom {})
          bus           (chan 10)
          ;; This mimics the actual config used in production
          service-type-namespaces {:lights  :light
                                   :sensors :sensor
                                   :groups  :group}
          ;; Simulated Deconz config response
          config-result {"lights" {"9" {:name     "bed1-left"
                                        :uniqueid "uid-bed1-left"
                                        :state    {:on  false
                                                   :bri 0}}}}
          put-deconz-calls (atom [])]

      ;; Step 1: Store the config (this happens on startup when Deconz is discovered)
      (deconz/store-deconz-config service-type-namespaces state* config-result)

      ;; Verify the light was stored
      (is
        (some? (get-in @state* [:radiale.deconz :light/bed1-left]))
        "Light should be stored with namespace :light")

      ;; Step 2: Now try to PUT to this light (this happens when a schedule fires)
      (let [mocked-radiale-map {:put-deconz (fn [cmd cb]
                                              (swap! put-deconz-calls conj {:cmd cmd})
                                              (cb {:success true}))}
            message {::deconz/ident :light/bed1-left
                     ::deconz/state {:on  true
                                     :bri 254}}]

        (deconz/put mocked-radiale-map bus state* message)

        ;; Verify the PUT was called with the correct API type
        (is
          (= 1 (count @put-deconz-calls))
          "put-deconz should be called once")

        (let [{:keys [cmd]} (first @put-deconz-calls)]
          (is
            (= "9" (:id cmd))
            "Device ID should be '9'")
          ;; THIS IS THE KEY ASSERTION - the type must be "lights" (plural) for the Deconz API
          (is
            (= "lights" (:type cmd))
            "API type MUST be 'lights' (plural) not 'light' (singular) - Deconz API requires plural form")
          (is
            (= {:on  true
                :bri 254}
               (:state cmd))
            "State should match")))

      (close! bus))))

;; --- Unit tests for put ---
(deftest put-test
  (let [bus (chan 10)
        state* (atom {})
        put-deconz-calls (atom [])
        get-config-calls (atom [])
        original-message {::deconz/ident :my-light
                          ::deconz/state {:on true}}
        mocked-radiale-map {:put-deconz (fn [cmd cb]
                                          (swap! put-deconz-calls conj
                                            {:cmd cmd
                                             :cb  cb})
                                          (cb {:success true}))}] ; Mock pod fn

    (with-redefs [deconz/get-config (fn [s id]
                                      (swap! get-config-calls conj
                                        {:s  s
                                         :id id})
                                      {:id   (name id)
                                       :type "lights"})]

      (testing "Single ident and single state"
        (reset! put-deconz-calls [])
        (reset! get-config-calls [])
        (deconz/put mocked-radiale-map bus state* original-message)

        (is
          (= 1 (count @get-config-calls)))
        (is
          (= :my-light (:id (first @get-config-calls))))

        (is
          (= 1 (count @put-deconz-calls)))
        (let [{:keys [cmd cb]} (first @put-deconz-calls)]
          (is
            (= {:id    "my-light"
                :type  "lights"
                :state {:on true}}
               cmd))
          (is
            (fn? cb))) ; Callback is a function

        (let [bus-msg (poll! bus)]
          (is
            (= original-message bus-msg))))

      (testing "Sequence of idents and single state"
        (reset! put-deconz-calls [])
        (reset! get-config-calls [])
        (let [message {::deconz/ident [:light1 :light2]
                       ::deconz/state {:bri 128}}]
          (deconz/put mocked-radiale-map bus state* message)
          (is
            (= 2 (count @get-config-calls)))
          (is
            (= 2 (count @put-deconz-calls)))
          (is
            (= {:id    "light1"
                :type  "lights"
                :state {:bri 128}}
               (:cmd (first @put-deconz-calls))))
          (is
            (= {:id    "light2"
                :type  "lights"
                :state {:bri 128}}
               (:cmd (second @put-deconz-calls))))
          (is
            (= message (poll! bus)))))

      (testing "Single ident and sequence of states"
        (reset! put-deconz-calls [])
        (reset! get-config-calls [])
        (let [message {::deconz/ident :light3
                       ::deconz/state [{:on true} {:on false}]}]
          (deconz/put mocked-radiale-map bus state* message)
          (is
            (= 2 (count @get-config-calls))) ; get-config is called for each state
          (is
            (= 2 (count @put-deconz-calls)))
          (is
            (= {:id    "light3"
                :type  "lights"
                :state {:on true}}
               (:cmd (first @put-deconz-calls))))
          (is
            (= {:id    "light3"
                :type  "lights"
                :state {:on false}}
               (:cmd (second @put-deconz-calls))))
          (is
            (= message (poll! bus)))))

      (testing "Sequence of idents and sequence of states"
        (reset! put-deconz-calls [])
        (reset! get-config-calls [])
        (let [message {::deconz/ident [:l4 :l5]
                       ::deconz/state [{:s1 true} {:s2 false}]}]
          (deconz/put mocked-radiale-map bus state* message)
          (is
            (= 4 (count @get-config-calls))) ; 2 idents * 2 states
          (is
            (= 4 (count @put-deconz-calls)))
          (is
            (= {:id    "l4"
                :type  "lights"
                :state {:s1 true}}
               (:cmd (nth @put-deconz-calls 0))))
          (is
            (= {:id    "l4"
                :type  "lights"
                :state {:s2 false}}
               (:cmd (nth @put-deconz-calls 1))))
          (is
            (= {:id    "l5"
                :type  "lights"
                :state {:s1 true}}
               (:cmd (nth @put-deconz-calls 2))))
          (is
            (= {:id    "l5"
                :type  "lights"
                :state {:s2 false}}
               (:cmd (nth @put-deconz-calls 3))))
          (is
            (= message (poll! bus))))))
    (close! bus)))


;; --- Unit tests for on-press ---
(deftest on-press-test
  (testing "fires when buttonevent matches via state lookup"
    (let [state*  (atom {:radiale.deconz {:sensor/test-switch {:state {:buttonevent 1002}}}})
          handler (deconz/on-press
                    :sensor/test-switch
                    1002
                    (fn [_]
                      :fired))
          msg     {::state/domain :radiale.deconz
                   ::state/ident  :sensor/test-switch
                   ::state/prop   :state
                   ::state/now    {:lastupdated "T1"
                                   :buttonevent 1002}}]
      (is
        (= :fired (handler state* msg)))))

  ;; Regression test for the user's log at 16:22:12: a second 1002 press leaves
  ;; :buttonevent unchanged so clojure.data/diff strips it from ::now. The
  ;; handler must read :buttonevent from @state* to still recognise the press.
  (testing "CRITICAL regression — fires when :buttonevent absent from :now"
    (let [state*  (atom {:radiale.deconz {:sensor/test-switch {:state {:buttonevent 1002}}}})
          handler (deconz/on-press
                    :sensor/test-switch
                    1002
                    (fn [_]
                      :fired))
          msg     {::state/domain :radiale.deconz
                   ::state/ident  :sensor/test-switch
                   ::state/prop   :state
                   ::state/now    {:lastupdated "T2"}}]
      (is
        (= :fired (handler state* msg))
        "must fire when buttonevent only present in @state*, not in :now diff")))

  (testing "no-op when buttonevent doesn't match press-code"
    (let [state*  (atom {:radiale.deconz {:sensor/test-switch {:state {:buttonevent 2002}}}})
          handler (deconz/on-press
                    :sensor/test-switch
                    1002
                    (fn [_]
                      :fired))
          msg     {::state/domain :radiale.deconz
                   ::state/ident  :sensor/test-switch
                   ::state/prop   :state
                   ::state/now    {:lastupdated "T1"}}]
      (is
        (nil? (handler state* msg)))))

  (testing "no-op when :lastupdated absent from :now"
    (let [state*  (atom {:radiale.deconz {:sensor/test-switch {:state {:buttonevent 1002}}}})
          handler (deconz/on-press
                    :sensor/test-switch
                    1002
                    (fn [_]
                      :fired))
          msg     {::state/domain :radiale.deconz
                   ::state/ident  :sensor/test-switch
                   ::state/prop   :state
                   ::state/now    {:other "thing"}}]
      (is
        (nil? (handler state* msg)))))

  (testing "no-op for wrong domain"
    (let [state*  (atom {:radiale.deconz {:sensor/test-switch {:state {:buttonevent 1002}}}})
          handler (deconz/on-press
                    :sensor/test-switch
                    1002
                    (fn [_]
                      :fired))
          msg     {::state/domain :radiale.esp
                   ::state/ident  :sensor/test-switch
                   ::state/prop   :state
                   ::state/now    {:lastupdated "T1"}}]
      (is
        (nil? (handler state* msg)))))

  (testing "no-op for wrong sensor ident"
    (let [state*  (atom {:radiale.deconz {:sensor/test-switch {:state {:buttonevent 1002}}}})
          handler (deconz/on-press
                    :sensor/test-switch
                    1002
                    (fn [_]
                      :fired))
          msg     {::state/domain :radiale.deconz
                   ::state/ident  :sensor/other-switch
                   ::state/prop   :state
                   ::state/now    {:lastupdated "T1"}}]
      (is
        (nil? (handler state* msg)))))

  (testing "no-op for wrong prop"
    (let [state*  (atom {:radiale.deconz {:sensor/test-switch {:state {:buttonevent 1002}}}})
          handler (deconz/on-press
                    :sensor/test-switch
                    1002
                    (fn [_]
                      :fired))
          msg     {::state/domain :radiale.deconz
                   ::state/ident  :sensor/test-switch
                   ::state/prop   :props
                   ::state/now    {:lastupdated "T1"}}]
      (is
        (nil? (handler state* msg))))))
