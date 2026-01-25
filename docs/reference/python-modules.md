# Python Modules Reference

Reference for radiale's Python modules that provide device integrations.

## Overview

Python modules are located in `radiale/` and communicate with Clojure via the Babashka pod interface. They handle:

- Device communication (API calls, WebSockets, mDNS)
- Asynchronous I/O (asyncio)
- Protocol-specific logic

## radiale.pod

Core Babashka pod interface.

### Purpose

Handles communication between Clojure and Python:
- Bencode message serialization/deserialization
- Operation dispatch to appropriate modules
- Async task management
- Callback routing to Clojure

### Key Components

| Component | Description |
|-----------|-------------|
| `OutgoingQ` | Thread-safe queue for messages to Clojure |
| `run_loop()` | Main asyncio event loop |
| `bencode_out()` | Encodes and sends messages to stdout |
| `handle_message()` | Dispatches incoming operations |

### Operations Exposed

| Operation | Handler | Description |
|-----------|---------|-------------|
| `listen-mdns` | `mdns.listen()` | mDNS service discovery |
| `listen-mqtt` | `mqtt.listen()` | MQTT subscription |
| `listen-deconz` | `deconz.listen()` | deCONZ WebSocket |
| `subscribe-esp` | `esphome.subscribe()` | ESPHome state subscription |
| `subscribe-chromecast` | `chromecast.subscribe()` | Chromecast monitoring |
| `put-deconz` | `deconz.put()` | deCONZ state change |
| `switch-esp` | `esphome.switch()` | ESPHome switch control |
| `light-esp` | `esphome.light()` | ESPHome light control |
| `service-esp` | `esphome.service()` | ESPHome service call |
| `state-esp` | `esphome.state()` | ESPHome Home Assistant state |
| `millis-solar` | `schedule.ms_until_solar()` | Solar time calculation |
| `millis-crontab` | `schedule.ms_until_crontab()` | Cron time calculation |
| `astral-now` | `schedule.astral_now()` | Current solar position |
| `sleep-ms` | `asyncio.sleep()` | Async delay |

## radiale.esphome

ESPHome device integration using `aioesphomeapi`.

### Functions

#### `subscribe(name, host, callback)`

Connects to an ESPHome device and subscribes to state updates.

| Parameter | Type | Description |
|-----------|------|-------------|
| `name` | str | Device identifier |
| `host` | str | Device hostname |
| `callback` | callable | Function to receive state updates |

Returns: Task handle

#### `switch(host, key, state)`

Controls a switch entity.

| Parameter | Type | Description |
|-----------|------|-------------|
| `host` | str | Device hostname |
| `key` | str | Entity key |
| `state` | bool | Desired state |

#### `light(host, key, state)`

Controls a light entity.

| Parameter | Type | Description |
|-----------|------|-------------|
| `host` | str | Device hostname |
| `key` | str | Entity key |
| `state` | dict | State with `state`, `brightness`, etc. |

#### `service(host, service_name, data)`

Calls a user-defined service.

| Parameter | Type | Description |
|-----------|------|-------------|
| `host` | str | Device hostname |
| `service_name` | str | Service identifier |
| `data` | dict | Service parameters |

### State Updates

State updates are sent as callbacks with structure:
```python
{
    "type": "entity_type",  # e.g., "switch", "sensor"
    "key": "entity_key",
    "state": {...}          # Entity-specific state
}
```

## radiale.deconz

deCONZ Zigbee gateway integration.

### Functions

#### `listen(host, api_key, callback)`

Connects to deCONZ REST API and WebSocket for events.

| Parameter | Type | Description |
|-----------|------|-------------|
| `host` | str | deCONZ hostname |
| `api_key` | str | API authentication key |
| `callback` | callable | Function to receive events |

Returns: Initial device configuration

#### `put(host, api_key, resource_type, resource_id, state)`

Sends a state change to a device.

| Parameter | Type | Description |
|-----------|------|-------------|
| `host` | str | deCONZ hostname |
| `api_key` | str | API key |
| `resource_type` | str | `"lights"`, `"groups"`, etc. |
| `resource_id` | str | Device ID |
| `state` | dict | State to apply |

### WebSocket Events

Events received via WebSocket:
```python
{
    "e": "changed",         # Event type
    "r": "lights",          # Resource type
    "id": "1",              # Resource ID
    "state": {"on": true}   # Changed state
}
```

## radiale.mqtt

MQTT client using `aiomqtt`.

### Functions

#### `listen(host, port, username, password, callback)`

Connects to MQTT broker and subscribes to all topics.

| Parameter | Type | Description |
|-----------|------|-------------|
| `host` | str | Broker hostname |
| `port` | int | Broker port (default 1883) |
| `username` | str | Optional authentication |
| `password` | str | Optional authentication |
| `callback` | callable | Function to receive messages |

### Message Format

Messages are delivered as:
```python
{
    "topic": "sensors/temperature",
    "payload": {"value": 22.5}  # Parsed JSON or raw string
}
```

## radiale.mdns

mDNS service discovery using `zeroconf`.

### Functions

#### `listen(service_type, callback)`

Listens for mDNS service announcements.

| Parameter | Type | Description |
|-----------|------|-------------|
| `service_type` | str | e.g., `"_esphomelib._tcp.local."` |
| `callback` | callable | Function to receive discoveries |

### Discovery Events

```python
{
    "name": "device-name",
    "host": "192.168.1.100",
    "port": 6053,
    "properties": {...}
}
```

## radiale.chromecast

Chromecast integration using `dmcast`.

### Functions

#### `subscribe(name, host, callback)`

Connects to a Chromecast and monitors state.

| Parameter | Type | Description |
|-----------|------|-------------|
| `name` | str | Device identifier |
| `host` | str | Device hostname |
| `callback` | callable | Function to receive state updates |

## radiale.schedule

Time calculations for scheduling.

### Functions

#### `ms_until_solar(event, lat, lon, tz)`

Calculates milliseconds until a solar event.

| Parameter | Type | Description |
|-----------|------|-------------|
| `event` | str | `"sunrise"`, `"sunset"`, `"dawn"`, `"dusk"`, `"noon"` |
| `lat` | float | Latitude |
| `lon` | float | Longitude |
| `tz` | str | Timezone (e.g., `"Europe/London"`) |

Returns: Milliseconds until event

#### `ms_until_crontab(expression)`

Calculates milliseconds until next cron trigger.

| Parameter | Type | Description |
|-----------|------|-------------|
| `expression` | str | Cron expression (e.g., `"0 * * * *"`) |

Returns: Milliseconds until trigger

#### `astral_now(lat, lon, tz)`

Returns current solar position information.

| Parameter | Type | Description |
|-----------|------|-------------|
| `lat` | float | Latitude |
| `lon` | float | Longitude |
| `tz` | str | Timezone |

Returns: Map with sunrise, sunset, dawn, dusk times

## radiale.logging

Systemd-aware logging utilities.

### Functions

#### `eprint(*args)`

Prints to stderr with optional systemd journal priority prefix.

When running under systemd, adds RFC 5424 priority levels:
- `<6>` for INFO level messages

### Environment Detection

Checks `JOURNAL_STREAM` environment variable to detect systemd.

## See Also

- [Clojure API](clojure-api.md) - Clojure wrappers for these modules
- [Architecture](../explanation/architecture.md) - How Python and Clojure interact
- [Message Format](message-format.md) - Message structure details
