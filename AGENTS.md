# Agent Guide for Radiale

This document contains everything an AI agent needs to know to work effectively in this repository.

## Project Overview

Radiale is a home automation system with a hybrid Clojure + Python architecture:

- **Clojure** (`src/radiale/`): Orchestration, state management, scheduling, event routing
- **Python** (`radiale/`): Device integrations (ESPHome, deCONZ, MQTT, Chromecast, mDNS)
- **Bridge**: Babashka pods connect the two via bencode serialization over stdio

## Repository Structure

```
radiale/
├── src/radiale/           # Clojure source code
│   ├── core.clj           # Main entry point, event loop, pod initialization
│   ├── state.clj          # Application state atom and watchers
│   ├── schedule.clj       # Time-based scheduling (cron, solar, delays)
│   ├── scheduler.clj      # core.async job scheduler implementation
│   ├── watch.clj          # Message pattern matching
│   ├── esp.clj            # ESPHome wrapper
│   ├── deconz.clj         # deCONZ/Zigbee wrapper
│   ├── chromecast.clj     # Chromecast wrapper
│   └── influx.clj         # InfluxDB (stub)
├── radiale/               # Python package
│   ├── pod.py             # Babashka pod interface (dispatch, bencode)
│   ├── esphome.py         # ESPHome client (aioesphomeapi)
│   ├── deconz.py          # deCONZ REST API + WebSocket
│   ├── mqtt.py            # MQTT client (aiomqtt)
│   ├── mdns.py            # mDNS discovery (zeroconf)
│   ├── chromecast.py      # Chromecast client (dmcast)
│   ├── schedule.py        # Solar/cron time calculations (astral)
│   └── logging.py         # Systemd-aware logging
├── test/radiale/          # Clojure tests (clojure.test)
│   └── *_test.clj
├── tests/radiale/         # Python tests (pytest)
│   └── test_*.py
├── docs/                  # Documentation (Diataxis structure)
├── config/                # User configuration (not in repo)
├── deps.edn               # Clojure dependencies
├── setup.py               # Python package definition
├── bb.edn                 # Babashka configuration
├── pod-xlfe-radiale.py    # Pod entry point script
├── start.sh               # Application startup script
└── run-tests.sh           # Combined test runner
```

## Key Architecture Concepts

### Event-Driven Message Passing

All events flow through a central `core.async` channel in `core.clj`:

```clojure
;; Messages are maps with these key patterns:
{:fn some-function        ; Function to execute
 ::rc/then {...}          ; Action after completion
 ::rc/desc "description"  ; For logging
 ::rc/at-most-once :id}   ; Deduplication key
```

### State Management

Single atom holds all state (`state.clj`):

```clojure
{:deconz {:lights {...} :sensors {...}}
 :esphome {:device-name {...}}
 :mqtt {"topic" {:payload ...}}
 :schedules {...}
 :watchers {...}}
```

### Pod Communication

Clojure calls Python via `pod.xlfe.radiale/*` functions. The pod is initialized lazily in `core.clj:init-pod!`.

**Important**: Pod loading is deferred. Functions in `radiale-map` use wrappers that check if the pod is initialized.

## Running the Project

```bash
# Start the application
./start.sh

# Or directly
clojure -i config/setup.clj
```

## Running Tests

```bash
# All tests
./run-tests.sh

# Python only
pytest tests/ -v

# Clojure only
clojure -M:test

# Single Python test
pytest tests/radiale/test_pod.py::test_eprint -v
```

## Making Changes

### Clojure Changes

1. Edit files in `src/radiale/`
2. Run `clojure -M:test` to verify
3. Namespaces follow `radiale.*` pattern
4. Tests go in `test/radiale/*_test.clj`

### Python Changes

1. Edit files in `radiale/`
2. Run `pytest tests/` to verify
3. Tests go in `tests/radiale/test_*.py`
4. Use `unittest.mock` for mocking external dependencies

### Adding a New Device Integration

1. Create Python module `radiale/newdevice.py`
2. Add operations to `radiale/pod.py` dispatch table
3. Create Clojure wrapper `src/radiale/newdevice.clj`
4. Add tests for both
5. Document in `docs/how-to/configure-newdevice.md`

## Code Patterns

### Clojure Patterns

```clojure
;; Namespace declaration
(ns radiale.example
  (:require [radiale.core :as rc]
            [clojure.core.async :as async]))

;; Function that returns a message for the event loop
(defn do-something [state send-chan params]
  {:fn another-function
   ::rc/then {...}})

;; Using with-redefs for testing
(with-redefs [pod.xlfe.radiale/some-fn (constantly "mocked")]
  (is (= expected (function-under-test))))
```

### Python Patterns

```python
# Async function pattern
async def listen(host, callback):
    """Listen for events and call callback with results."""
    async with some_client(host) as client:
        async for event in client.events():
            callback({"type": "event", "data": event})

# Pod operation handler
def handle_operation(args):
    """Synchronous wrapper that schedules async work."""
    loop = asyncio.get_event_loop()
    return loop.run_until_complete(async_operation(args))
```

## Common Issues and Solutions

### Test Failures

**Issue**: Clojure tests fail with pod-related errors
**Solution**: Pod loading is now lazy. Tests should work without Python. If not, check `init-pod!` isn't being called at load time.

**Issue**: Python tests fail with import errors
**Solution**: Install package in editable mode: `pip install -e .`

**Issue**: `test_eprint` fails in CI
**Solution**: Mock `_SYSTEMD_JOURNAL` - CI has `JOURNAL_STREAM` set. See existing test for pattern.

### Runtime Issues

**Issue**: "Pod not initialized" error
**Solution**: Ensure `init-pod!` is called before using pod functions. The `run` function does this.

**Issue**: Devices not discovered
**Solution**: Check mDNS is working (`avahi-browse -art`), verify network connectivity.

## Dependencies

### Clojure (deps.edn)

- `org.clojure/clojure` 1.11.1
- `org.clojure/core.async` 1.5.648
- `com.taoensso/timbre` 5.2.1 (logging)
- `babashka/babashka.pods` (pod interface)

### Python (setup.py)

- `aioesphomeapi` - ESPHome native API
- `dmcast` - Chromecast (installed from git)
- `zeroconf` - mDNS discovery
- `aiomqtt` - MQTT client
- `websockets`, `aiohttp` - deCONZ
- `astral` - Solar calculations
- `pytz` - Timezone handling
- `bcoding` - Bencode for pod communication
- `protobuf>=5.0.0` - Required by dmcast

## CI/CD

GitHub Actions workflow (`.github/workflows/ci.yml`):
- Triggers on push/PR to main
- Sets up Java 17, Python 3.9, Clojure CLI
- Runs `./run-tests.sh`

## Documentation

Documentation follows [Diataxis](https://diataxis.fr/) in `docs/`:

- `tutorials/` - Learning-oriented guides
- `how-to/` - Task-oriented guides  
- `reference/` - Technical reference
- `explanation/` - Design rationale

When adding features, update relevant docs.

## Commit Guidelines

- Run tests before committing
- Keep commits focused (one logical change)
- Use descriptive commit messages
- Don't commit secrets or credentials

## Key Files to Understand

| File | Why It Matters |
|------|----------------|
| `src/radiale/core.clj` | Main entry point, event loop, pod init |
| `radiale/pod.py` | Python pod interface, operation dispatch |
| `deps.edn` | Clojure deps and test alias |
| `setup.py` | Python package and dependencies |
| `run-tests.sh` | How tests are executed |

## Quick Reference

| Task | Command |
|------|---------|
| Run app | `./start.sh` |
| Run all tests | `./run-tests.sh` |
| Run Python tests | `pytest tests/ -v` |
| Run Clojure tests | `clojure -M:test` |
| Install Python deps | `pip install -e .` |
| Download Clojure deps | `clojure -P` |
