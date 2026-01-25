# Radiale Documentation

Radiale is a home automation system combining Clojure for orchestration and Python for device integrations, connected via Babashka pods.

## Documentation Structure

This documentation follows the [Diataxis framework](https://diataxis.fr/):

### Tutorials (Learning-oriented)

Step-by-step lessons to help you get started.

- [Getting Started](tutorials/getting-started.md) - Set up radiale from scratch

### How-To Guides (Task-oriented)

Practical guides for accomplishing specific tasks.

- [Configure deCONZ](how-to/configure-deconz.md) - Connect to your Zigbee gateway
- [Configure ESPHome](how-to/configure-esphome.md) - Integrate ESPHome devices
- [Configure MQTT](how-to/configure-mqtt.md) - Set up MQTT integration
- [Add Schedules](how-to/add-schedule.md) - Create time-based automations
- [Deploy to NixOS](how-to/deploy-nixos.md) - Deploy radiale to a NixOS server
- [Run Tests](how-to/run-tests.md) - Execute the test suite

### Reference (Information-oriented)

Technical descriptions of the system components.

- [Configuration Format](reference/configuration.md) - EDN configuration structure
- [Clojure API](reference/clojure-api.md) - Core namespace reference
- [Python Modules](reference/python-modules.md) - Python module reference
- [Message Format](reference/message-format.md) - Event and message structure

### Explanation (Understanding-oriented)

Background and design rationale.

- [Architecture](explanation/architecture.md) - System design and why Clojure + Python
- [State Management](explanation/state-management.md) - How state and events work

## Quick Links

| Task | Go To |
|------|-------|
| First time setup | [Getting Started](tutorials/getting-started.md) |
| Add a new device type | [Configuration Format](reference/configuration.md) |
| Understand the codebase | [Architecture](explanation/architecture.md) |
| Run tests before contributing | [Run Tests](how-to/run-tests.md) |
| Deploy to production | [Deploy to NixOS](how-to/deploy-nixos.md) |
