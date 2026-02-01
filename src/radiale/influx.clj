(ns radiale.influx
  (:require
    [clojure.core.async :as async]
    [clojure.string :as str]
    [radiale.state :as state]
    [radiale.watch :as watch]
    [taoensso.timbre :as timbre])
  (:import [com.influxdb.v3.client InfluxDBClient Point]
           [com.influxdb.v3.client.write WriteOptions]
           [java.time Instant]))

(defonce ^:private client* (atom nil))
(defonce ^:private config* (atom nil))
(defonce ^:private writer-chan* (atom nil))

(def ^:private write-buffer-size 1024)
(def ^:private batch-interval-ms 100)

(defn- write-batch!
  [client points]
  (when (seq points)
    (let [start-ns   (System/nanoTime)
          point-list (java.util.ArrayList. ^java.util.Collection points)
          write-opts (WriteOptions. nil nil nil true)] ; noSync=true for faster writes
      (timbre/trace "InfluxDB3 writing batch" {:points (count points)})
      (.writePoints ^InfluxDBClient client point-list write-opts)
      (let [elapsed-ms (/ (- (System/nanoTime) start-ns) 1000000.0)]
        (timbre/trace
          "InfluxDB3 batch complete"
          {:points     (count points)
           :elapsed-ms elapsed-ms})))))

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

(defn- prop-keys
  [prop]
  (cond
    (keyword? prop)
    #{prop (keyword->string prop)}
    (string? prop)
    #{prop (keyword prop)}
    :else
    #{prop}))

(defn- normalize-allow-props
  [allow-props]
  (reduce
    (fn [acc prop]
      (into acc (prop-keys prop)))
    #{}
    (or allow-props [])))

(defn- normalize-prop-aliases
  [prop-aliases]
  (reduce-kv
    (fn [aliases prop alias]
      (let [value (cond
                    (keyword? alias)
                    alias
                    (string? alias)
                    (keyword alias)
                    :else
                    alias)]
        (reduce
          (fn [acc key]
            (assoc acc key value))
          aliases
          (prop-keys prop))))
    {}
    (or prop-aliases {})))

(defn- ensure-client!
  [{:keys [::host ::token ::database]
    :as   opts}]
  (when-not @client*
    (when (or (str/blank? host) (str/blank? token) (str/blank? database))
      (throw (ex-info "InfluxDB3 config requires ::host, ::token, and ::database" opts)))
    (reset! config* opts)
    (reset! client* (InfluxDBClient/getInstance host (char-array token) database))
    (timbre/info "InfluxDB3 client initialized"))
  (when-not @writer-chan*
    (let [ch (async/chan (async/buffer write-buffer-size))]
      (reset! writer-chan* ch)
      (async/thread
        (timbre/info "InfluxDB3 writer thread started (batching every" batch-interval-ms "ms)")
        (loop [batch []]
          (let [timeout-ch (async/timeout batch-interval-ms)
                [val port] (async/alts!! [ch timeout-ch])]
            (cond
              ;; New points arrived from channel
              (= port ch)
              (if val
                (recur (into batch val))
                ;; Channel closed - flush remaining and exit
                (when (seq batch)
                  (try (write-batch! @client* batch)
                       (catch Throwable t (timbre/error t "InfluxDB3 final flush failed")))))

              ;; Timeout - flush batch if non-empty
              (seq batch)
              (do (try (write-batch! @client* batch)
                       (catch Throwable t (timbre/error t "InfluxDB3 batch write failed")))
                  (recur []))

              ;; Timeout with empty batch - just continue
              :else
              (recur []))))))))

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
        prop-aliases (normalize-prop-aliases (::prop-aliases opts))
        measurement  (get prop-aliases prop prop)
        scalar?      (or (not (map? raw-now)) (contains? raw-now :state))
        allow-props? (contains? opts ::allow-props)
        allow        (when allow-props?
                       (normalize-allow-props allow-props))
        domain       (::state/domain m)]
    (when (contains? domains domain)
      (let [base-tags (normalize-tags
                        {:device ident
                         :domain domain})
            prop-tags (when scalar?
                        (normalize-tags {:prop prop}))
            tags      (merge base-tags prop-tags (normalize-tags extra-tags))
            allow?    (fn [measurement]
                        (or (not allow-props?) (contains? allow measurement)))]
        (cond
          (and
            (map? raw-now)
            (contains? raw-now :state))
          (when (and
                  (allow? measurement)
                  (valid-field-value? now))
            [(point-for-value measurement tags now)])

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

          (allow? measurement)
          (when-let [point (point-for-value measurement tags now)]
            [point]))))))

(defn write-event
  [_radiale-map _bus _state*
   {:keys [::event]
    :as   _m}]
  (if-let [ch @writer-chan*]
    (if-let [resolved (event->points @config* event)]
      (do (timbre/trace "InfluxDB3 queuing points" {:points (count resolved)})
          (when-not (async/offer! ch resolved)
            (timbre/warn "InfluxDB3 write queue full; dropping points" {:points (count resolved)})))
      (timbre/trace
        "InfluxDB3 event->points returned nil"
        {:domain (::state/domain event)
         :ident  (::state/ident event)
         :prop   (::state/prop event)}))
    (timbre/warn "InfluxDB3 writer channel not initialized")))

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
                    (let [points      (event->points opts m)
                          raw-now     (::state/now m)
                          prop        (::state/prop m)
                          domain      (::state/domain m)
                          measurement (get (normalize-prop-aliases (::prop-aliases opts)) prop prop)]
                      (timbre/trace
                        "InfluxDB3 watcher received event"
                        {:domain     domain
                         :ident      (::state/ident m)
                         :prop       prop
                         :has-points (some? points)})
                      (when (and
                              (map? raw-now)
                              (contains? raw-now :state)
                              (nil? points))
                        (timbre/trace
                          "InfluxDB3 filtered event"
                          {:domain      domain
                           :prop        prop
                           :measurement measurement
                           :now         raw-now
                           :domains     (normalize-domains opts)
                           :allow-props (when (contains? opts ::allow-props)
                                          (::allow-props opts))}))
                      (when points
                        {:radiale.core/fn write-event
                         ::event          m})))})))
