(ns radiale.example
  (:require 
    [radiale.core :as rc]
    [radiale.esp :as esp]
    [radiale.schedule :as schedule]
    [radiale.watch :as watch]))


(def location {:tz "Australia/Sydney" :lat -33.8688 :lon 151.2093}) 

(rc/run 
  [
   ; subscribe to ESP-Home events (state changes) from devices discovered locally
   {::rc/fn esp/discover 
    ::id ::esp}                    

   ; once discovered, toggle a specific light every 5 seconds 
   {::rc/fn       watch/on
    ::watch/on    (fn [{:keys [::id ::esp/ident ::esp/state] :as m}]
                    (when (and 
                            (= id ::esp) 
                            (= ident :backlight/backlight))
                      (merge m
                             {::id nil
                              ::esp/params {:state (not state)}})))
                              
    ::rc/then {::rc/fn schedule/after 
               ::schedule/seconds 5
               ::rc/then {::rc/fn esp/light}}}           


   ; turn on a heated towel rail at sunrise
   {::rc/fn schedule/solar 
    ::schedule/params (merge location {:event "sunrise"})
    ::rc/then {::rc/fn esp/switch
               ::esp/ident :switch-b/primary
               ::esp/state true}}])














(alter-var-root #'taoensso.timbre/*config* #(assoc %1 :min-level :info))


    
     



  
