(ns radiale.influx
  (:require
    [clojure.string :as str]
    [radiale.state :as state]
    [radiale.watch :as watch]
    [taoensso.timbre :as timbre])
  (:import [com.influxdb.v3.client InfluxDBClient Point]
           [java.time Instant]))

(defonce ^:private client* (atom nil))
(defonce ^:private config* (atom nil))

(defn- finite-number?
  [value]
  (and
    (number? value)
    (not (Double/isNaN (double value)))
    (not (Double/isInfinite (double value)))))

(defn- valid-field-value? [value] (or (finite-number? value) (string? value) (boolean? value)))

(defn- extract-value
  [now]
  (cond
    (map? now)
    (:state now)
    :else
    now))

(defn- keyword->string [value] (subs (str value) 1))

(defn- normalize-tag-value
  [value]
  (cond
    (keyword? value)
    (keyword->string value)
    (string? value)
    value
    (nil? value)
    nil
    :else
    (str value)))

(defn- normalize-tags
  [tags]
  (->> tags
       (map
         (fn [[k v]]
           [(normalize-tag-value k) (normalize-tag-value v)]))
       (filter
         (fn [[k v]]
           (and
             (some? k)
             (some? v))))
       (into {})))

(defn- normalize-domains
  [{:keys [::domain ::domains]}]
  (let [values (cond
                 (some? domains)
                 domains
                 (some? domain)
                 [domain]
                 :else
                 [:radiale.esp])]
    (set
      (map
        (fn [value]
          (cond
            (keyword? value)
            value
            (string? value)
            (keyword value)
            :else
            value))
        values))))

(defn- normalize-allow-props
  [allow-props]
  (set
    (map
      (fn [prop]
        (if (keyword? prop) prop (keyword prop)))
      allow-props)))

(defn- ensure-client!
  [{:keys [::host ::token ::database]
    :as   opts}]
  (when-not @client*
    (when (or (str/blank? host) (str/blank? token) (str/blank? database))
      (throw (ex-info "InfluxDB3 config requires ::host, ::token, and ::database" opts)))
    (reset! config* opts)
    (reset! client* (InfluxDBClient/getInstance host (char-array token) database))
    (timbre/info "InfluxDB3 client initialized")))

(defn- measurement-name
  [measurement]
  (cond
    (keyword? measurement)
    (keyword->string measurement)
    (string? measurement)
    measurement
    :else
    (str measurement)))

(defn- point-for-value
  [measurement tags value]
  (when (valid-field-value? value)
    (let [point (Point/measurement (measurement-name measurement))]
      (doseq [[k v] tags]
        (.setTag point k v))
      (.setField point "value" value)
      (.setTimestamp point (Instant/now))
      point)))

(defn- point-for-fields
  [measurement tags field-values]
  (let [fields (->> field-values
                    (filter
                      (fn [[_ v]]
                        (valid-field-value? v)))
                    (into {}))]
    (when (seq fields)
      (let [point (Point/measurement (measurement-name measurement))]
        (doseq [[k v] tags]
          (.setTag point k v))
        (doseq [[k v] fields]
          (.setField point (name k) v))
        (.setTimestamp point (Instant/now))
        point))))

(defn- event->points
  [{:keys [::allow-props ::extra-tags]
    :as   opts} m]
  (let [prop         (::state/prop m)
        ident        (::state/ident m)
        raw-now      (::state/now m)
        now          (extract-value raw-now)
        domains      (normalize-domains opts)
        allow-props? (contains? opts ::allow-props)
        allow        (when allow-props?
                       (normalize-allow-props allow-props))
        domain       (::state/domain m)]
    (when (contains? domains domain)
      (let [base-tags (normalize-tags
                        {:device ident
                         :domain domain})
            tags      (merge base-tags (normalize-tags extra-tags))
            allow?    (fn [measurement]
                        (or (not allow-props?) (contains? allow measurement)))]
        (cond
          (and
            (map? raw-now)
            (contains? raw-now :state))
          (when (and
                  (allow? prop)
                  (valid-field-value? now))
            [(point-for-value prop tags now)])

          (map? raw-now)
          (when-let [point
                     (point-for-fields
                       domain
                       tags
                       (into
                         {}
                         (filter
                           (fn [[k _]]
                             (allow? k))
                           raw-now)))]
            [point])

          (allow? prop)
          (when-let [point (point-for-value prop tags now)]
            [point]))))))

(defn write-event
  [_radiale-map _bus _state*
   {:keys [::event]
    :as   _m}]
  (when-let [client @client*]
    (when-let [points (event->points @config* event)]
      (try (doseq [point points]
             (.writePoint client point))
           (catch Exception e (timbre/warn e "InfluxDB3 write failed"))))))

(defn subscribe
  [_radiale-map bus state*
   {:keys [::id]
    :as   opts}]
  (ensure-client! opts)
  (let [watch-id (or id :influx-writer)]
    (watch/on
      nil
      bus
      state*
      {:id        watch-id
       ::watch/on (fn [_ m]
                    (when (event->points opts m)
                      {:radiale.core/fn write-event
                       ::event          m}))})))
