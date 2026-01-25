(ns radiale.influx
  (:require

    [babashka.pods :as pods]
    [clojure.core.async :as async]
    [clojure.core.async :as a]
    [clojure.test :refer [function?]]
    [radiale.esp :as esp]
    [radiale.state :as state]
    [radiale.watch :as watch]
    [taoensso.timbre :as timbre]))
    ; [babashka.deps :as deps]))
    ; [radiale.schedule :as schedule]
    ; [radiale.deconz]))

; set log level
; (alter-var-root #'timbre/*config* #(assoc %1 :min-level :info))

; (deps/add-deps '{:deps {org.clj-commons/clj-http-lite {:mvn/version "0.4.392"}}})
; (require '[clj-http.lite.client :as client])






