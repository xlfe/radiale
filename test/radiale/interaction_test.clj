(ns radiale.interaction-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [babashka.pods :as pods]
            ;; We need to refer to the actual Clojure wrapper functions for pod calls.
            ;; These are defined in `src/radiale/core.clj` as being required from `pod.xlfe.radiale`.
            ;; This implies there's a Clojure namespace `pod.xlfe.radiale` that provides these wrappers.
            ;; If these wrappers are generated or loaded dynamically by `(require '[pod.xlfe.radiale :as radiale])`
            ;; and are not in a static .clj file under src/, we need to ensure they are loaded for the test.
            ;; The `(pods/load-pod ["./pod-xlfe-radiale.py"])` and `(require '[pod.xlfe.radiale :as radiale])`
            ;; in `radiale.core` make these functions available.
            ;; For testing, we can either:
            ;; 1. Ensure `radiale.core` is loaded so `pod.xlfe.radiale` alias and its vars are available.
            ;; 2. Or, if the wrappers are simple enough, replicate a similar structure for test purposes
            ;;    if loading them directly is problematic in the test environment.
            ;; Let's assume they are available after loading radiale.core or directly.
            ;; We will refer to them via `pod.xlfe.radiale/sleep-ms` etc.
            [pod.xlfe.radiale :as pod-clj-wrappers]
            [radiale.core :as rc-core-loader] ; Ensure radiale.core (and thus load-pod) is loaded
            [taoensso.timbre :as timbre]))


;; Fixture to silence Timbre logging during tests
(defn silence-logging-fixture [f]
  (let [original-config timbre/*config*]
    (timbre/set-config! {:min-level :fatal})
    (f)
    (timbre/set-config! original-config)))

(use-fixtures :once silence-logging-fixture)


;; --- Test for simple pod invocation (sleep-ms) ---
(deftest sleep-ms-invocation-test
  (let [invoke-args-atom (atom nil)
        result-prom (promise)
        sleep-duration-ms 100
        mock-id "sleep-test-id-123"] ; Example ID that might be part of opts or generated

    ;; The actual pod.xlfe.radiale/sleep-ms function is a wrapper around pods/invoke.
    ;; (defn sleep-ms ([opts cb _]) ...)
    ;; We need to call this wrapper.

    (with-redefs [babashka.pods/invoke
                  (fn [pod-spec var-fqn args opts-map]
                    (reset! invoke-args-atom {:pod-spec pod-spec
                                              :var-fqn var-fqn
                                              :args args
                                              :opts-map opts-map})
                    ;; Simulate the pod succeeding and calling the :success handler
                    (let [success-handler (get-in opts-map [:handlers :success])]
                      (assert (fn? success-handler) "Success handler must be a function")
                      ;; The actual pod would return a bencoded map, which gets translated.
                      ;; The Python pod's RadialePod.invoke for sleep-ms sends:
                      ;; self.out.write_msg(id=id, status="done", data=opts)
                      ;; where opts is the original sleep duration.
                      ;; The event map received by the success handler would look like:
                      ;; {:value "500", :id "...", :status ["done"], :opts original-opts-map, :fn-name :sleep-ms}
                      ;; The value is JSON encoded string of the data.
                      (success-handler {:value (str sleep-duration-ms) ; Pod sends data as a JSON string
                                        :id mock-id
                                        :status ["done"]
                                        ;; :opts and :fn-name are added by the wrapper's success handler itself
                                        })))])

      ;; Call the Clojure wrapper function for sleep-ms
      ;; pod-clj-wrappers/sleep-ms expects opts and a callback.
      ;; The callback will receive an event map.
      (pod-clj-wrappers/sleep-ms
        {"ms" sleep-duration-ms} ; This is the 'opts' map passed to sleep-ms
        (fn [event] (deliver result-prom event))) ; This is the 'cb'

      ;; Verify babashka.pods/invoke was called correctly
      (is (some? @invoke-args-atom) "babashka.pods/invoke was not called")
      (is (= "pod.xlfe.radiale" (:pod-spec @invoke-args-atom)))
      (is (= 'pod.xlfe.radiale/sleep-ms* (:var-fqn @invoke-args-atom))) ; Note the '*'
      ;; The 'args' passed to invoke is a vector containing the options map
      (is (= [{"ms" sleep-duration-ms}] (:args @invoke-args-atom)))
      (is (map? (:opts-map @invoke-args-atom)))
      (is (fn? (get-in @invoke-args-atom [:opts-map :handlers :success])))

      ;; Verify the success handler was called by our mock invoke, and the wrapper processed it
      (let [cb-result (deref result-prom 100 :timeout)]
        (is (not= :timeout cb-result) "Callback was not invoked in time")
        ;; The callback to sleep-ms receives an event map that the wrapper augments.
        ;; The wrapper's success handler: (fn [event] (cb (assoc event :opts (dissoc opts :password :api-key) :fn-name :sleep-ms)))
        (is (= (str sleep-duration-ms) (:value cb-result)))
        (is (= mock-id (:id cb-result)))
        (is (= ["done"] (:status cb-result)))
        (is (= :sleep-ms (:fn-name cb-result))) ; Added by the wrapper
        (is (= {"ms" sleep-duration-ms} (:opts cb-result))))))) ; Added by the wrapper (original opts)


;; --- Test for an operation that expects structured data (e.g., astral-now) ---
(deftest astral-now-invocation-test
  (let [invoke-args-atom (atom nil)
        result-prom (promise)
        astral-opts {"city" "London" "tz" "Europe/London"}
        mock-pod-response-data {:period "day" :city "London"} ; Data pod would return
        mock-id "astral-test-id-456"]

    (with-redefs [babashka.pods/invoke
                  (fn [pod-spec var-fqn args opts-map]
                    (reset! invoke-args-atom {:pod-spec pod-spec, :var-fqn var-fqn, :args args, :opts-map opts-map})
                    (let [success-handler (get-in opts-map [:handlers :success])]
                      (assert (fn? success-handler))
                      ;; Python pod's RadialePod.invoke for astral-now sends:
                      ;; self.out.write_msg(id=id, status="done", data=schedule.astral_now(**opts))
                      ;; data is the result of schedule.astral_now (e.g., "day" or a dict)
                      ;; The value is JSON encoded string of this data.
                      (success-handler {:value (clojure.data.json/write-str mock-pod-response-data) ; Use clojure.data.json
                                        :id mock-id
                                        :status ["done"]})))]
                  ;; We need a JSON library like cheshire if it's used by the pod success handler
                  ;; The pod.py uses json.dumps. Clojure side might use cheshire or clojure.data.json
                  ;; The wrapper's success handler does `(cb (assoc event ...))`. It doesn't parse JSON.
                  ;; It's the responsibility of the final callback user to parse :value if it's JSON.
                  ;; For this test, let's assume the callback `cb` passed to `astral-now`
                  ;; expects the raw event and might parse `:value` itself if needed.
                  ;; The current `make_clj_code` in `pod.py` does not auto-parse JSON in the success handler.
                  ;; It passes the event map as is. The `:value` field is a string.

      (pod-clj-wrappers/astral-now
        astral-opts
        (fn [event] (deliver result-prom event)))

      (is (some? @invoke-args-atom))
      (is (= "pod.xlfe.radiale" (:pod-spec @invoke-args-atom)))
      (is (= 'pod.xlfe.radiale/astral-now* (:var-fqn @invoke-args-atom)))
      (is (= [astral-opts] (:args @invoke-args-atom)))

      (let [cb-result (deref result-prom 100 :timeout)]
        (is (not= :timeout cb-result))
        (is (= (clojure.data.json/write-str mock-pod-response-data) (:value cb-result)))
        (is (= mock-id (:id cb-result)))
        (is (= ["done"] (:status cb-result)))
        (is (= :astral-now (:fn-name cb-result))) ; Added by wrapper
        (is (= astral-opts (:opts cb-result))))))) ; Added by wrapper

;; Note: For these tests to run, the `pod.xlfe.radiale` namespace and its functions
;; (like `sleep-ms`, `astral-now`) must be loaded. This typically happens when
;; `radiale.core` is loaded, which executes `(pods/load-pod ["./pod-xlfe-radiale.py"])`
;; and `(require '[pod.xlfe.radiale :as radiale])`.
;; If `radiale.core` is not loaded as part of the test setup, these tests might fail
;; because `pod.xlfe.radiale/sleep-ms` var won't be found.
;; A simple way to ensure this is to add `(:require [radiale.core])` in the ns declaration,
;; though it might pull in more than needed. Or, ensure test runner loads `radiale.core` first.
;; For now, the tests assume `pod.xlfe.radiale` vars are resolvable.

;; We need a JSON library for the astral-now test's mock response.
;; Add cheshire to :test alias in deps.edn or use clojure.data.json if available by default.
;; Let's use clojure.data.json for now to avoid adding new deps if not strictly needed by main code.
;; Re-adjusting astral-now-invocation-test to use clojure.data.json if available,
;; or just pass a simple string if the pod returns a simple string for astral-now.
;; The python `astral_now` in `radiale/schedule.py` returns a string like "day".
(deftest astral-now-invocation-simple-string-response-test
  (let [invoke-args-atom (atom nil)
        result-prom (promise)
        astral-opts {"city" "London"}
        mock-pod-response-str "day" ; schedule.astral_now in Python returns a string
        mock-id "astral-test-id-789"]

    (with-redefs [babashka.pods/invoke
                  (fn [pod-spec var-fqn args opts-map]
                    (reset! invoke-args-atom {:pod-spec pod-spec, :var-fqn var-fqn, :args args, :opts-map opts-map})
                    (let [success-handler (get-in opts-map [:handlers :success])]
                      (success-handler {:value (clojure.data.json/write-str mock-pod-response-str) ; Pod sends JSON string
                                        :id mock-id
                                        :status ["done"]})))]

      (pod-clj-wrappers/astral-now
        astral-opts
        (fn [event] (deliver result-prom event)))

      (is (some? @invoke-args-atom))
      (is (= 'pod.xlfe.radiale/astral-now* (:var-fqn @invoke-args-atom)))
      (is (= [astral-opts] (:args @invoke-args-atom)))

      (let [cb-result (deref result-prom 100 :timeout)]
        (is (not= :timeout cb-result))
        ;; The :value is a JSON string. The client callback might parse it or use as is.
        ;; The wrapper does not parse it.
        (is (= (clojure.data.json/write-str mock-pod-response-str) (:value cb-result)))
        (is (= :astral-now (:fn-name cb-result)))
        (is (= astral-opts (:opts cb-result)))))))

;; Final check on deps for clojure.data.json - it's part of Clojure itself.
;; Cheshire was an example, clojure.data.json is fine.
;; The python `json.dumps(data)` in `pod.py` is the key.
;; So, `(clojure.data.json/write-str "day")` results in `"\"day\""`.
;; If python `json.dumps("day")` is `"\"day\""`.
;; If python `json.dumps({"period": "day"})` is `"{\"period\": \"day\"}"`.
;; The python `astral_now` returns a string. So `json.dumps("day")` is correct.
;; The clojure callback receives `{:value "\"day\"" ...}`.
