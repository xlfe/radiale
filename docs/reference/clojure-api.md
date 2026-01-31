# Clojure API Reference

Reference for radiale's Clojure namespaces and public functions.

## radiale.core

Main entry point and event loop.

### `run`

```clojure
(run config)
```

Starts the radiale event loop with the given configuration.

| Parameter | Type | Description |
|-----------|------|-------------|
| `config` | vector | Vector of configuration maps |

**Example:**
```clojure
(rc/run [{:fn radiale.esp/discover}])
```

### `init-pod!`

```clojure
(init-pod!)
```

Initializes the Python Babashka pod. Called automatically by `run`.

### `send!`

```clojure
(send! chan msg)
```

Sends a message to the event loop channel.

| Parameter | Type | Description |
|-----------|------|-------------|
| `chan` | channel | The core.async channel |
| `msg` | map | Message to process |

## radiale.state

Application state management.

### `state*`

```clojure
@state*
```

Atom containing the application state. Access via `@state*` or `deref`.

### `watch-state`

```clojure
(watch-state state* send-chan)
```

Adds a watcher to the state atom that emits change events to the channel.

Events have the structure:
```clojure
{::domain :deconz
 ::ident :lights/kitchen
 ::prop :state
 ::prev {:on false}
 ::now {:on true}}
```

### `unpack`

```clojure
(unpack old-state new-state)
```

Compares two state maps and returns a sequence of change tuples.

## radiale.schedule

Time-based scheduling functions.

### `crontab`

```clojure
{:fn radiale.schedule/crontab
 ::rc/params {:hour 8 :minute 0 :day_of_week "0-4" :tz "Europe/London"}
 ::rc/then {...}}
```

Schedules an action using a crontab configuration.

| Key | Required | Description |
|-----|----------|-------------|
| `::rc/params` | Yes | Crontab config map (see below) |
| `::rc/then` | Yes | Action to execute |
| `::rc/at-most-once` | Recommended | Unique schedule ID |

**Crontab params**:

| Param | Type | Description |
|-------|------|-------------|
| `:hour` | int/string | Hour 0-23 or `"*"` |
| `:minute` | int/string | Minute 0-59 or `"*"` |
| `:day_of_week` | string | `"*"`, `"0-4"`, `"5,6"` (0=Monday, 6=Sunday) |
| `:tz` | string | Timezone (e.g., `"Europe/London"`) |

### `solar`

```clojure
{:fn radiale.schedule/solar
 ::rc/params {:event "sunset" :lat 51.5 :lon -0.1 :tz "UTC"}
 ::rc/then {...}}
```

Schedules an action at a solar event.

| Key | Required | Description |
|-----|----------|-------------|
| `::rc/params` | Yes | Map with `:event`, `:lat`, `:lon`, `:tz` |
| `::rc/then` | Yes | Action to execute |
| `::rc/at-most-once` | Recommended | Unique schedule ID |

Solar events: `"sunrise"`, `"sunset"`, `"dawn"`, `"dusk"`, `"noon"`

### `after`

```clojure
{:fn radiale.schedule/after
 ::rc/seconds 300
 ::rc/then {...}}
```

Executes an action once after a delay.

| Key | Required | Description |
|-----|----------|-------------|
| `::rc/seconds` | Yes | Delay in seconds |
| `::rc/then` | Yes | Action to execute |
| `::rc/at-most-once` | Recommended | Unique schedule ID |

### `every`

```clojure
{:fn radiale.schedule/every
 ::rc/seconds 60
 ::rc/then {...}}
```

Executes an action repeatedly at fixed intervals.

| Key | Required | Description |
|-----|----------|-------------|
| `::rc/seconds` | Yes | Interval in seconds |
| `::rc/then` | Yes | Action to execute |
| `::rc/at-most-once` | Recommended | Unique schedule ID |

## radiale.watch

Message pattern matching and watchers.

### `add-watch`

```clojure
{:fn radiale.watch/add-watch
 :path [:domain :device :property]
 ::rc/then {...}}
```

Registers a watcher for state changes at the specified path.

| Key | Required | Description |
|-----|----------|-------------|
| `:path` | Yes | Vector of keys into state |
| `::rc/then` | Yes | Action on change |
| `::rc/at-most-once` | Recommended | Unique watcher ID |

### `match-message`

```clojure
(match-message watchers msg)
```

Matches a message against registered watchers and returns triggered actions.

## radiale.deconz

deCONZ Zigbee gateway integration.

### `discover`

```clojure
{:fn radiale.deconz/discover
 ::rc/host "deconz.local"
 ::rc/api-key "API_KEY"}
```

Discovers and subscribes to deCONZ devices.

| Key | Required | Description |
|-----|----------|-------------|
| `::rc/host` | Yes | deCONZ hostname or IP |
| `::rc/api-key` | Yes | deCONZ API key |

### `put`

```clojure
{:fn radiale.deconz/put
 ::rc/ident :lights/kitchen
 ::rc/state {:on true :bri 254}}
```

Sends a state change to a deCONZ device.

| Key | Required | Description |
|-----|----------|-------------|
| `::rc/ident` | Yes | Device identifier |
| `::rc/state` | Yes | State map to apply |

## radiale.esp

ESPHome device integration.

### `discover`

```clojure
{:fn radiale.esp/discover}
```

Discovers ESPHome devices via mDNS and subscribes to state updates.

### `switch`

```clojure
{:fn radiale.esp/switch
 ::rc/ident :esphome/device
 ::rc/key "switch_1"
 ::rc/state true}
```

Controls an ESPHome switch entity.

| Key | Required | Description |
|-----|----------|-------------|
| `::rc/ident` | Yes | Device identifier |
| `::rc/key` | Yes | Entity key |
| `::rc/state` | Yes | `true`, `false`, or `:toggle` |

### `light`

```clojure
{:fn radiale.esp/light
 ::rc/ident :esphome/device
 ::rc/key "light_1"
 ::rc/state {:state true :brightness 0.8}}
```

Controls an ESPHome light entity.

| Key | Required | Description |
|-----|----------|-------------|
| `::rc/ident` | Yes | Device identifier |
| `::rc/key` | Yes | Entity key |
| `::rc/state` | Yes | State map |

### `service`

```clojure
{:fn radiale.esp/service
 ::rc/ident :esphome/device
 ::rc/service "my_service"
 ::rc/data {:param "value"}}
```

Calls an ESPHome user-defined service.

| Key | Required | Description |
|-----|----------|-------------|
| `::rc/ident` | Yes | Device identifier |
| `::rc/service` | Yes | Service name |
| `::rc/data` | No | Service parameters |

## radiale.chromecast

Chromecast device integration.

### `discover`

```clojure
{:fn radiale.chromecast/discover}
```

Discovers Chromecast devices via mDNS and monitors their state.

## radiale.influx

InfluxDB3 writer integration.

### `subscribe`

```clojure
{:fn radiale.influx/subscribe
 ::influx/host "http://localhost:8181"
 ::influx/token "INFLUX_TOKEN"
 ::influx/database "radiale"
 ::influx/domains [:radiale.esp :radiale.deconz]
 ::influx/allow-props [:temp :humidity]}
```

Registers a watcher that writes ESPHome state changes to InfluxDB3. When
`::influx/allow-props` is omitted, all scalar values with a `:state` field
are written.

### Measurement Mapping

- ESPHome (`:radiale.esp`): measurement = property name, field = `value`.
- deCONZ (`:radiale.deconz`): measurement = `:radiale.deconz`, fields = each state key.

## Pod Functions

Functions exposed by the Python pod under `pod.xlfe.radiale`.

### `listen-mqtt`

```clojure
{:fn pod.xlfe.radiale/listen-mqtt
 :host "mqtt.local"
 :port 1883
 :username "user"
 :password "pass"}
```

Connects to an MQTT broker and subscribes to all topics.

### `listen-mdns`

```clojure
{:fn pod.xlfe.radiale/listen-mdns
 :service-type "_esphomelib._tcp.local."}
```

Listens for mDNS service announcements.

### `millis-solar`

```clojure
(pod.xlfe.radiale/millis-solar {:event "sunset" :lat 51.5 :lon -0.1 :tz "UTC"})
```

Returns milliseconds until the next solar event.

### `millis-crontab`

```clojure
(pod.xlfe.radiale/millis-crontab "0 * * * *")
```

Returns milliseconds until the next cron trigger.

## See Also

- [Configuration Reference](configuration.md) - Configuration format
- [Python Modules](python-modules.md) - Python implementation details
- [Message Format](message-format.md) - Event structure
