# State Management

This document explains how radiale manages application state, why the design was chosen, and how state changes propagate through the system.

## Overview

Radiale uses a single Clojure atom to hold all application state. Changes to this state emit events that can trigger automations.

```
┌─────────────────────────────────────────────────────────┐
│                     state* atom                          │
│  {:deconz   {...}                                       │
│   :esphome  {...}                                       │
│   :mqtt     {...}                                       │
│   :schedules {...}                                      │
│   :watchers {...}}                                      │
└────────────────────────┬────────────────────────────────┘
                         │
                         │ watch-state
                         ▼
┌─────────────────────────────────────────────────────────┐
│                  Change Detection                        │
│  Compare old → new, emit events for differences         │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                  Event Channel                           │
│  {::domain :deconz                                      │
│   ::ident  :lights/kitchen                              │
│   ::prop   :state                                       │
│   ::prev   {:on false}                                  │
│   ::now    {:on true}}                                  │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                  Watcher Matching                        │
│  Find watchers registered for this path                 │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                  Action Execution                        │
│  Execute ::then actions for matched watchers            │
└─────────────────────────────────────────────────────────┘
```

## Why a Single Atom?

### The Problem

Home automation state is interconnected:
- A motion sensor state affects light decisions
- Time of day affects which rules apply
- Device availability affects automation behavior

Distributed state leads to:
- Synchronization bugs
- Inconsistent reads
- Complex update coordination

### The Solution

A single atom provides:
- **Atomic updates**: All changes are consistent
- **Snapshot reads**: `@state*` gives a consistent view
- **Built-in watching**: Clojure atoms support watchers
- **Thread safety**: No locks needed

### Trade-offs

**Advantages**:
- Simple mental model
- No race conditions
- Easy to debug (inspect entire state)
- Time-travel debugging possible

**Disadvantages**:
- Memory overhead for large states
- All updates serialize through one point
- Large state trees can be verbose

## State Structure

The state atom has a hierarchical structure:

```clojure
{;; Device states by domain
 :deconz
 {:lights
  {:kitchen {:id "1"
             :type "Extended color light"
             :state {:on true :bri 254 :ct 350}
             :config {:startup {:mode "lastonstate"}}}}
  :sensors
  {:motion-1 {:id "5"
              :type "ZHAPresence"
              :state {:presence true :lastupdated "2025-01-26T10:30:00"}
              :config {:on true :reachable true}}}}
 
 :esphome
 {:living-room
  {:info {:name "living-room" :mac "AA:BB:CC:DD:EE:FF"}
   :switch {:relay_1 {:key 123 :state true}}
   :sensor {:temperature {:key 456 :state 22.5}}}}
 
 :mqtt
 {"sensors/outdoor/temperature" {:value 15.2 :unit "C"}
  "homeassistant/status" "online"}
 
 ;; Internal state
 :schedules
 {:sunset-lights {:id :sunset-lights
                  :next-run 1706300000000
                  :desc "Turn on lights at sunset"}}
 
 :watchers
 {:motion-light {:path [:deconz :sensors :motion-1 :state :presence]
                 :then {...}}}}
```

### Path Convention

State is accessed via paths (vectors of keys):

```clojure
;; Path: [:deconz :lights :kitchen :state :on]
;; Value: true

(get-in @state* [:deconz :lights :kitchen :state :on])
;; => true
```

## Change Detection

When state changes, `watch-state` compares old and new values to detect what changed.

### The `unpack` Function

```clojure
(defn unpack [old-state new-state]
  ;; Returns sequence of changes:
  ;; [{:domain :deconz :ident :kitchen :prop :state :prev {...} :now {...}}
  ;;  ...]
  )
```

### Change Granularity

Changes are detected at the property level:

```clojure
;; Old state
{:deconz {:lights {:kitchen {:state {:on false :bri 100}}}}}

;; New state  
{:deconz {:lights {:kitchen {:state {:on true :bri 254}}}}}

;; Detected change
{::domain :deconz
 ::ident :lights/kitchen
 ::prop :state
 ::prev {:on false :bri 100}
 ::now {:on true :bri 254}}
```

## Watcher System

Watchers register interest in specific state paths and execute actions when those paths change.

### Registering a Watcher

```clojure
{:fn radiale.watch/add-watch
 :path [:deconz :sensors :motion-1 :state :presence]
 ::rc/at-most-once :motion-light
 ::rc/then {:fn radiale.deconz/put
            ::rc/ident :lights/hallway
            ::rc/state {:on true}}}
```

### Watcher Matching

When a change event arrives, `match-message` finds watchers whose path matches:

```clojure
;; Event
{::domain :deconz
 ::ident :sensors/motion-1
 ::prop :state
 ::prev {:presence false}
 ::now {:presence true}}

;; Watcher path
[:deconz :sensors :motion-1 :state :presence]

;; Match! Execute ::then action
```

### Path Matching Rules

1. **Exact match**: Path must match exactly
2. **Wildcard**: `:*` matches any key at that level (if implemented)
3. **Prefix**: Watching `[:deconz]` matches all deconz changes

## State Update Patterns

### Direct Update (Internal)

```clojure
(swap! state* assoc-in [:deconz :lights :kitchen :state] {:on true})
```

### Via Message (Recommended)

```clojure
{:fn (fn [state chan _ _]
       (swap! state assoc-in [:custom :data] "value")
       nil)}
```

### From Device Callback

Python modules send callbacks that update state:

```clojure
;; Callback from deconz.py
{:type :state-change
 :domain :deconz
 :resource "lights"
 :id "1"
 :state {:on true}}

;; Processed by core.clj to update state
```

## Concurrency Considerations

### Thread Safety

Clojure atoms are thread-safe:
- `swap!` retries on conflict
- `deref` always returns consistent value
- Watchers fire after successful update

### Update Ordering

Updates are serialized:
1. `swap!` applies update function
2. Watcher fires with old and new values
3. Change events go to channel
4. Event loop processes sequentially

### Avoiding Infinite Loops

Watchers can trigger actions that change state, which could trigger the same watcher.

**Prevention strategies**:
1. Check if state actually changed before acting
2. Use `::rc/at-most-once` to deduplicate
3. Design watchers to be idempotent

```clojure
;; Safe: Only act on transition to true
::rc/then (fn [_ _ old new]
            (when (and (:presence new) (not (:presence old)))
              {:fn turn-on-light}))
```

## Debugging State

### Inspect Current State

```clojure
;; In REPL
@radiale.state/state*

;; Pretty print
(clojure.pprint/pprint @radiale.state/state*)
```

### Watch Changes

```clojure
;; Add debug watcher
(add-watch radiale.state/state* :debug
  (fn [_ _ old new]
    (println "State changed!")
    (println "Diff:" (clojure.data/diff old new))))
```

### Log State Path

```clojure
;; In configuration
{:fn (fn [state _ _ _]
       (println "Kitchen light:" 
                (get-in state [:deconz :lights :kitchen :state]))
       nil)}
```

## Design Decisions

### Decision: Flat Watcher Registration

**Rationale**: Watchers are stored in the state atom itself under `:watchers`. This keeps all application state in one place.

**Alternative considered**: Separate atom for watchers.

**Why rejected**: Adds complexity, no clear benefit.

### Decision: Path-Based Matching

**Rationale**: Paths provide precise, composable state addressing.

**Alternative considered**: Regex or glob patterns.

**Why rejected**: Less readable, harder to optimize.

### Decision: Emit All Changes

**Rationale**: Let watchers filter, rather than pre-filtering at emission.

**Alternative considered**: Only emit for registered paths.

**Why rejected**: Limits flexibility, harder to add new watchers dynamically.

## See Also

- [Architecture](architecture.md) - Overall system design
- [Message Format](../reference/message-format.md) - Event structure
- [Configuration Reference](../reference/configuration.md) - Watcher configuration
