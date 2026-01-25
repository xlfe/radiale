# How to Configure MQTT

Connect radiale to an MQTT broker to integrate MQTT-based devices and services.

## Problem

You have devices or services that communicate via MQTT and want to integrate them with radiale.

## Prerequisites

- An MQTT broker (Mosquitto, EMQX, etc.) running and accessible
- Broker credentials (if authentication is enabled)
- Radiale is installed ([Getting Started](../tutorials/getting-started.md))

## Steps

### 1. Add MQTT Listener to Configuration

Edit `config/setup.clj`:

```clojure
(ns config.setup
  (:require [radiale.core :as rc]))

(def config
  [{:fn pod.xlfe.radiale/listen-mqtt
    :host "mqtt.local"              ; broker hostname or IP
    :port 1883                      ; default MQTT port
    :username "user"                ; optional
    :password "pass"}])             ; optional

(rc/run config)
```

### 2. Start Radiale

```bash
./start.sh
```

Radiale subscribes to all topics (`#`) and logs incoming messages:

```
INFO radiale.core - MQTT connected to mqtt.local:1883
DEBUG radiale.core - MQTT: sensors/temperature -> {"value": 22.5}
```

### 3. React to MQTT Messages

Use a watcher to trigger actions on specific topics:

```clojure
;; When temperature exceeds threshold, turn on fan
{:fn radiale.watch/add-watch
 :path [:mqtt "sensors/temperature"]
 ::rc/then (fn [_ _ old new]
             (when (> (:value new) 25)
               {:fn radiale.esp/switch
                ::rc/ident :esphome/fan
                ::rc/key "relay"
                ::rc/state true}))}
```

### 4. Filter by Topic Pattern

For more specific topic matching:

```clojure
;; Watch all sensor topics
{:fn radiale.watch/add-watch
 :path [:mqtt]
 :match-fn (fn [path] (str/starts-with? (last path) "sensors/"))
 ::rc/then handle-sensor-update}
```

### 5. Publish to MQTT (via external means)

Radiale currently focuses on subscribing to MQTT. To publish, you can:

1. Use an ESPHome device with MQTT
2. Call an external script
3. Use the MQTT integration in your other automation tools

## Configuration Options

| Key | Required | Description |
|-----|----------|-------------|
| `:host` | Yes | Broker hostname or IP |
| `:port` | No | Port (default: 1883) |
| `:username` | No | Authentication username |
| `:password` | No | Authentication password |

## Troubleshooting

### "Connection refused" error

- Verify the broker is running: `mosquitto_sub -h <host> -t '#'`
- Check hostname and port are correct
- Ensure no firewall is blocking the connection

### "Authentication failed"

- Verify username and password are correct
- Check broker ACL configuration allows the user to subscribe

### Messages not appearing

- Verify messages are being published: `mosquitto_sub -h <host> -t '#' -v`
- Check the topic path in your watcher matches exactly
- MQTT payloads are parsed as JSON when possible

## See Also

- [Message Format](../reference/message-format.md) - How MQTT messages are structured
- [Add Schedules](add-schedule.md) - Combine MQTT with time-based rules
- [State Management](../explanation/state-management.md) - How MQTT updates application state
