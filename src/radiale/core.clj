(ns radiale.core
  (:require

    [babashka.pods :as pods]
    [clojure.core.async :as async]
    [clojure.core.async :as a]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [function?]]
    [radiale.esp :as esp]
    [radiale.state :as state]
    [radiale.watch :as watch]
    [taoensso.timbre :as timbre]))

;; Systemd journal priority levels (RFC 5424)
(def ^:private log-level->syslog
  {:trace  7 ; debug
   :debug  7 ; debug
   :info   6 ; info
   :warn   4 ; warning
   :error  3 ; err
   :fatal  2 ; crit
   :report 6}) ; info

;; Detect if running under systemd
(def ^:private systemd? (some? (System/getenv "JOURNAL_STREAM")))

(defn- systemd-output-fn
  "Output function for systemd journal - omits timestamp (journald adds it)
   and prefixes with syslog priority level."
  [{:keys [level ?ns-str ?line msg_ ?err]}]
  (let [priority (get log-level->syslog level 6)
        ns-str   (or ?ns-str "?")
        line     (or ?line "?")
        msg      (force msg_)
        err-str  (when ?err
                   (let [sw (java.io.StringWriter.)
                         pw (java.io.PrintWriter. sw)]
                     (.printStackTrace ^Throwable ?err pw)
                     (str "\n" (.toString sw))))]
    (str "<" priority ">" (str/upper-case (name level)) " [" ns-str ":" line "] - " msg err-str)))

;; Configure Timbre with systemd-aware output
(when systemd?
  (timbre/merge-config! {:output-fn systemd-output-fn}))

(timbre/set-level! :debug)
; (deps/add-deps '{:deps {djblue/portal {:mvn/version "0.23.0"}}})

; (require '[portal.api :as p])

; (def portal (p/open {:port 8821}))
; (reset! portal state*)
; (add-tap #'p/submit) ; Add portal as a tap> target

;; Pod loading is deferred until init-pod! is called
;; This allows tests to require this namespace without loading the pod
(def ^:private pod-loaded? (atom false))
(def ^:private radiale-ns (atom nil))

(defn init-pod!
  "Initialize the Python pod. Must be called before using radiale-map functions.
   Safe to call multiple times - will only load the pod once."
  []
  (when-not @pod-loaded?
    (pods/load-pod "./pod-xlfe-radiale.py")
    (require '[pod.xlfe.radiale])
    (reset! radiale-ns (find-ns 'pod.xlfe.radiale))
    (reset! pod-loaded? true)
    (println "Pod loaded")))

(defn- get-pod-fn
  "Get a function from the pod namespace. Throws if pod not initialized."
  [fn-name]
  (fn [& args]
    (when-not @pod-loaded? (throw (ex-info "Pod not initialized. Call init-pod! first." {:fn fn-name})))
    (apply (ns-resolve @radiale-ns fn-name) args)))

(def radiale-map
  "Map of pod function keywords to their implementations.
   Pod must be initialized via init-pod! before these functions are called."
  {:listen-mdns          (get-pod-fn 'listen-mdns)
   :listen-mqtt          (get-pod-fn 'listen-mqtt)
   :listen-deconz        (get-pod-fn 'listen-deconz)
   :millis-solar         (get-pod-fn 'millis-solar)
   :millis-crontab       (get-pod-fn 'millis-crontab)
   :put-deconz           (get-pod-fn 'put-deconz)
   :mdns-info            (get-pod-fn 'mdns-info)
   :subscribe-esp        (get-pod-fn 'subscribe-esp)
   :subscribe-chromecast (get-pod-fn 'subscribe-chromecast)
   :sleep-ms             (get-pod-fn 'sleep-ms)
   :switch-esp           (get-pod-fn 'switch-esp)
   :light-esp            (get-pod-fn 'light-esp)
   :service-esp          (get-pod-fn 'service-esp)
   :state-esp            (get-pod-fn 'state-esp)
   :astral-now           (get-pod-fn 'astral-now)})


(defn try-fn
  [send-chan state* m]
  (watch/match-message send-chan state* m)
  (let [clean-m (dissoc m ::fn ::then)]
    (if-let [fn- (::fn m)]

      ; if there is a ::fn specified, call it now after removing it from the map
      (try (fn- radiale-map send-chan state* (dissoc m ::fn))
           (catch Exception e
             (timbre/error
               e
               "Error invoking ::fn handler"
               {:fn       fn-
                :msg-keys (keys m)})))

      ; if not, check for a ::then
      ; If so, merge the map and try again for a fn
      (when-let [then (::then m)]
        (cond
          (fn? then)
          (try-fn send-chan state* (then clean-m))

          (map? then)
          (try-fn send-chan state* (merge clean-m then))

          (sequential? then)
          (doseq [t then]
            (try-fn send-chan state* (merge clean-m t)))

          :else
          (timbre/error
            "Unexpected ::then type"
            {:then then
             :msg  m}))))))

(defn update-or-add
  [state* ident state]
  (if-let [a* (ident @state*)]
    (reset! a* state)
    (swap! state* assoc ident (atom state))))


(defn run
  [config]
  {:pre [(sequential? config)]}
  (init-pod!)
  (let [send-chan (a/chan 64)
        state*    (atom {})]

    (state/watch-state send-chan state*)

    (doseq [m config]
      (prn m)
      (timbre/debug m)
      (try-fn send-chan state* m))

    (timbre/warn "RUNNING")
    (while true
      (try (let [msg (async/<!! send-chan)]

             ; (let [{:keys [::esp/state ::esp/ident]} msg])
             ; (when ident
             ; (update-or-add state* ident state)
             (let [log-msg (prn-str msg)]
               (if (and
                     (map? msg)
                     (contains? msg :radiale.influx/event))
                 (timbre/trace log-msg)
                 (timbre/debug log-msg)))
             ; (prn msg)

             (cond
               (map? msg)
               (try-fn send-chan state* msg)

               (sequential? msg)
               (doseq [m msg]
                 (try-fn send-chan state* m))

               :else
               (timbre/error msg)))
           (catch Exception e (timbre/error e "Error processing message"))))))
