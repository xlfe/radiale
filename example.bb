(ns radiale.example
  (:require [radiale.core :as rc]))

(rc/run 
  {
   :lights-master-bedroom
   {:radiale.deconz/group   {:name "Master bedroom"}}
   
   :location                {
                             :tz "Australia/Sydney"
                             :lat -33.8688 
                             :lon 151.2093} 
   :before-sunset 
   {:radiale.schedule/solar {:event "sunset" 
                             :radiale.transform/merge :location
                             :offset-seconds -3600}} 

   :lights-on-evening
   {:radiale.watch/event    {:on :before-sunset
                             :then 
                             {:radiale.deconz/put :lights-master-bedroom
                              :state {:on true}}}}
    
   :sunrise 
   {:radiale.schedule/solar {:event "sunrise" 
                             :radiale.transform/merge :location}}


   :heated-towel-rail
   {:radiale.esp/switch     :switch_a/switch_a}

   :heated-floor
   {:radiale.esp/switch     :floor/switch}

   :winter-warmers
   {:radiale.watch/event    {:on   :sunrise
                             :then 
                             [
                              {:radiale.esp/switch {:switch :heated-floor      :state true}}
                              {:radiale.esp/switch {:swith  :heated-towel-rail :state true}}]}}})
    
     



  
