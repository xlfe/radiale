# How to Configure ESPHome

Integrate ESPHome devices with radiale for control and state monitoring.

## Problem

You have ESPHome devices on your network and want to control them or react to their state changes in radiale.

## Prerequisites

- ESPHome devices with the native API enabled
- Devices are on the same network as radiale
- Radiale is installed ([Getting Started](../tutorials/getting-started.md))

## Steps

### 1. Verify ESPHome API is Enabled

In your ESPHome device YAML, ensure the API is enabled:

```yaml
api:
  password: ""  # or set a password
```

If using a password, you'll need to configure it in radiale (not yet supported - use empty password).

### 2. Add ESPHome Discovery to Configuration

Edit `config/setup.clj`:

```clojure
(ns config.setup
  (:require [radiale.core :as rc]
            [radiale.esp :as esp]))

(def config
  [{:fn esp/discover}])  ; Discovers all ESPHome devices via mDNS

(rc/run config)
```

### 3. Start Radiale

```bash
./start.sh
```

ESPHome devices are discovered automatically via mDNS:

```
INFO radiale.esp - Discovered: living-room.local
INFO radiale.esp - Discovered: garage-door.local
INFO radiale.esp - Connected to living-room.local, entities: 3
```

### 4. Control a Switch

```clojure
;; Turn on a switch
{:fn esp/switch
 ::rc/ident :esphome/living-room    ; device name
 ::rc/key "relay_1"                  ; entity key from ESPHome
 ::rc/state true}                    ; on/off
```

### 5. Control a Light

```clojure
;; Set light brightness
{:fn esp/light
 ::rc/ident :esphome/bedroom-light
 ::rc/key "light_1"
 ::rc/state {:state true :brightness 0.5}}  ; 50% brightness
```

### 6. Call a Service

For custom ESPHome services:

```clojure
;; Call a user-defined service
{:fn esp/service
 ::rc/ident :esphome/garage-door
 ::rc/service "open_door"
 ::rc/data {}}
```

### 7. React to State Changes

Watch for state changes from ESPHome entities:

```clojure
;; When button pressed, toggle a light
{:fn radiale.watch/add-watch
 :path [:esphome :living-room :binary_sensor :button :state]
 ::rc/then {:fn esp/switch
            ::rc/ident :esphome/living-room
            ::rc/key "relay_1"
            ::rc/state :toggle}}
```

## Troubleshooting

### Device not discovered

- Verify the device is online: `ping <device>.local`
- Check mDNS is working: `avahi-browse -art | grep esphome`
- Ensure the ESPHome API component is enabled

### "Connection failed" error

- The device may be rebooting or offline
- Check ESPHome logs for errors
- Verify no other client is connected (ESPHome allows limited connections)

### State not updating

- Ensure you're subscribed to the device (discovery does this automatically)
- Check the entity key matches exactly (case-sensitive)

## See Also

- [Configuration Reference](../reference/configuration.md)
- [Add Schedules](add-schedule.md) - Time-based automation
- [Python Modules](../reference/python-modules.md) - ESPHome module details
