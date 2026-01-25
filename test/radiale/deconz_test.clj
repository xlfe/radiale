(ns radiale.deconz-test
  (:require
    [clojure.core.async :as async :refer [<!! >!! alts!! chan close! poll! timeout]]
    [clojure.test :refer :all]
    [radiale.deconz :as deconz]
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
              :service  "radiale.light"
              :id       "1"}
             (get-in @state* [ident :props])))
        (is
          (= {:on false} (get-in @state* [ident :state])))
        (is
          (= ident (get-in @state* ["uid-l1"])))))

    (testing "Light 2 config"
      (let [ident (keyword "radiale.light" "Light 2")]
        (is
          (= {:name     "Light 2"
              :uniqueid "uid-l2"
              :service  "radiale.light"
              :id       "2"}
             (get-in @state* [ident :props])))
        (is
          (= {:on true} (get-in @state* [ident :state])))
        (is
          (= ident (get-in @state* ["uid-l2"])))))

    (testing "Sensor 10 config"
      (let [ident (keyword "radiale.sensor" "Sensor 1")]
        (is
          (= {:name     "Sensor 1"
              :uniqueid "uid-s10"
              :service  "radiale.sensor"
              :id       "10"}
             (get-in @state* [ident :props])))
        (is
          (= {:open true} (get-in @state* [ident :state])))
        (is
          (= ident (get-in @state* ["uid-s10"])))))))

;; --- Unit tests for state-change ---
(deftest state-change-test
  (let [bus (chan 10)
        state* (atom
                 {"uid-l1"              :radiale.light/Light1
                  :radiale.light/Light1 {:props {:name     "Light1"
                                                 :uniqueid "uid-l1"}
                                         :state {:on false}}})
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
             (get-in @state* [:radiale.light/Light1 :state])))
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
             (get-in @state* [:radiale.light/Light1 :props])))
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
                 {:my-light {:props {:id      "light-id-01"
                                     :service "lights"}}})]
    (is
      (= {:id   "light-id-01"
          :type "lights"}
         (deconz/get-config state* :my-light)))))

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
