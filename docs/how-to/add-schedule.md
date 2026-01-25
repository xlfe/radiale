# How to Add Schedules

Create time-based automations using cron expressions, solar events, or simple delays.

## Problem

You want to run automations at specific times, such as turning on lights at sunset or running a task every hour.

## Prerequisites

- Radiale is installed ([Getting Started](../tutorials/getting-started.md))
- For solar schedules: know your latitude, longitude, and timezone

## Steps

### 1. Schedule with Cron Expression

Run a task on a cron schedule:

```clojure
{:fn radiale.schedule/crontab
 ::rc/desc "Turn off lights at midnight"
 ::rc/crontab "0 0 * * *"           ; midnight daily
 ::rc/at-most-once :midnight-lights-off
 ::rc/then {:fn radiale.deconz/put
            ::rc/ident :lights/all
            ::rc/state {:on false}}}
```

Common cron patterns:
- `0 * * * *` - Every hour
- `*/15 * * * *` - Every 15 minutes
- `0 8 * * 1-5` - 8 AM on weekdays
- `0 0 * * *` - Midnight daily

### 2. Schedule at Solar Events

Trigger actions at sunrise, sunset, or twilight:

```clojure
{:fn radiale.schedule/solar
 ::rc/desc "Sunset lights on"
 ::rc/params {:event "sunset"
              :lat 51.5074          ; London latitude
              :lon -0.1278          ; London longitude  
              :tz "Europe/London"}
 ::rc/at-most-once :sunset-lights
 ::rc/then {:fn radiale.deconz/put
            ::rc/ident :lights/outdoor
            ::rc/state {:on true :bri 254}}}
```

Available solar events:
- `"sunrise"` / `"sunset"`
- `"dawn"` / `"dusk"` (civil twilight)
- `"noon"` (solar noon)

### 3. Run After a Delay

Execute once after a specified delay:

```clojure
{:fn radiale.schedule/after
 ::rc/desc "Delayed notification"
 ::rc/seconds 300                   ; 5 minutes
 ::rc/at-most-once :delayed-task
 ::rc/then {:fn my-notification-fn}}
```

### 4. Run Periodically

Execute repeatedly at fixed intervals:

```clojure
{:fn radiale.schedule/every
 ::rc/desc "Health check"
 ::rc/seconds 60                    ; every minute
 ::rc/at-most-once :health-check
 ::rc/then {:fn check-system-health}}
```

### 5. Combine Schedules with Conditions

Add logic to scheduled actions:

```clojure
{:fn radiale.schedule/solar
 ::rc/desc "Conditional sunset lights"
 ::rc/params {:event "sunset" :lat 51.5 :lon -0.1 :tz "Europe/London"}
 ::rc/at-most-once :smart-sunset
 ::rc/then (fn [state _ _ _]
             ;; Only if someone is home
             (when (get-in state [:presence :home])
               {:fn radiale.deconz/put
                ::rc/ident :lights/living-room
                ::rc/state {:on true}}))}
```

## Key Concepts

### The `::rc/at-most-once` Key

This prevents duplicate schedules. Use a unique keyword for each schedule:

```clojure
::rc/at-most-once :my-unique-schedule-id
```

If radiale restarts, schedules with the same ID won't be duplicated.

### The `::rc/then` Key

Specifies what happens when the schedule triggers. Can be:

- A map with `::fn` - calls the specified function
- A function - called with `(state send-chan old-val new-val)`
- A vector - sequence of actions to execute

## Troubleshooting

### Schedule not firing

- Verify the cron expression is correct (use [crontab.guru](https://crontab.guru/))
- Check logs for scheduling errors
- Ensure `::rc/at-most-once` ID is unique

### Solar times seem wrong

- Verify latitude/longitude are correct (use [latlong.net](https://www.latlong.net/))
- Check timezone string is valid (e.g., "America/New_York", not "EST")
- Solar calculations use the `astral` Python library

### Duplicate executions

- Add `::rc/at-most-once` with a unique ID
- Check you're not adding the same schedule multiple times in config

## See Also

- [Configuration Reference](../reference/configuration.md) - Full configuration options
- [Clojure API](../reference/clojure-api.md) - Schedule namespace reference
- [Architecture](../explanation/architecture.md) - How scheduling works internally
