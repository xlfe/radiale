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

(pods/load-pod "./pod-xlfe-radiale.py")
(require '[pod.xlfe.radiale :as radiale])


(println "Pod loaded")

(def radiale-map
  {:listen-mdns          radiale/listen-mdns
   :listen-mqtt          radiale/listen-mqtt
   :listen-deconz        radiale/listen-deconz
   :millis-solar         radiale/millis-solar
   :millis-crontab       radiale/millis-crontab
   :put-deconz           radiale/put-deconz
   :mdns-info            radiale/mdns-info
   :subscribe-esp        radiale/subscribe-esp
   :subscribe-chromecast radiale/subscribe-chromecast
   :sleep-ms             radiale/sleep-ms
   :switch-esp           radiale/switch-esp
   :light-esp            radiale/light-esp
   :service-esp          radiale/service-esp
   :state-esp            radiale/state-esp
   :astral-now           radiale/astral-now})


(defn try-fn
  [send-chan state* m]
  (watch/match-message send-chan state* m)
  (let [clean-m (dissoc m ::fn ::then)]
    (if-let [fn- (::fn m)]

      ; if there is a ::fn specified, call it now after removing it from the map
      (fn- radiale-map send-chan state* (dissoc m ::fn))

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
          (timbre/error m))))))

(defn update-or-add
  [state* ident state]
  (if-let [a* (ident @state*)]
    (reset! a* state)
    (swap! state* assoc ident (atom state))))


(defn run
  [config]
  {:pre [(sequential? config)]}
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
             (timbre/debug (prn-str msg))
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
