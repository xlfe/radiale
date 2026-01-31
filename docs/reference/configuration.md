# Configuration Reference

Radiale is configured using Clojure EDN data structures. This reference describes all configuration options.

## Configuration Structure

Configuration is a vector of maps, where each map defines an action or listener:

```clojure
[{:fn some-function
  ::rc/key "value"
  ...}
 {:fn another-function
  ...}]
```

The configuration is passed to `radiale.core/run`:

```clojure
(ns config.setup
  (:require [radiale.core :as rc]))

(def config [...])
(rc/run config)
```

## Common Keys

These keys are used across multiple configuration types.

### `::fn` or `:fn`

The function to execute. Can be:

- A fully-qualified function symbol: `radiale.deconz/discover`
- A pod function: `pod.xlfe.radiale/listen-mqtt`
- An anonymous function: `(fn [state chan old new] ...)`

### `::rc/then`

Action to perform after the main function completes or an event occurs.

```clojure
;; Map with function
::rc/then {:fn radiale.deconz/put
           ::rc/ident :lights/kitchen
           ::rc/state {:on true}}

;; Function
::rc/then (fn [state chan old new]
            (println "Event:" new))

;; Vector of actions
::rc/then [{:fn action-1} {:fn action-2}]
```

### `::rc/desc`

Human-readable description for logging:

```clojure
::rc/desc "Turn on lights at sunset"
```

### `::rc/at-most-once`

Unique identifier to prevent duplicate schedules/watchers:

```clojure
::rc/at-most-once :sunset-lights
```

## deCONZ Configuration

Connect to a deCONZ Zigbee gateway.

### Discovery

```clojure
{:fn radiale.deconz/discover
 ::rc/host "deconz.local"     ; Required: hostname or IP
 ::rc/api-key "ABCD1234"}     ; Required: deCONZ API key
```

### Device Control

```clojure
{:fn radiale.deconz/put
 ::rc/ident :lights/living-room  ; Required: device identifier
 ::rc/state {:on true            ; Light state
             :bri 254            ; Brightness (0-254)
             :ct 350             ; Color temperature (mireds)
             :xy [0.5 0.5]}}     ; CIE color coordinates
```

### State Properties

| Property | Type | Description |
|----------|------|-------------|
| `:on` | boolean | Light on/off |
| `:bri` | int (0-254) | Brightness |
| `:ct` | int (153-500) | Color temperature in mireds |
| `:xy` | [float, float] | CIE xy color coordinates |
| `:hue` | int (0-65535) | Hue |
| `:sat` | int (0-254) | Saturation |
| `:transitiontime` | int | Transition time in 1/10 seconds |

## ESPHome Configuration

Integrate ESPHome devices.

### Discovery

```clojure
{:fn radiale.esp/discover}    ; Discovers all ESPHome devices via mDNS
```

### Switch Control

```clojure
{:fn radiale.esp/switch
 ::rc/ident :esphome/device-name  ; Required: device identifier
 ::rc/key "switch_1"              ; Required: entity key
 ::rc/state true}                 ; true/false/:toggle
```

### Light Control

```clojure
{:fn radiale.esp/light
 ::rc/ident :esphome/device-name
 ::rc/key "light_1"
 ::rc/state {:state true          ; on/off
             :brightness 0.5       ; 0.0-1.0
             :rgb [255 0 0]}}      ; RGB color
```

### Service Call

```clojure
{:fn radiale.esp/service
 ::rc/ident :esphome/device-name
 ::rc/service "service_name"      ; ESPHome service name
 ::rc/data {:param "value"}}      ; Service parameters
```

## MQTT Configuration

Connect to an MQTT broker.

```clojure
{:fn pod.xlfe.radiale/listen-mqtt
 :host "mqtt.local"              ; Required: broker hostname
 :port 1883                      ; Optional: port (default 1883)
 :username "user"                ; Optional: authentication
 :password "pass"}               ; Optional: authentication
```

## InfluxDB3 Configuration

Write state changes to InfluxDB3 using the Java client.

```clojure
{:fn radiale.influx/subscribe
 ::influx/host "http://localhost:8181" ; Required
 ::influx/token "INFLUX_TOKEN"         ; Required
 ::influx/database "radiale"           ; Required
 ;; Optional: domains to write (defaults to [:radiale.esp])
 ::influx/domains [:radiale.esp :radiale.deconz]
 ;; Optional: only write these properties (keywords from ::state/prop)
 ::influx/allow-props [:temp :humidity]}
```

If `::influx/allow-props` is omitted, all scalar values with a `:state` field are written.

### Measurement Mapping

- ESPHome (`:radiale.esp`): measurement = property name, field = `value`.
- deCONZ (`:radiale.deconz`): measurement = `:radiale.deconz`, fields = each state key (`bri`, `on`, `ct`, ...).

All tags/measurement names are written as strings. Tags include `device` and `domain`.

## Scheduling Configuration

Time-based task execution.

### Crontab Schedule

```clojure
{:fn radiale.schedule/crontab
 ::rc/desc "Description"
 ::rc/params {:hour 8             ; 0-23 or "*"
              :minute 0           ; 0-59 or "*"
              :day_of_week "0-4"  ; Python weekday: 0=Mon, 6=Sun
              :tz "Europe/London"}
 ::rc/at-most-once :unique-id    ; Recommended: prevent duplicates
 ::rc/then {...}}                ; Required: action to execute
```

**Note**: Day of week uses Python convention (0=Monday, 6=Sunday), NOT standard cron (0=Sunday).

### Solar Schedule

```clojure
{:fn radiale.schedule/solar
 ::rc/desc "Description"
 ::rc/params {:event "sunset"     ; Required: solar event
              :lat 51.5           ; Required: latitude
              :lon -0.1           ; Required: longitude
              :tz "Europe/London"} ; Required: timezone
 ::rc/at-most-once :unique-id
 ::rc/then {...}}
```

Solar events: `"sunrise"`, `"sunset"`, `"dawn"`, `"dusk"`, `"noon"`

### Delay (Run Once)

```clojure
{:fn radiale.schedule/after
 ::rc/desc "Description"
 ::rc/seconds 300                ; Required: delay in seconds
 ::rc/at-most-once :unique-id
 ::rc/then {...}}
```

### Periodic (Run Repeatedly)

```clojure
{:fn radiale.schedule/every
 ::rc/desc "Description"
 ::rc/seconds 60                 ; Required: interval in seconds
 ::rc/at-most-once :unique-id
 ::rc/then {...}}
```

## Watch Configuration

React to state changes.

```clojure
{:fn radiale.watch/add-watch
 :path [:domain :device :property] ; Required: state path to watch
 ::rc/then {...}}                  ; Required: action on change
```

The `:path` is a vector of keys into the application state atom.

## Complete Example

```clojure
(ns config.setup
  (:require [radiale.core :as rc]
            [radiale.deconz :as deconz]
            [radiale.esp :as esp]
            [radiale.schedule :as schedule]
            [radiale.watch :as watch]))

(def config
  [;; Discover devices
   {:fn deconz/discover
    ::rc/host "deconz.local"
    ::rc/api-key "YOUR_API_KEY"}
   
   {:fn esp/discover}
   
   ;; MQTT listener
   {:fn pod.xlfe.radiale/listen-mqtt
    :host "mqtt.local"}
   
   ;; Sunset automation
   {:fn schedule/solar
    ::rc/desc "Sunset lights"
    ::rc/params {:event "sunset" :lat 51.5 :lon -0.1 :tz "Europe/London"}
    ::rc/at-most-once :sunset
    ::rc/then {:fn deconz/put
               ::rc/ident :lights/outdoor
               ::rc/state {:on true}}}
   
   ;; Motion sensor reaction
   {:fn watch/add-watch
    :path [:deconz :sensors :motion :state :presence]
    ::rc/then {:fn deconz/put
               ::rc/ident :lights/hallway
               ::rc/state {:on true :transitiontime 5}}}])

(rc/run config)
```

## See Also

- [Message Format](message-format.md) - Event and message structure
- [Clojure API](clojure-api.md) - Function reference
- [Architecture](../explanation/architecture.md) - How configuration is processed
