---
name: ola-s1-canbus
description: Read the Ola S1 CAN bus from the head unit: which IDs carry what, how to turn bytes into numbers, and the parser app that decodes them. Use when the user wants CAN frames, vehicle telemetry (speed, battery, faults), the CAN map, or to understand what the scooter reports about itself. Trigger: CAN bus, candump vcan0, CAN frames, Ola CAN IDs, vehicle telemetry, can_database.json, fault codes, BMS cells, scooter data, decode CAN.
---

# Ola S1 — The CAN bus

Everything the scooter knows about itself travels on a CAN bus. The head unit
does not invent those numbers — it reads them. This page is the map.

## How the bus reaches Android

```
vehicle CAN wire
      │
      ▼
UART converter  /dev/ttyS1 @ 921600
      │
      ▼
vcan0   (virtual CAN interface inside Android)
```

`vcan0` is not filtered. If it is quiet, the vehicle really is quiet — not a
cable fault and not a missing ID.

## Ways to read it

| Path | What you get | Notes |
|---|---|---|
| `candump vcan0` | raw frames | simplest, works from root shell |
| gRPC `CanDumpStream` | the same tap | unfiltered; **this is the path the app uses** |
| gRPC `ReadCanStream` | frames after `Init`, in theory | stayed silent on our unit even after `Init`; prefer the dump tap |
| gRPC `ReadCanFrame` | only IDs you subscribed to with `Init` | blocks forever if none arrive |
| `logcat` from the parser app | every frame the app parsed | full rate, no wedge risk |

On the unit this was tested against, `ReadCanStream` returned zero frames even
after `Init` with the live IDs, while `CanDumpStream` delivered exactly the same
sleep trickle that `candump` showed. Treat the dump tap as the reliable push
path, and do not assume a silent `ReadCanStream` means a silent bus.

The `logcat` path is underrated. The vehicle parser logs each frame it decodes,
so `logcat` gives you the parsed view at full rate without any wedge risk — and
it shows you the parser's interpretation, which is itself evidence about what
each ID means.

## The map lives in the app

`app/app/src/main/assets/can_database.json` is the decoded CAN map the app ships.
It is the starting point for reading a frame dump: ID → meaning → how to turn
the bytes into a number. Treat it as a hypothesis to confirm against a live dump,
not as ground truth.

## The loop

`behaviour → hypothesis → smallest test → evidence → read the code`. Do not stop
at "it works once". One working call proves one call, not a schema (docs/10).

## When to use this skill

- User wants CAN frames, raw or parsed.
- User wants to know what a CAN ID means.
- User wants vehicle telemetry: speed, battery, state, faults.
- User is decoding `can_database.json` or a `candump`/`logcat` dump.
- User asks about what the scooter reports about itself.