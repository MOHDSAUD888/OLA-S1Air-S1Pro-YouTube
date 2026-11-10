---
allowed-tools: Read, Glob, Grep, Bash(cd app && ./gradlew :overlay:assembleDebug)
description: Run YouTube on the Ola S1 dashboard
user-invocable: true
argument-hint: "<overlay|smartube|build> [--ipv6 <addr>]"
argument: which YouTube route to set up
---

# /ola-youtube

Run real YouTube, full-screen, on the Ola S1's dash.

## Instructions

1. Confirm the prerequisite: root on the head unit (the connect skill). The dash
   is Android under the HMI, so once you have root + adb it runs ordinary apps.

2. Pick a route:

   - **`overlay`** — `app/overlay`, the floating `olatube` player. One-time setup
     via `SetupActivity`, then it draws over the HMI and starts on boot.
   - **`smartube`** — sideload a YouTube client (e.g. SmartTube) in PIP, for a
     native-app feel.

3. To build the overlay:

   ```sh
   cd app && ./gradlew :overlay:assembleDebug
   ```

   JDK 17 + the Gradle wrapper in `app/`; nothing here depends on what's
   installed on the machine.

4. Known gotchas:
   - The dash ships **no Android System WebView provider**, so
     `WebPlayerService` fails softly ("No WebView on this device - player
     unavailable") instead of crashing the whole app.
   - `SetupActivity` must grant the overlay permission once; after that
     `BootReceiver` re-adds it on reboot.
   - The overlay's `YtAccessibilityService` sends BACK to SmartTube and reports
     which app is in front. Enable it once in Settings > Accessibility.

5. Follow the loop: `behaviour → hypothesis → smallest test → evidence → read the
   code`, then refine (docs/10).

Refer to `skills/ola-s1-youtube/SKILL.md` for the full reference.