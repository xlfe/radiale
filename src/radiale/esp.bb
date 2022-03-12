(ns radiale.esp
  (:require
      [clojure.string]))

(defonce esp-registry* (atom {}))


(defn keywordize-esp-services
  [service-name services]
  (reduce-kv 
    (fn [m k v]
      (let [service-ident (keyword service-name (:object_id v))]
        (assert (nil? (get m k)))
        (assert (nil? (get m service-ident)))

        (merge m {k service-ident
                  service-ident v})))
    {}
    services))


(defn esp-logger
  [{:keys [service-name services state]}]
  (let [service-name (first (clojure.string/split service-name #"\."))]
    (if services
      (swap! esp-registry* merge (keywordize-esp-services service-name services))
      (let [
            [k state]     state
            er            @esp-registry*
            service-ident (get er (keyword k))
            service       (get er service-ident)
            details       (assoc service :state state)]

        (prn {service-ident details})))))




(defn switch
  [k {:keys [esp-switch]} params]
  (prn (get @esp-registry* :switch_a/switch_a))
  (esp-switch :switch_a/switch_a true))
