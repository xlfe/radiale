# How to Configure deCONZ

Connect radiale to your deCONZ Zigbee gateway to control Zigbee lights, sensors, and switches.

## Problem

You have a deCONZ gateway (ConBee/RaspBee) and want to integrate your Zigbee devices with radiale.

## Prerequisites

- deCONZ is running and accessible on your network
- You have a deCONZ API key (or know how to generate one)
- Radiale is installed ([Getting Started](../tutorials/getting-started.md))

## Steps

### 1. Get Your deCONZ API Key

If you don't have an API key, generate one using the deCONZ Phoscon web interface:

1. Open Phoscon at `http://<deconz-host>/phocon`
2. Go to **Settings** > **Gateway** > **Advanced**
3. Click **Authenticate app**
4. Within 60 seconds, run:

```bash
curl -X POST -H "Content-Type: application/json" \
  -d '{"devicetype": "radiale"}' \
  http://<deconz-host>:8080/api
```

Save the returned API key.

### 2. Add deCONZ to Your Configuration

Edit `config/setup.clj`:

```clojure
(ns config.setup
  (:require [radiale.core :as rc]
            [radiale.deconz :as deconz]))

(def config
  [{:fn deconz/discover
    ::rc/host "deconz.local"      ; or IP address
    ::rc/api-key "YOUR_API_KEY"}])

(rc/run config)
```

### 3. Start Radiale

```bash
./start.sh
```

You should see your Zigbee devices being discovered:

```
INFO radiale.deconz - Discovered lights: 5
INFO radiale.deconz - Discovered sensors: 3
```

### 4. Control a Light

Add an action to your configuration:

```clojure
;; Turn on a light
{:fn deconz/put
 ::rc/ident :lights/living-room   ; device identifier
 ::rc/state {:on true :bri 254}}  ; full brightness
```

### 5. React to Sensor Events

Use a watcher to respond to sensor changes:

```clojure
;; When motion detected, turn on light
{:fn radiale.watch/add-watch
 :path [:deconz :sensors :motion-sensor :state :presence]
 ::rc/then {:fn deconz/put
            ::rc/ident :lights/hallway
            ::rc/state {:on true}}}
```

## Troubleshooting

### "Connection refused" error

- Verify deCONZ is running: `curl http://<host>:8080/api/<key>/config`
- Check that the host and port are correct
- Ensure no firewall is blocking the connection

### "Unauthorized" error

- Your API key may be invalid or expired
- Generate a new API key following step 1

### Devices not appearing

- Ensure devices are paired with deCONZ first
- Check the Phoscon interface to verify device visibility
- WebSocket connection may have failed - check logs for errors

## See Also

- [Configuration Reference](../reference/configuration.md)
- [Add Schedules](add-schedule.md) - Automate lights based on time
- [Architecture](../explanation/architecture.md) - How deCONZ integration works
