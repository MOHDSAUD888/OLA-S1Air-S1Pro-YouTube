---
name: ola-s1-youtube
description: Run YouTube on the Ola S1 dashboard: the floating olatube overlay and the SmartTube picture-in-picture route. Use when the user wants to watch video on the dash, install or configure the overlay app, or understand how an Android app draws over the HMI. Trigger: YouTube on dash, olatube, WebPlayerService, OverlayService, SmartTube PIP, floating player, overlay app, draw over other apps, SYSTEM_ALERT_WINDOW, BootReceiver, accessibility service.
---

# Ola S1 — YouTube on the dashboard

The headline hack: **real YouTube, full-screen on the Ola S1's dash** — the same
screen that normally only shows speed, battery and maps. The dashboard is Android
under the HMI, so once you have root and adb it runs ordinary apps.

Two ways, both built on the root access documented in the connect skill:

## Route 1 — the `olatube` overlay

`app/overlay` is a floating web player (`WebPlayerService` / `OverlayService`)
with an accessibility helper (`YtAccessibilityService`) that starts on boot
(`BootReceiver`) and draws over the HMI. One-time setup via `SetupActivity`.

Components:

| Component | Role |
|---|---|
| `SetupActivity` | Tiny launcher: grants the overlay permission, starts the service, exits |
| `OverlayService` | The system overlay window itself — a foreground service of type `specialUse` so the dash doesn't reap it |
| `WebPlayerService` | The floating WebView player: `https://m.youtube.com`, with a draggable title bar, a resize grip, a fullscreen toggle, and a close button |
| `YtAccessibilityService` | Lets the floating controls send BACK to SmartTube and reports which app is in front |
| `BootReceiver` | Re-adds the overlay after a reboot |

It loads the mobile YouTube site, so **no Google Play Services / System WebView is
needed** — but note the dash ships no WebView provider, so `WebPlayerService`
fails softly ("No WebView on this device - player unavailable") instead of
crashing the whole app.

Permissions it needs: `SYSTEM_ALERT_WINDOW`, `RECEIVE_BOOT_COMPLETED`,
`FOREGROUND_SERVICE`, `KILL_BACKGROUND_PROCESSES` (CLOSE reclaims SmartTube's RAM
once it is in the background), `INTERNET`.

## Route 2 — SmartTube + picture-in-picture

A sideloaded YouTube client (e.g. SmartTube) running in a PIP window, for a
native-app feel. The overlay's `KILL_BACKGROUND_PROCESSES` permission exists to
reclaim SmartTube's RAM once it is in the background.

## How it is possible

The dash is a normal Android head unit sitting behind Ola's HMI. Get root + adb
(with the connect skill) and it launches and draws any Android app you install —
YouTube included.

## When to use this skill

- User wants to watch video on the dash.
- User wants to install, build, or configure the `olatube` overlay.
- User wants to understand how an app draws over the HMI.
- User is debugging the overlay (no WebView, overlay permission, boot receiver).
- User is choosing between the overlay and SmartTube PIP.