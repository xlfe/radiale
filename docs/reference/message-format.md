# Message Format Reference

Reference for the message and event structures used in radiale's event-driven architecture.

## Overview

Radiale uses a message-passing architecture where all events flow through a central `core.async` channel. Messages are Clojure maps with specific keys that determine how they're processed.

## Core Message Keys

### `::fn` (or `:fn`)

Specifies a function to execute.

```clojure
{:fn radiale.deconz/put
 ...}
```

Can be:
- A function symbol: `radiale.deconz/put`
- A pod function: `pod.xlfe.radiale/listen-mqtt`
- An anonymous function: `(fn [state chan old new] ...)`

### `::rc/then`

Specifies what happens after the function executes or an event occurs.

```clojure
;; Function to call
::rc/then {:fn radiale.esp/switch
           ::rc/ident :device
           ::rc/state true}

;; Anonymous function
::rc/then (fn [state chan old new]
            (println "Changed from" old "to" new))

;; Sequence of actions
::rc/then [{:fn action-1} {:fn action-2}]

;; Map to merge into state
::rc/then {:some-key "some-value"}
```

### `::rc/desc`

Human-readable description for logging.

```clojure
::rc/desc "Turn on lights at sunset"
```

### `::rc/at-most-once`

Unique identifier to prevent duplicate scheduling.

```clojure
::rc/at-most-once :sunset-lights-on
```

## State Change Events

When application state changes, watchers emit events with this structure:

```clojure
{::rc/domain :deconz           ; Source domain
 ::rc/ident :lights/kitchen    ; Device identifier
 ::rc/prop :state              ; Changed property
 ::rc/prev {:on false          ; Previous value
            :bri 0}
 ::rc/now {:on true            ; Current value
           :bri 254}}
```

### Domain Values

| Domain | Description |
|--------|-------------|
| `:deconz` | deCONZ/Zigbee devices |
| `:esphome` | ESPHome devices |
| `:mqtt` | MQTT messages |
| `:chromecast` | Chromecast devices |

## Device Control Messages

### deCONZ Light Control

```clojure
{:fn radiale.deconz/put
 ::rc/ident :lights/living-room
 ::rc/state {:on true
             :bri 254
             :ct 350
             :transitiontime 10}}
```

### ESPHome Switch Control

```clojure
{:fn radiale.esp/switch
 ::rc/ident :esphome/device-name
 ::rc/key "relay_1"
 ::rc/state true}  ; or false, or :toggle
```

### ESPHome Light Control

```clojure
{:fn radiale.esp/light
 ::rc/ident :esphome/bedroom
 ::rc/key "light_main"
 ::rc/state {:state true
             :brightness 0.8
             :rgb [255 200 150]}}
```

## Schedule Messages

### Cron Schedule

```clojure
{:fn radiale.schedule/crontab
 ::rc/desc "Hourly check"
 ::rc/crontab "0 * * * *"
 ::rc/at-most-once :hourly-check
 ::rc/then {:fn check-something}}
```

### Solar Schedule

```clojure
{:fn radiale.schedule/solar
 ::rc/desc "Sunset automation"
 ::rc/params {:event "sunset"
              :lat 51.5074
              :lon -0.1278
              :tz "Europe/London"}
 ::rc/at-most-once :sunset-auto
 ::rc/then {:fn do-something}}
```

## MQTT Messages

When MQTT messages arrive, they're stored in state and can trigger watchers:

```clojure
;; State structure
{:mqtt {"sensors/temperature" {:value 22.5}
        "sensors/humidity" {:value 65}}}

;; Watch for MQTT topic
{:fn radiale.watch/add-watch
 :path [:mqtt "sensors/temperature"]
 ::rc/then (fn [state chan old new]
             (when (> (:value new) 30)
               {:fn alert-high-temp}))}
```

## Watcher Messages

Register a watcher for state changes:

```clojure
{:fn radiale.watch/add-watch
 :path [:deconz :sensors :motion-1 :state :presence]
 ::rc/at-most-once :motion-watcher
 ::rc/then {:fn radiale.deconz/put
            ::rc/ident :lights/hallway
            ::rc/state {:on true}}}
```

## Function Signatures

When `::rc/then` is a function, it receives:

```clojure
(fn [state send-chan old-value new-value]
  ;; state: current application state (atom value)
  ;; send-chan: core.async channel to send messages
  ;; old-value: previous value (for state changes)
  ;; new-value: current value (for state changes)
  
  ;; Return a message map to execute, or nil
  {:fn some-function
   ...})
```

## Pod Communication

Messages between Clojure and Python use bencode serialization.

### Clojure to Python

```clojure
;; Invoke operation
{"op" "invoke"
 "id" "unique-id"
 "var" "pod.xlfe.radiale/listen-mqtt"
 "args" [{:host "mqtt.local" :port 1883}]}
```

### Python to Clojure

```clojure
;; Callback with result
{"id" "unique-id"
 "value" {:connected true}}

;; Async callback (streaming results)
{"id" "callback-id"
 "value" {:topic "sensors/temp" :payload {:value 22}}}
```

## State Structure

The application state atom has this general structure:

```clojure
{:deconz {:lights {:kitchen {:state {:on true :bri 254}
                             :config {...}}}
          :sensors {:motion-1 {:state {:presence true}
                               :config {...}}}}
 
 :esphome {:living-room {:switch {:relay_1 {:state true}}
                         :sensor {:temperature {:state 22.5}}}}
 
 :mqtt {"topic/path" {:payload "data"}}
 
 :chromecast {:tv {:status "PLAYING"
                   :media {...}}}
 
 :schedules {:sunset-lights {:next-run 1706284800000}}
 
 :watchers {:motion-light {:path [...] :then {...}}}}
```

## See Also

- [Configuration Reference](configuration.md) - How to construct configuration
- [Clojure API](clojure-api.md) - Functions that process messages
- [State Management](../explanation/state-management.md) - How state changes propagate
