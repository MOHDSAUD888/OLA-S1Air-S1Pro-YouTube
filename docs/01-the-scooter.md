# 01 — The scooter, in plain words

## Two computers, not one

The thing on the handlebar is a **screen with an Android computer inside**. It
does not drive the motor. The motor, battery, brakes and locks are controlled by
separate small computers on a **CAN bus**.

```
┌──────────────────────────────────────────┐
│ Head unit (Android)                      │
│  - draws the UI                          │
│  - talks to Ola cloud over 4G            │
│  - bridges the vehicle CAN bus           │
│  - runs the diagnostic daemon (port 50005)│
└───────────────────┬──────────────────────┘
                    │  vcan0  (UART → CAN)
┌───────────────────┴──────────────────────┐
│ Vehicle CAN bus                          │
└───┬─────────┬─────────┬─────────┬────────┘
    │         │         │         │
   VCU       BMS       BCM       BLE
  motor    battery    body     bluetooth
```

This split explains almost everything:

- The dash can **show** the battery but cannot **change** it.
- The dash can **ask** the vehicle to unlock, but the vehicle must be awake to
  answer.
- If the dash app dies, the scooter still works. You just lose the screen.
- If the vehicle is asleep, the dash is alive but sees almost no CAN traffic.

---

## Hardware

| Item | Value |
|---|---|
| Platform | MediaTek MT6765 / **MT8768** (automotive MT6762 variant) |
| CPU | 8× ARM Cortex-A53, 64-bit |
| RAM | 2.9 GB |
| Storage | eMMC; `/data` 12 GB |
| Screen | **800 × 480**, density 280 |
| OS | **Android 13** (SDK 33) |
| Kernel | 4.19.191, aarch64 |
| Wi-Fi | `wlan0` (station) + `ap0` (hotspot, when enabled) |
| Cellular | own 4G link (Jio), independent of Wi-Fi |
| Extra | GPS, Bluetooth, speaker, mic |

The head unit has **its own 4G connection**. It is online even when your phone is
not nearby. That link is used for cloud commands, OTA and telemetry.

---

## Software

Build fingerprint: `...:user/test-keys`, build date 2025-05-19.

| Item | Value |
|---|---|
| Android | 13, `TP1A.220624.014` |
| Security patch | **2023-09-05** |
| Build tags | **test-keys** (not release-keys) |
| `ro.debuggable` | **1** |
| SELinux | **Permissive** |
| Verified boot | green, bootloader locked |
| Encryption | file-based encryption on |

So the image is signed and the bootloader is locked — nothing was flashed — but
the **running policy** is wide open. That is why a root process can read
anything.

---

## The apps that matter

| Package | Short name | Job |
|---|---|---|
| `com.olaelectric.hmiclient` | HHMIC | the whole UI (Kanzi) |
| `com.android.hmiserverapp` | HHMIS | server half of the UI, state machine |
| `com.olaelectric.vehicleparser` | HVDP | parses CAN, talks to vehicle |
| `com.olaelectric.profilemanagerservice` | HPMS | rider profiles, passcode |
| `com.olaelectric.ota` | HOTA | updates |
| `com.olaelectric.cloudconnectivitymanager` | HCCM | cloud link |
| `com.olaelectric.vehiclestatemanager` | HVSM | vehicle state |
| `com.olaelectric.connectivityservice` | HSSS | network |
| `com.ola.mfgapp` | HMFG | technician / diagnostic app |

All of these are **platform-signed system apps** in `/system/priv-app`.

The HMI app is special: it declares `android.intent.category.HOME`. There is no
normal Android launcher behind it. If it stops, you get a blank screen, not a
home screen.

---

## The CAN bridge

The vehicle bus is not exposed to Android directly. A small converter bridges it
to a virtual CAN interface:

```
vehicle CAN wire → UART converter → vcan0 (Android)
```

From a root shell you can watch it with `candump vcan0` or send with
`cansend vcan0 ...`.

| State | What you see |
|---|---|
| Awake | ~188 IDs, ~970 frames/s |
| Asleep | ~2 frames/s, only a trickle: `6D2`, `643`, `6DB`, `66D`, `65D` |

Quick sleep test:

```sh
timeout 3 candump vcan0 | wc -l
# < 100  -> asleep
# ~3000  -> awake
```

---

## Sleep and wake: the most important rule

**The head unit cannot wake the scooter.**

Wake is owned by the vehicle. The BMS recognises only these wake reasons:

| Reason | Source |
|---|---|
| `Charger_CAN_Wakeup` | charger plugged in |
| `IMU_Wakeup` | movement / tamper |
| `SoC_Push_Button_Wakeup` | the red Start/Stop button |
| `RTC_Wakeup` | clock / schedule |
| `EOL_WakeUp` | end-of-line factory tool |

There is no "wake" command in the HMI→vehicle command set. A CAN frame from the
dash cannot substitute for one of these.

This is why:

- a correct passcode can fail (the vehicle is asleep and cannot ACK),
- remote unlock from the dash is impossible,
- the screen can be white while the Android side is perfectly alive.

---

## Screen states you will see

| What you see | What it means |
|---|---|
| Flat white `(242,243,244)` | normal idle / locked screen |
| White with no UI, ~2.6 KB screenshot | display asleep or HMI surface gone |
| Keypad with 0–9 | passcode window is open |
| Full dash UI | awake and rendering |
| Black `(0,0,0)` | display off (long-press red button) |

A screenshot is a cheap state check:

| PNG size | State |
|---|---|
| ~2.6 KB | flat / asleep |
| ~17 KB | keypad |
| > 200 KB | full UI |

---

## What the owner controls

- The red Start/Stop button: short press wakes / asks for passcode; **hold ~5 s
  locks the scooter and turns the display off**.
- The phone app: talks to Ola cloud, not to the dash.
- Settings on the dash: theme, regen dial, toggles, etc.

The dash is a **terminal**, not the authority. Keep that in mind for every
finding that follows.
