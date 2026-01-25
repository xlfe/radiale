# Getting Started with Radiale

This tutorial walks you through setting up radiale from scratch. By the end, you'll have a running instance discovering devices on your network.

## What You'll Learn

- Install all dependencies (Java, Python, Clojure)
- Configure a minimal radiale setup
- Start the application and verify it's working
- Understand the basic project structure

## Prerequisites

You need a Linux or macOS system with:
- Network access to IoT devices (ESPHome, deCONZ, etc.)
- Basic familiarity with the command line
- A text editor

## Step 1: Install System Dependencies

Radiale requires Java, Python, and Clojure CLI tools.

### Java (JDK 17+)

```bash
# Ubuntu/Debian
sudo apt install openjdk-17-jdk

# macOS (Homebrew)
brew install openjdk@17

# Verify installation
java -version
```

**Checkpoint**: You should see Java version 17 or higher.

### Python (3.9+)

```bash
# Ubuntu/Debian
sudo apt install python3 python3-pip python3-venv

# macOS (Homebrew)
brew install python@3.11

# Verify installation
python3 --version
```

**Checkpoint**: You should see Python 3.9 or higher.

### Clojure CLI

```bash
# Linux
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh

# macOS (Homebrew)
brew install clojure/tools/clojure

# Verify installation
clojure --version
```

**Checkpoint**: You should see the Clojure CLI version.

## Step 2: Clone and Install Radiale

```bash
# Clone the repository
git clone https://github.com/xlfe/radiale.git
cd radiale

# Create a Python virtual environment (recommended)
python3 -m venv .venv
source .venv/bin/activate

# Install Python dependencies
pip install -e .
```

**Checkpoint**: Run `pip list | grep radiale` and verify the package is installed.

## Step 3: Verify Clojure Dependencies

Clojure dependencies are defined in `deps.edn` and downloaded automatically on first run.

```bash
# Download dependencies (this may take a minute)
clojure -P
```

**Checkpoint**: The command completes without errors.

## Step 4: Create Your Configuration

Radiale uses a Clojure file to define your setup. Create `config/setup.clj`:

```bash
mkdir -p config
```

Create `config/setup.clj` with a minimal configuration:

```clojure
(ns config.setup
  (:require [radiale.core :as rc]))

;; Minimal configuration: just discover ESPHome devices via mDNS
(def config
  [{:fn radiale.esp/discover}])

;; Start radiale
(rc/run config)
```

This configuration:
1. Loads the radiale core namespace
2. Defines a config that discovers ESPHome devices
3. Starts the main event loop

## Step 5: Start Radiale

```bash
./start.sh
```

Or run directly:

```bash
clojure -i config/setup.clj
```

**Checkpoint**: You should see log output. If you have ESPHome devices on your network, they'll be discovered and logged.

Example output:
```
2025-01-26 10:30:15 INFO radiale.core - Starting radiale...
2025-01-26 10:30:15 INFO radiale.core - Pod initialized
2025-01-26 10:30:16 INFO radiale.esp - Discovered: living-room.local
```

Press `Ctrl+C` to stop.

## Step 6: Understand the Project Structure

Now that radiale is running, let's understand the codebase:

```
radiale/
├── src/radiale/           # Clojure source code
│   ├── core.clj           # Main entry point and event loop
│   ├── state.clj          # Application state management
│   ├── schedule.clj       # Time-based scheduling
│   ├── esp.clj            # ESPHome integration
│   ├── deconz.clj         # deCONZ/Zigbee integration
│   └── ...
├── radiale/               # Python modules (device integrations)
│   ├── pod.py             # Babashka pod interface
│   ├── esphome.py         # ESPHome client
│   ├── deconz.py          # deCONZ client
│   └── ...
├── config/                # Your configuration files
│   └── setup.clj          # Main entry point
├── deps.edn               # Clojure dependencies
└── setup.py               # Python package definition
```

**Key insight**: Clojure handles orchestration (state, scheduling, event routing) while Python handles device communication (ESPHome API, WebSockets, mDNS).

## Next Steps

You now have radiale running. Continue with:

- [Configure deCONZ](../how-to/configure-deconz.md) - Add Zigbee device support
- [Configure MQTT](../how-to/configure-mqtt.md) - Integrate MQTT devices
- [Add Schedules](../how-to/add-schedule.md) - Create time-based automations
- [Architecture](../explanation/architecture.md) - Understand how it all works

## Troubleshooting

### "Command not found: clojure"

The Clojure CLI isn't in your PATH. Ensure the installation completed and restart your terminal.

### Python ImportError

Make sure you activated the virtual environment:
```bash
source .venv/bin/activate
```

### No devices discovered

- Verify your devices are on the same network
- Check that mDNS is not blocked by your firewall
- ESPHome devices must have the API enabled
