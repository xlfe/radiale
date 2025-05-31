# Radiale

Radiale is a home automation project.

It leverages the power of Clojure for its core logic, Python for specific integrations, and Babashka pods to extend its capabilities.

## Project Structure

The project is organized into the following main directories:

*   **`radiale/`**: This directory contains Python modules for specific integrations.
    *   `__init__.py`: Initializes the Python package.
    *   `chromecast.py`: Module for interacting with Chromecast devices.
    *   `deconz.py`: Module for interacting with deCONZ (Zigbee gateway).
    *   `esphome.py`: Module for interacting with ESPHome devices.
    *   `mdns.py`: Module for mDNS discovery.
    *   `mqtt.py`: Module for MQTT communication.
    *   `pod.py`: Core module for Babashka pod interaction.
    *   `schedule.py`: Module for scheduling tasks.
*   **`src/`**: This directory contains the Clojure source code for the core logic of Radiale.
    *   `radiale/`: This sub-directory contains the Clojure namespaces.
        *   `chromecast.clj`: Clojure wrapper for Chromecast functionality.
        *   `core.clj`: Main entry point and core logic of the Radiale application.
        *   `deconz.clj`: Clojure wrapper for deCONZ functionality.
        *   `esp.clj`: Clojure wrapper for ESPHome functionality.
        *   `influx.clj`: Module for interacting with InfluxDB (time-series database).
        *   `schedule.clj`: Clojure wrapper for task scheduling.
        *   `state.clj`: Manages the state of the application.
        *   `watch.clj`: Module for watching changes (e.g., file system, network).

Key files at the root level:

*   `bb.edn`: Babashka project configuration file.
*   `deps.edn`: Clojure dependencies configuration file.
*   `pod-xlfe-radiale.py`: Script for the Radiale Babashka pod.
*   `setup.py`: Python package setup script.
*   `requirements.txt`: Python dependencies.
*   `start.sh`: Script to start the Radiale application.
*   `Dockerfile-dev`: Docker configuration for the development environment.

## Core Concepts

Radiale's architecture revolves around a few key concepts:

*   **Clojure-Python Interaction (Babashka Pods)**:
    *   The core logic resides in Clojure (`src/radiale/core.clj`), while device-specific integrations and other functionalities are often implemented in Python (`radiale/` directory).
    *   Babashka pods serve as the bridge between these two languages. The `pod-xlfe-radiale.py` script defines a Babashka pod that exposes Python functions to the Clojure environment.
    *   Communication between Clojure and the Python pod is handled using **bencode** for message serialization. The `radiale/pod.py` module in Python manages the pod's lifecycle, message decoding/encoding, and invoking the appropriate Python functions based on requests from Clojure.
    *   Clojure functions (e.g., in `src/radiale/esp.clj`, `src/radiale/deconz.clj`) wrap these pod invocations, providing a seamless interface for the core application logic.

*   **State Management (`src/radiale/state.clj`)**:
    *   Radiale maintains an application state as a Clojure atom (`state*` in `src/radiale/core.clj`).
    *   The `src/radiale/state.clj` namespace provides mechanisms to watch for changes in this state.
    *   When parts of the state are updated (e.g., a device status changes), registered watch functions are triggered.
    *   This allows different parts of the application to react to state changes. For example, a change in a sensor's state might trigger an automation rule.
    *   The `unpack` function in `state.clj` helps in dissecting state changes to identify the specific domain, device, and property that changed, along with its previous and current values.

*   **Message Passing and Event Loop (`src/radiale/core.clj`)**:
    *   Radiale uses a central event loop implemented with `core.async` channels. A main `send-chan` is used to pass messages or events throughout the application.
    *   The `run` function in `core.clj` initializes this channel and enters a loop, continuously taking messages from `send-chan`.
    *   Messages are typically maps that can specify a function to execute (`::fn`) or a subsequent action/transformation (`::then`).
    *   The `try-fn` function is responsible for processing these messages. It can:
        *   Invoke a function directly if `::fn` is present.
        *   Process a `::then` clause, which can be another function, a map to merge, or a sequence of actions to perform.
        *   Utilize `watch/match-message` to trigger actions based on message patterns.
    *   This message-passing architecture allows for decoupled components and asynchronous operations.

*   **Scheduling (`src/radiale/schedule.clj` and `radiale/schedule.py`)**:
    *   Radiale supports task scheduling based on time, solar events (sunrise/sunset), and cron-like expressions.
    *   The Clojure side (`src/radiale/schedule.clj`) uses the `overtone/at-at` library for managing scheduled jobs.
    *   It provides functions like `crontab`, `solar`, `after` (run once after a delay), and `every` (run periodically).
    *   These functions typically interact with the Python pod (via `radiale/millis-solar` and `radiale/millis-crontab` ops defined in `radiale/pod.py`) to calculate the milliseconds until the next scheduled event.
    *   The Python module `radiale/schedule.py` contains the logic for these calculations (e.g., `ms_until_solar`, `ms_until_crontab`).
    *   Scheduled tasks result in messages being put onto the main `send-chan` for processing by the core event loop.
    *   The `::at-most-once` option in scheduling helps prevent duplicate job scheduling by using a unique identifier.

## Supported Devices and Services

Radiale integrates with various devices and services:

*   **Chromecast**:
    *   Utilizes mDNS for discovery (`_googlecast._tcp.local.`).
    *   The `radiale.chromecast` Python module, using `dmcast`, connects to Chromecast devices and notifies of state changes.
    *   `src/radiale/chromecast.clj` provides functions to discover and store Chromecast properties in the application state.
    *   Allows sending commands to Chromecast devices (though specific commands are not detailed in `chromecast.py`'s `command` method, it suggests generic command forwarding).

*   **deCONZ (Zigbee Gateway)**:
    *   Connects to a deCONZ gateway via its REST API and WebSocket for real-time event listening.
    *   `radiale.deconz` Python module handles API requests (e.g., getting configuration, putting device states) and listens for WebSocket events.
    *   `src/radiale/deconz.clj` manages deCONZ device discovery, stores their configuration and state, and provides a `put` function to send commands (e.g., turn lights on/off, change brightness).
    *   State changes from deCONZ (e.g., sensor updates, light status) are processed and reflected in the application state.

*   **ESPHome**:
    *   Integrates with ESPHome devices using the `aioesphomeapi` library.
    *   Discovery is done via mDNS (`_esphomelib._tcp.local.`).
    *   `radiale.esphome` Python module establishes connections, handles device state subscriptions, lists available entities/services, and executes commands (switch, light, user-defined services). It also handles reconnection logic.
    *   `src/radiale/esp.clj` processes discovery results, stores ESPHome device services and their states, and provides functions to send commands (`switch`, `light`, `service`) and update Home Assistant states on the ESPHome device.

*   **MQTT**:
    *   Provides a generic MQTT client using `asyncio-mqtt`.
    *   `radiale.mqtt` Python module can connect to an MQTT broker and subscribe to all topics (`#`).
    *   Received MQTT messages (topic and payload) are forwarded to the Clojure core for processing.
    *   The Clojure side (`src/radiale/core.clj` registers `listen-mqtt`) can then react to these messages, allowing integration with any device or service that communicates over MQTT.

## Installation

### Prerequisites

*   **Java**: Required for running Clojure. Version 19 is used in the Docker setup, but other recent LTS versions should work.
*   **Python**: Required for the Babashka pod and various integrations. Version 3.9 is specified.
*   **Clojure CLI Tools**: Needed to manage Clojure dependencies and run the application.
*   **Babashka (optional but recommended for pod development/testing)**: For working with Babashka pods directly.

### Dependencies

*   **Clojure Dependencies**: Defined in `deps.edn`. Key dependencies include:
    *   `org.clojure/clojure`
    *   `org.clojure/core.async`
    *   `com.taoensso/timbre` (for logging)
    *   `net.xlfe/at-at` (for scheduling)
    *   `babashka/babashka.pods` (for Python pod interaction)
    These will be fetched automatically by the Clojure CLI.

*   **Python Dependencies**: Listed in `setup.py` under `install_requires`. Key dependencies include:
    *   `protobuf`
    *   `aioesphomeapi`
    *   `websockets`
    *   `aiohttp`
    *   `dmcast` (for Chromecast)
    *   `zeroconf` (for mDNS)
    *   `bcoding` (for pod communication)
    *   `asyncio-mqtt`
    *   `astral` (for solar scheduling)
    You can install these using pip, typically via `pip install -e .` or `python setup.py develop` from the project root.

### Docker

*   A `Dockerfile-dev` is provided, which sets up an environment with Java, Python, Clojure tools, and installs all dependencies. This can be a convenient way to get started or ensure a consistent environment.
*   Build the Docker image and run it, referring to the `start.sh` script as the entry point.

## Configuration

The main application configuration is typically loaded from a Clojure (EDN) file. The `start.sh` script executes `clojure -i config/setup.clj`. This suggests that `config/setup.clj` is the entry point for loading your specific setup, which would in turn likely load an EDN configuration file.

*   **EDN Configuration File**: While a specific example like `config.edn.example` is not present in the root, you will need to create a configuration file (e.g., `config/my_config.edn`).
*   This file will define:
    *   Credentials and connection details for services like deCONZ (API key, host), MQTT (broker address, credentials).
    *   Definitions of your devices and how they map to Radiale's internal identifiers.
    *   Automation rules, schedules, and event handlers.
*   Refer to the `src/radiale/core.clj` `run` function, which expects a sequence of configuration maps. Each map defines an initial setup or listener.
*   Examine the various `discover` and command functions in `src/radiale/deconz.clj`, `src/radiale/esp.clj`, etc., to understand the parameters they require (e.g., `::api-key`, `::host` for deCONZ).

## Usage

### Running the Application

1.  **Ensure all dependencies** (Clojure and Python) are installed.
2.  **Create your configuration file(s)** as described in the "Configuration" section. Modify `config/setup.clj` if necessary to point to your main configuration EDN file.
3.  **Execute the `start.sh` script**:
    ```bash
    ./start.sh
    ```
    Alternatively, if running without the script, you would use the Clojure CLI. Since `start.sh` uses `clojure -i config/setup.clj` (which loads and executes the script), you would replicate that if `config/setup.clj` is your main entry point for running the application:
    ```bash
    clojure -i config/setup.clj
    ```
    Ensure the Python pod script (`pod-xlfe-radiale.py`) is executable and its path is correctly referenced by the Clojure code. `start.sh` is generally the recommended method.

### Example Configuration (Conceptual EDN)

This is a *hypothetical* example to illustrate how you might structure your configuration. The actual keywords and structure will depend on the implementation in `core.clj` and related modules. (Note: Clojure keywords like `::ident` often use namespaces, e.g., `:some.namespace/livingroom_light` or `::alias/livingroom_light`, for clarity and to avoid collisions, depending on how they are defined and used in the code.)

```clojure
[
 ;; Initialize deCONZ connection and discovery
 {:fn radiale.deconz/discover
  ::host "deconz.local"
  ::api-key "YOUR_DECONZ_API_KEY"
  ::service-type-namespaces {:lights :radiale.deconz/light ; Example of namespaced keyword
                             :sensors :radiale.deconz/sensor}}

 ;; Initialize ESPHome discovery
 {:fn radiale.esp/discover}

 ;; Initialize MQTT listener
 {:fn pod.xlfe.radiale/listen-mqtt ; Direct call to a pod function
  :host "mqtt.local"
  :username "mqtt_user"
  :password "mqtt_pass"}

 ;; Define a scheduled task: Turn on 'livingroom.light_main' at sunset
 {:fn radiale.schedule/solar
  ::rc/desc "Sunset lights on"
  ::params {:event "sunset" :lat 51.50 :lon -0.12 :tz "Europe/London"} ; Example coordinates
  ::at-most-once :sunset_livingroom_light_on ; Unique ID for the schedule
  ::then {:fn radiale.deconz/put
          ::ident :radiale.deconz/livingroom_light_main ; Assuming 'livingroom_light_main' is unique within this namespace
          ::state {:on true :bri 254}}}

 ;; Automation: When a specific MQTT message is received, toggle an ESPHome switch
 {:fn radiale.watch/add-watch
  :path [:mqtt/messages "my/custom/topic/toggle"] ; Path to watch in the state
  ::then {:fn radiale.esp/switch
          ::ident :radiale.esphome/my_esp_switch ; Example of a more specific namespaced ident
          ::state :toggle}} ; Assuming :toggle is a valid state, otherwise true/false

 ;; Simple periodic task: Log a message every 5 minutes
 {:fn radiale.schedule/every
  ::rc/desc "Periodic log"
  ::seconds (* 5 60)
  ::at-most-once :periodic_log_message
  ::then {:fn (fn [_ _ _ _] (taoensso.timbre/info "5 minutes elapsed"))}} ; Inline function
]
```

**Note**: This example is illustrative. You'll need to adapt it based on the actual data structures and functions available in Radiale's Clojure modules. The `::fn` key points to a Clojure function to be called, and other keys provide parameters for that function. The `::then` key specifies what to do after the initial function completes or an event occurs.

## Contributing

Contributions are welcome! Whether it's reporting a bug, suggesting a new feature, or submitting a pull request, your input is valuable. Please feel free to open an issue or a PR on the project's repository.
