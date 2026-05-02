(ns radiale.watchdog-test
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer :all]
    [radiale.core :as rc]
    [radiale.deconz :as deconz]
    [radiale.schedule :as schedule]
    [radiale.watchdog :as watchdog]
    [taoensso.timbre :as timbre]))


(defn silence-logging-fixture
  [f]
  (let [original timbre/*config*]
    (timbre/set-config! {:min-level :error})
    (f)
    (timbre/set-config! original)))


(use-fixtures :once silence-logging-fixture)


(def ^:private wd-key ::test-watchdog)
(def ^:private lights [:light/a :light/b])
(def ^:private on-cmd
  {::rc/fn        deconz/put
   ::deconz/ident lights
   ::deconz/state {:on  true
                   :bri 254}})
(def ^:private off-cmd
  {::rc/fn        deconz/put
   ::deconz/ident lights
   ::deconz/state {:on false}})

(def ^:private cfg
  {::watchdog/key     wd-key
   ::watchdog/step-ms 300000
   ::watchdog/max-ms  900000
   ::watchdog/bump-ms 300000
   ::watchdog/on-cmd  on-cmd
   ::watchdog/off-cmd off-cmd})


;; ---- compute-extend-target ----

(deftest compute-extend-target-test
  (testing "cold start (nil) returns now + step"
    (is
      (= 300000 (watchdog/compute-extend-target 0 nil 300000 900000))))

  (testing "cold start (deadline = now)"
    (is
      (= 300000 (watchdog/compute-extend-target 0 0 300000 900000))))

  (testing "cold start (deadline past)"
    (is
      (= 300000 (watchdog/compute-extend-target 0 -10000 300000 900000))))

  (testing "1 extension active extends to 2x step"
    (is
      (= 600000 (watchdog/compute-extend-target 0 300000 300000 900000))))

  (testing "2 extensions active hits cap"
    (is
      (= 900000 (watchdog/compute-extend-target 0 600000 300000 900000))))

  (testing "3+ extensions at cap stays at cap"
    (is
      (= 900000 (watchdog/compute-extend-target 0 900000 300000 900000)))
    (is
      (= 900000 (watchdog/compute-extend-target 0 1200000 300000 900000))))

  (testing "mid-window under cap"
    (is
      (= 780000 (watchdog/compute-extend-target 0 480000 300000 900000))))

  (testing "mid-window crosses cap"
    (is
      (= 900000 (watchdog/compute-extend-target 0 720000 300000 900000)))))


;; ---- compute-bump-target ----

(deftest compute-bump-target-test
  (testing "cold (nil) returns now + bump"
    (is
      (= 300000 (watchdog/compute-bump-target 0 nil 300000))))

  (testing "deadline near future extends"
    (is
      (= 300000 (watchdog/compute-bump-target 0 120000 300000))))

  (testing "deadline far future does not shorten"
    (is
      (= 600000 (watchdog/compute-bump-target 0 600000 300000))))

  (testing "deadline at boundary returns same"
    (is
      (= 300000 (watchdog/compute-bump-target 0 300000 300000)))))


;; ---- extend! ----

(deftest extend!-test
  (testing "cold call sets state and returns [on schedule]"
    (let [state*     (atom {})
          [on sched] (watchdog/extend! state* cfg 0)]
      (is
        (= 300000 (get-in @state* (watchdog/state-path wd-key))))
      (is
        (= on-cmd on))
      (is
        (= schedule/after
           (::rc/fn sched)))
      (is
        (= 300 (::schedule/seconds sched)))
      (is
        (= wd-key (::schedule/at-most-once sched)))
      (is
        (= off-cmd (::rc/then sched)))))

  (testing "3x calls cap deadline at max"
    (let [state* (atom {})]
      (watchdog/extend! state* cfg 0)
      (watchdog/extend! state* cfg 0)
      (let [[_ sched] (watchdog/extend! state* cfg 0)]
        (is
          (= 900000 (get-in @state* (watchdog/state-path wd-key))))
        (is
          (= 900 (::schedule/seconds sched))))))

  (testing "extend after natural expiry resets to step"
    (let [state* (atom {})]
      (swap! state* assoc-in (watchdog/state-path wd-key) -10000)
      (let [[_ sched] (watchdog/extend! state* cfg 0)]
        (is
          (= 300000 (get-in @state* (watchdog/state-path wd-key))))
        (is
          (= 300 (::schedule/seconds sched))))))

  (testing "seconds rounding truncates sub-second (not rounds)"
    (let [state* (atom {})]
      (swap! state* assoc-in (watchdog/state-path wd-key) 4999)
      ;; base = 4999, target = 4999 + 300000 = 304999
      ;; seconds = (long (/ 304999 1000)) = 304
      (let [[_ sched] (watchdog/extend! state* cfg 0)]
        (is
          (= 304999 (get-in @state* (watchdog/state-path wd-key))))
        (is
          (= 304 (::schedule/seconds sched))))))

  (testing "on-cmd may itself be a sequence — flattened into result"
    (let [state*    (atom {})
          extra-on  {::rc/fn        deconz/put
                     ::deconz/ident :light/x
                     ::deconz/state {:on true}}
          cfg-multi (assoc cfg ::watchdog/on-cmd [on-cmd extra-on])
          result    (watchdog/extend! state* cfg-multi 0)]
      (is
        (= 3 (count result)))
      (is
        (= on-cmd (first result)))
      (is
        (= extra-on (second result)))
      (is
        (= schedule/after
           (::rc/fn (last result)))))))


;; ---- clear! ----

(deftest clear!-test
  (testing "clears state and emits [cancel off]"
    (let [state* (atom {})]
      (swap! state* assoc-in (watchdog/state-path wd-key) 999)
      (let [[cancel off] (watchdog/clear! state* cfg)]
        (is
          (nil? (get-in @state* (watchdog/state-path wd-key))))
        (is
          (= schedule/cancel
             (::rc/fn cancel)))
        (is
          (= wd-key (::schedule/at-most-once cancel)))
        (is
          (= off-cmd off)))))

  (testing "idempotent when nothing pending"
    (let [state*       (atom {})
          [cancel off] (watchdog/clear! state* cfg)]
      (is
        (nil? (get-in @state* (watchdog/state-path wd-key))))
      (is
        (= schedule/cancel
           (::rc/fn cancel)))
      (is
        (= off-cmd off)))))


;; ---- bump! ----

(deftest bump!-test
  (testing "cold (nil) returns commands and sets state"
    (let [state*     (atom {})
          [on sched] (watchdog/bump! state* cfg 0)]
      (is
        (= 300000 (get-in @state* (watchdog/state-path wd-key))))
      (is
        (= on-cmd on))
      (is
        (= 300 (::schedule/seconds sched)))))

  (testing "extends when target > current"
    (let [state* (atom {})]
      (swap! state* assoc-in (watchdog/state-path wd-key) 120000)
      (let [[_ sched] (watchdog/bump! state* cfg 0)]
        (is
          (= 300000 (get-in @state* (watchdog/state-path wd-key))))
        (is
          (= 300 (::schedule/seconds sched))))))

  (testing "no-op when active long window (returns nil, leaves state alone)"
    (let [state* (atom {})]
      (swap! state* assoc-in (watchdog/state-path wd-key) 600000)
      (is
        (nil? (watchdog/bump! state* cfg 0)))
      (is
        (= 600000 (get-in @state* (watchdog/state-path wd-key)))))))


;; ---- bump-cmd rc-fn ----

(deftest bump-cmd-test
  (testing "pushes commands when extending"
    (let [state*    (atom {})
          send-chan (async/chan 10)]
      (watchdog/bump-cmd nil send-chan state* cfg)
      (let [first-cmd  (async/poll! send-chan)
            second-cmd (async/poll! send-chan)
            third-cmd  (async/poll! send-chan)]
        (is
          (= deconz/put
             (::rc/fn first-cmd)))
        (is
          (= schedule/after
             (::rc/fn second-cmd)))
        (is
          (nil? third-cmd)))
      (async/close! send-chan)))

  (testing "pushes nothing when not extending"
    (let [state*    (atom {})
          send-chan (async/chan 10)]
      (swap! state* assoc-in (watchdog/state-path wd-key) (+ (System/currentTimeMillis) 600000))
      (watchdog/bump-cmd nil send-chan state* cfg)
      (is
        (nil? (async/poll! send-chan)))
      (async/close! send-chan))))


;; ---- end-to-end sequencing ----

(deftest sequence-test
  (testing "extend, expire, extend again — fresh window"
    (let [state* (atom {})]
      (watchdog/extend! state* cfg 0)
      (is
        (= 300000 (get-in @state* (watchdog/state-path wd-key))))
      ;; Time advances past the schedule. State still holds stale 300000.
      (let [[_ sched] (watchdog/extend! state* cfg 600000)]
        ;; cur=300000, base = max(600000, 300000) = 600000
        ;; target = min(600000+900000, 600000+300000) = 900000
        ;; seconds = 300 (a fresh 5min window, not a phantom 0 from stale state)
        (is
          (= 900000 (get-in @state* (watchdog/state-path wd-key))))
        (is
          (= 300 (::schedule/seconds sched))))))

  (testing "extend then clear empties state"
    (let [state* (atom {})]
      (watchdog/extend! state* cfg 0)
      (watchdog/clear! state* cfg)
      (is
        (nil? (get-in @state* (watchdog/state-path wd-key))))))

  (testing "two independent watchdogs share state atom but don't interfere"
    (let [state* (atom {})
          cfg-a  (assoc cfg ::watchdog/key ::a)
          cfg-b  (assoc cfg ::watchdog/key ::b)]
      (watchdog/extend! state* cfg-a 0)
      (watchdog/extend! state* cfg-b 0)
      (watchdog/clear! state* cfg-a)
      (is
        (nil? (get-in @state* (watchdog/state-path ::a))))
      (is
        (= 300000 (get-in @state* (watchdog/state-path ::b)))))))


;; ---- ticks (recurring display update) ----

(def ^:private tick-key ::test-tick)

(defn- mk-render
  "Test render fn: records every (state* minutes) call and returns a single
   marker command per call. Returns [render-fn calls-atom]."
  [marker]
  (let [calls (atom [])]
    [(fn [_state* minutes]
       (swap! calls conj minutes)
       {::marker marker
        ::min    minutes}) calls]))


(defn- ticks-cfg
  ([] (ticks-cfg {}))
  ([overrides]
   (merge
     {::watchdog/key       wd-key
      ::watchdog/ticks-key tick-key
      ::watchdog/render    (fn [_ m]
                             {::min m})}
     overrides)))


(deftest ticks-test
  (testing "active deadline far ahead pushes render + reschedule, ::rc/then is back-reference"
    (let [state*      (atom {})
          send-chan   (async/chan 10)
          [render xs] (mk-render :outdoor)
          ;; deadline = now + 600s (10min)
          now         (System/currentTimeMillis)
          cfg         (ticks-cfg {::watchdog/render render})]
      (swap! state* assoc-in (watchdog/state-path wd-key) (+ now 600000))
      (watchdog/ticks nil send-chan state* cfg)
      (let [msg1 (async/poll! send-chan)
            msg2 (async/poll! send-chan)]
        (is
          (= 1 (count @xs))
          "render called once")
        (is
          (= 10 (first @xs))
          "minutes computed correctly")
        (is
          (= :outdoor (::marker msg1))
          "render output pushed first")
        (is
          (= schedule/after
             (::rc/fn msg2))
          "schedule pushed second")
        (is
          (= 60 (::schedule/seconds msg2))
          "default 60s tick interval")
        (is
          (= tick-key (::schedule/at-most-once msg2))
          "at-most-once = ticks-key")
        ;; Critical: ::rc/then must self-reference ticks with the same cfg so
        ;; the chain re-enters ticks cleanly each tick.
        (is
          (= (assoc cfg ::rc/fn watchdog/ticks) (::rc/then msg2))
          "::rc/then = (assoc cfg ::rc/fn ticks)")
        (is
          (nil? (async/poll! send-chan))
          "no more messages"))
      (async/close! send-chan)))

  (testing "boundary 90s ahead → minutes = 2 (ceil 1.5)"
    (let [state*      (atom {})
          send-chan   (async/chan 10)
          [render xs] (mk-render :x)
          cfg         (ticks-cfg {::watchdog/render render})
          now         (System/currentTimeMillis)]
      (swap! state* assoc-in (watchdog/state-path wd-key) (+ now 90000))
      (watchdog/ticks nil send-chan state* cfg)
      (is
        (= 2 (first @xs)))
      (async/close! send-chan)))

  (testing "boundary 60s ahead → minutes = 1"
    (let [state*      (atom {})
          send-chan   (async/chan 10)
          [render xs] (mk-render :x)
          cfg         (ticks-cfg {::watchdog/render render})
          now         (System/currentTimeMillis)]
      (swap! state* assoc-in (watchdog/state-path wd-key) (+ now 60000))
      (watchdog/ticks nil send-chan state* cfg)
      (is
        (= 1 (first @xs)))
      (async/close! send-chan)))

  (testing "deadline = now (boundary) → render(nil), no schedule"
    (let [state*      (atom {})
          send-chan   (async/chan 10)
          [render xs] (mk-render :x)
          cfg         (ticks-cfg {::watchdog/render render})
          now         (System/currentTimeMillis)]
      (swap! state* assoc-in (watchdog/state-path wd-key) now)
      (watchdog/ticks nil send-chan state* cfg)
      (is
        (= [nil] @xs)
        "render called with nil minutes")
      (let [msg1 (async/poll! send-chan)
            msg2 (async/poll! send-chan)]
        (is
          (= :x (::marker msg1))
          "render output pushed")
        (is
          (nil? msg2)
          "no schedule pushed"))
      (async/close! send-chan)))

  (testing "deadline in past → render(nil), no schedule"
    (let [state*      (atom {})
          send-chan   (async/chan 10)
          [render xs] (mk-render :x)
          cfg         (ticks-cfg {::watchdog/render render})]
      (swap! state* assoc-in (watchdog/state-path wd-key) 1)
      (watchdog/ticks nil send-chan state* cfg)
      (is
        (= [nil] @xs))
      (async/poll! send-chan) ; consume the render output
      (is
        (nil? (async/poll! send-chan))
        "no schedule pushed")
      (async/close! send-chan)))

  (testing "deadline nil → render(nil), no schedule"
    (let [state*      (atom {})
          send-chan   (async/chan 10)
          [render xs] (mk-render :x)
          cfg         (ticks-cfg {::watchdog/render render})]
      (watchdog/ticks nil send-chan state* cfg)
      (is
        (= [nil] @xs))
      (async/poll! send-chan)
      (is
        (nil? (async/poll! send-chan))
        "no schedule pushed")
      (async/close! send-chan)))

  (testing "render returning a sequence pushes each command"
    (let [state*    (atom {})
          send-chan (async/chan 10)
          render    (fn [_ _]
                      [{::n 1} {::n 2} {::n 3}])
          cfg       (ticks-cfg {::watchdog/render render})
          now       (System/currentTimeMillis)]
      (swap! state* assoc-in (watchdog/state-path wd-key) (+ now 300000))
      (watchdog/ticks nil send-chan state* cfg)
      (is
        (= 1 (::n (async/poll! send-chan))))
      (is
        (= 2 (::n (async/poll! send-chan))))
      (is
        (= 3 (::n (async/poll! send-chan))))
      ;; Then the schedule
      (is
        (= schedule/after
           (::rc/fn (async/poll! send-chan))))
      (async/close! send-chan)))

  (testing "custom ::tick-seconds honored in schedule"
    (let [state*    (atom {})
          send-chan (async/chan 10)
          cfg       (ticks-cfg
                      {::watchdog/tick-seconds 30
                       ::watchdog/render       (fn [_ m]
                                                 {::min m})})
          now       (System/currentTimeMillis)]
      (swap! state* assoc-in (watchdog/state-path wd-key) (+ now 600000))
      (watchdog/ticks nil send-chan state* cfg)
      (async/poll! send-chan) ; render output
      (let [sched (async/poll! send-chan)]
        (is
          (= 30 (::schedule/seconds sched))))
      (async/close! send-chan))))
