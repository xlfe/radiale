# Radiale

Radiale is a home automation system combining Clojure for orchestration and Python for device integrations, connected via Babashka pods.

## Features

- **ESPHome** device control and monitoring
- **deCONZ** Zigbee gateway integration
- **MQTT** broker subscription
- **Chromecast** device discovery
- **Scheduling** with cron, solar events, and delays
- **Event-driven** automation with state watching

## Quick Start

### Prerequisites

- Java 17+
- Python 3.9+
- Clojure CLI

### Installation

```bash
# Clone the repository
git clone https://github.com/xlfe/radiale.git
cd radiale

# Install Python dependencies
python3 -m venv .venv
source .venv/bin/activate
pip install -e .

# Download Clojure dependencies
clojure -P
```

### Minimal Configuration

Create `config/setup.clj`:

```clojure
(ns config.setup
  (:require [radiale.core :as rc]
            [radiale.esp :as esp]))

(def config
  [{:fn esp/discover}])  ; Discover ESPHome devices

(rc/run config)
```

### Run

```bash
./start.sh
```

## Documentation

Full documentation is available in the [`docs/`](docs/) directory:

- **[Getting Started](docs/tutorials/getting-started.md)** - Complete setup tutorial
- **[Configuration](docs/reference/configuration.md)** - Configuration reference
- **[Architecture](docs/explanation/architecture.md)** - System design

### How-To Guides

- [Configure deCONZ](docs/how-to/configure-deconz.md)
- [Configure ESPHome](docs/how-to/configure-esphome.md)
- [Configure MQTT](docs/how-to/configure-mqtt.md)
- [Add Schedules](docs/how-to/add-schedule.md)
- [Deploy to NixOS](docs/how-to/deploy-nixos.md)
- [Run Tests](docs/how-to/run-tests.md)

## Project Structure

```
radiale/
├── src/radiale/     # Clojure source (orchestration)
├── radiale/         # Python modules (device integrations)
├── test/radiale/    # Clojure tests
├── tests/radiale/   # Python tests
├── docs/            # Documentation
├── config/          # Your configuration files
├── deps.edn         # Clojure dependencies
└── setup.py         # Python package
```

## Running Tests

```bash
./run-tests.sh
```

Or individually:

```bash
# Python tests
pytest tests/

# Clojure tests
clojure -M:test
```

## Contributing

Contributions are welcome! Please:

1. Run tests before submitting PRs
2. Follow existing code style
3. Update documentation for new features

## License

MIT
