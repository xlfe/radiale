(ns radiale.influx
  (:require 

    [radiale.watch :as watch]
    [radiale.esp :as esp]
    [radiale.state :as state]
    [clojure.core.async :as async]
    [taoensso.timbre :as timbre]
    [clojure.test :refer [function?]]
    [clojure.core.async :as a]
    [babashka.pods :as pods]))
    ; [babashka.deps :as deps]))
    ; [radiale.schedule :as schedule]
    ; [radiale.deconz]))

; set log level
; (alter-var-root #'timbre/*config* #(assoc %1 :min-level :info))

; (deps/add-deps '{:deps {org.clj-commons/clj-http-lite {:mvn/version "0.4.392"}}})
; (require '[clj-http.lite.client :as client])





                              
