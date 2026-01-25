# Architecture

This document explains radiale's system design, the rationale behind key decisions, and how the components fit together.

## Overview

Radiale is a home automation system with a hybrid architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                      Clojure Runtime                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │   core.clj  │  │  state.clj  │  │    schedule.clj     │  │
│  │ Event Loop  │◄─┤ State Atom  │  │  Time Scheduling    │  │
│  └──────┬──────┘  └──────▲──────┘  └──────────┬──────────┘  │
│         │                │                     │             │
│         ▼                │                     ▼             │
│  ┌──────────────────────┴─────────────────────────────┐     │
│  │              core.async Channel                     │     │
│  │           (Central Message Bus)                     │     │
│  └──────────────────────┬─────────────────────────────┘     │
│                         │                                    │
│  ┌──────────────────────┴─────────────────────────────┐     │
│  │              Babashka Pod Interface                 │     │
│  │                 (bencode over stdio)                │     │
│  └──────────────────────┬─────────────────────────────┘     │
└─────────────────────────┼───────────────────────────────────┘
                          │
┌─────────────────────────┼───────────────────────────────────┐
│                      Python Runtime                          │
│  ┌──────────────────────┴─────────────────────────────┐     │
│  │                    pod.py                           │     │
│  │          (Operation Dispatch, Async Loop)           │     │
│  └───┬──────────┬──────────┬──────────┬──────────┬───┘     │
│      │          │          │          │          │          │
│      ▼          ▼          ▼          ▼          ▼          │
│  ┌───────┐ ┌────────┐ ┌───────┐ ┌──────┐ ┌───────────┐     │
│  │esphome│ │ deconz │ │ mqtt  │ │ mdns │ │chromecast │     │
│  └───────┘ └────────┘ └───────┘ └──────┘ └───────────┘     │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
              ┌───────────────────────┐
              │    IoT Devices        │
              │ ESPHome, Zigbee, MQTT │
              └───────────────────────┘
```

## Why Clojure + Python?

### The Problem

Home automation requires:
1. **Orchestration**: Managing state, scheduling, event routing
2. **Device Integration**: Protocol-specific communication (APIs, WebSockets, mDNS)

No single language excels at both.

### The Solution

**Clojure** handles orchestration:
- Immutable data structures for reliable state management
- `core.async` for concurrent event processing
- Homoiconic code enables configuration-as-code
- Excellent for complex business logic and rules

**Python** handles device integration:
- Rich ecosystem of IoT libraries (`aioesphomeapi`, `aiomqtt`, `zeroconf`)
- `asyncio` for efficient I/O multiplexing
- Faster iteration for protocol debugging
- Easier community contributions

### The Bridge: Babashka Pods

Babashka pods enable seamless Clojure-Python communication:
- Bencode serialization (simple, binary-safe)
- Stdio-based IPC (no network overhead)
- Async callbacks for streaming data

## Core Design Decisions

### Decision: Event-Driven Architecture

**Rationale**: Home automation is inherently event-driven (sensor triggers, time events, user actions). An event loop naturally models this.

**Implementation**: 
- Central `core.async` channel receives all events
- `try-fn` processes messages recursively
- `::then` chains enable complex workflows

**Trade-offs**:
- (+) Decoupled components, easy to extend
- (+) Natural handling of async operations
- (-) Debugging event flows can be complex
- (-) Stack traces may be less clear

### Decision: Immutable State Atom

**Rationale**: Mutable state is the source of most automation bugs (race conditions, inconsistent reads). Clojure's atom provides atomic updates with immutable snapshots.

**Implementation**:
- Single `state*` atom holds all application state
- Watchers detect changes and emit events
- State paths provide precise change detection

**Trade-offs**:
- (+) Thread-safe by design
- (+) Time-travel debugging possible
- (+) Consistent state snapshots
- (-) Memory overhead for large states
- (-) Learning curve for imperative programmers

### Decision: Configuration-as-Code

**Rationale**: YAML/JSON configuration files are limiting. Clojure EDN allows:
- Functions in configuration
- Conditional logic
- Code reuse via namespaces

**Implementation**:
- Configuration is a vector of Clojure maps
- Maps can reference functions directly
- `::then` can be anonymous functions

**Trade-offs**:
- (+) Extremely flexible
- (+) No separate "automation language"
- (+) Full Clojure power available
- (-) Steeper learning curve
- (-) Configuration errors are runtime errors

### Decision: Lazy Pod Initialization

**Rationale**: Loading the Python pod at namespace load time caused test failures and slow startup.

**Implementation**:
- `init-pod!` explicitly initializes the pod
- Called from `run` function, not at require time
- Wrapper functions in `radiale-map` check pod state

**Trade-offs**:
- (+) Tests can run without Python
- (+) Faster REPL startup
- (+) Clear initialization point
- (-) Slightly more complex function signatures

## Component Responsibilities

### radiale.core

The heart of the system:
- Initializes the pod
- Creates the message channel
- Runs the infinite event loop
- Processes messages via `try-fn`

### radiale.state

State management:
- Holds the `state*` atom
- Watches for changes
- Emits change events with `::domain`, `::ident`, `::prop`

### radiale.schedule

Time-based automation:
- Cron expressions
- Solar events (sunrise, sunset)
- Delays and intervals
- Uses `scheduler.clj` for job management

### radiale.watch

Pattern matching:
- Registers watchers on state paths
- Matches incoming messages
- Triggers actions on matches

### Python Modules

Device communication:
- Each module handles one protocol/device type
- Async I/O for efficient multiplexing
- Callbacks send events to Clojure

## Data Flow Example

Here's how a motion sensor triggers a light:

```
1. Zigbee motion sensor detects motion
   └─► deCONZ gateway receives event

2. deconz.py receives WebSocket message
   └─► Sends callback to Clojure via bencode

3. radiale.core receives callback
   └─► Updates state atom: [:deconz :sensors :motion :state :presence] = true

4. radiale.state watcher detects change
   └─► Emits event: {::domain :deconz ::ident :motion ::prop :presence ...}

5. radiale.watch matches registered watcher
   └─► Returns action: {:fn deconz/put ::ident :lights/hall ::state {:on true}}

6. radiale.core processes action
   └─► Calls pod.xlfe.radiale/put-deconz

7. deconz.py sends REST API request
   └─► Light turns on
```

## Concurrency Model

### Clojure Side

- Single event loop thread processes messages sequentially
- `core.async` `go` blocks for non-blocking operations
- Atom updates are atomic

### Python Side

- Single asyncio event loop
- Coroutines for concurrent I/O
- Thread-safe queue for Clojure communication

### Cross-Language

- Bencode messages are processed sequentially
- Callbacks maintain ordering per operation
- No shared memory between runtimes

## Extension Points

### Adding a New Device Type

1. Create Python module in `radiale/` for device communication
2. Add operations to `pod.py` dispatch table
3. Create Clojure wrapper in `src/radiale/`
4. Add configuration documentation

### Adding a New Automation Type

1. Define message format with required keys
2. Implement handler in `radiale.core` or dedicated namespace
3. Register with the event loop
4. Document configuration options

## See Also

- [State Management](state-management.md) - Deep dive into state handling
- [Message Format](../reference/message-format.md) - Message structure reference
- [Configuration Reference](../reference/configuration.md) - Configuration options
