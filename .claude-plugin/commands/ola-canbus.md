---
allowed-tools: Read, Glob, Grep, Bash(python scripts/ola.py shell *), Bash(python scripts/ola.py shell)
description: Read the Ola S1 CAN bus from the head unit
user-invocable: true
argument-hint: "<candump|logcat|dump> --ipv6 <addr>"
argument: how to read the bus
---

# /ola-canbus

Read the Ola S1 CAN bus from the head unit. Everything the scooter knows about
itself travels on it; the head unit reads it, it does not invent it.

## Instructions

1. Pick a read path. From a root shell (`python scripts/ola.py shell --ipv6 <addr>`):

   ```sh
   candump vcan0              # raw frames, simplest
   ```

   Or from the gRPC channel:

   ```sh
   python scripts/ola.py shell --ipv6 <addr> "logcat | grep -i can"
   ```

2. The paths, in order of usefulness:

   | Path | What you get | Notes |
   |---|---|---|
   | `candump vcan0` | raw frames | simplest, works from root shell |
   | gRPC `CanDumpStream` | the same tap | unfiltered; **this is the path the app uses** |
   | `logcat` from the parser app | every frame the app parsed | full rate, no wedge risk |
   | gRPC `ReadCanStream` | frames after `Init`, in theory | stayed silent on our unit even after `Init`; prefer the dump tap |
   | gRPC `ReadCanFrame` | only IDs you subscribed to with `Init` | blocks forever if none arrive |

   **Do not assume a silent `ReadCanStream` means a silent bus.** On the test unit
   it returned zero frames even after `Init` with the live IDs, while
   `CanDumpStream` delivered the same trickle `candump` showed.

3. Use `app/app/src/main/assets/can_database.json` as the map — ID → meaning →
   how to turn bytes into a number. Treat it as a hypothesis to confirm against a
   live dump, not as ground truth.

4. Follow the loop: `behaviour → hypothesis → smallest test → evidence → read the
   code`, then refine. One working call proves one call, not a schema (docs/10).

Refer to `skills/ola-s1-canbus/SKILL.md` for the full reference.