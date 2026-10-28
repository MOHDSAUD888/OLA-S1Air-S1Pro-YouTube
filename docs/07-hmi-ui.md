# 07 — The HMI: why the screen behaves like that

The dash UI is not a set of Android screens. It is one giant **Kanzi** scene
graph rendered into a single surface. Once you know that, most of the weird
behaviour stops being weird.

---

## One app, one activity

`AndroidManifest.xml` of `com.olaelectric.hmiclient` declares exactly one
activity: `com.olaelectric.hmiclient.MainActivity`.

| Property | Value |
|---|---|
| package | `com.olaelectric.hmiclient` |
| activity | `MainActivity` (the only one) |
| categories | `HOME`, `LAUNCHER`, `DEFAULT` |
| `sharedUserId` | `android.uid.system` |
| flags | `keepScreenOn`, `largeHeap`, `extractNativeLibs`, `usesCleartextTraffic` |

Two consequences:

1. **It is the home screen.** There is no stock launcher behind it. Kill the app
   and you get a white framebuffer, not a home screen.
2. **It runs as the system uid.** It can inject input and clear other apps' data
   without root. That is a deliberate design choice by Ola, not a bug.

---

## The renderer

Kanzi draws everything. The resources live in 11 bundles:

| Bundle | Size |
|---|---|
| `assets/ola_electric.kzb` | **643.86 MB** |
| `DefaultTheme=BharatMood_DarkMode.kzb` | 34.18 MB |
| `DefaultTheme=BoltDarkMode.kzb` | 18.63 MB |
| `DefaultTheme=BoltLightMode.kzb` | 16.22 MB |
| `DefaultTheme=VintageDarkMode.kzb` | 12.89 MB |
| `DefaultTheme=VintageLightMode.kzb` | 7.14 MB |
| `DefaultTheme=CareGulmoharDarkMode.kzb` | 4.90 MB |
| `DefaultTheme=DarkMode.kzb` | 2.99 MB |
| `DefaultTheme=CareGulmoharLightMode.kzb` | 2.42 MB |
| `DefaultTheme=BharatMood_LightMode.kzb` | 2.03 MB |
| `DefaultTheme=LightMode.kzb` | 1.68 MB |

A plain-text index, `ola_electric.kzb.txt`, lists **20,118 resources**, including
**1,945 screens**.

That is why the app holds ~410 MB of RAM even when it is not visible: the big
bundle is memory-mapped and never fully released.

---

## Everything is one tree

All 1,943 real screens hang off a single node:

```
Screens/Screen/RootPage/...
```

There is no Android activity per screen. Switching screens is a **page
transition** inside one scene graph.

The biggest roots:

| Root | Nodes | What it is |
|---|---|---|
| `PopupContainer` | 897 | every popup in the product |
| `ConcertScreens` | 176 | party / concert mode |
| `ContactsList` | 136 | phone contacts |
| `NavigationHost` | 117 | map host |
| `SettingsScreens` | 105 | settings tree |
| `MainScreen` | 91 | the riding cluster |
| `Status_Bar` | 15 | top bar |

`PopupContainer` being the largest tells you something useful: **almost every
message the rider sees is a prefab toggled by a boolean**, not a screen. That is
why popups appear instantly while screens animate.

---

## The data model

20 **data contexts** (Java classes) feed the UI. Each is declared as an XML asset
and bound into the scene graph. The UI does not query Android services directly;
it reads these contexts.

Practical effect: if a value looks stale, the context did not update — usually
because the HMI was in the background. Writes through `runOnKanziThread` are
dropped when the view is not in the foreground.

That is the cause of "I changed something and the dash still shows the old value".

---

## Why `input tap` does nothing

This is the single most frustrating thing about the platform, and it is by
design.

`MainActivity.onTouchEvent` does this:

```java
public boolean onTouchEvent(MotionEvent e) { ... processMotionEvent(e) ... }

private boolean isOnTopOfKanziUIElement(MotionEvent e)   // hit-test scene graph
private void injectEventToVirtualDisplay(MotionEvent e)  // for embedded apps
```

`injectEventToVirtualDisplay` uses reflection into hidden framework APIs to set a
**display id** and re-inject the event:

```java
setMotionEventDisplayId.invoke(ev, view.getVirtualDisplayId());
injectInputEvent.invoke(mInputManager, ev, 0);
```

So the real path is:

```
finger → /dev/input/event2 (Atmel maXTouch)
       → Android input pipeline
       → MainActivity.onTouchEvent
       → Kanzi hit-test  (or re-inject into a virtual display)
```

`adb shell input tap` writes into the input pipeline for **display 0**. Kanzi
hit-tests its own graph and expects the display-id retargeting. Injected events
either never reach it or arrive without the right display id.

Result:

| Target | `input tap` works? |
|---|---|
| Settings, dialogs, normal apps | **Yes** |
| The Kanzi dash | **No** |
| The passcode keypad (Kanzi) | **No** |

This was tested both ways, with a controlled A/B: synthetic touch events do reach
Android (InputReader sees them), but the Kanzi UI does not react.

There is no software route in. Use real touch, `am start` for intents, or
`input keyevent` for system keys.

---

## Embedded Android surfaces

The HMI is a hybrid. `OverlayKanziView` can host:

- `ActivityViewController` — a generic Android activity inside a view
- `DuxActivityView` — the "Dux" app surface, exposed as `DuxHost`

These render into **virtual displays** and get touch re-injected per display id.
That is why the manifest needs `INJECT_EVENTS` and `android.uid.system`.

When no embedded app is running, touches go straight to the Kanzi graph.

---

## Themes

Five moods × light/dark:

`Bolt`, `Care`, `Eclipse`, `Vintage`, `Bharat`

Each theme pack ships a full-screen `.mp4` background behind the dial. Sizes
range from ~0.8 MB to ~2.7 MB.

Two facts worth knowing:

- A theme switch reloads a multi-MB `.kzb` **and** restarts an `.mp4`. It is the
  heaviest UI operation in the product, which is why it lags.
- **Eclipse ships videos but no `.kzb`** in this build. The mood exists in the
  theme group but cannot actually be selected on this firmware.

Selection is driven by two ints in `VehicleDataContext`:
`CurrentActiveThemeValue` and `CurrentActiveMood`.

---

## The white screen

| What you see | Cause |
|---|---|
| Flat white `(242,243,244)` | normal idle / locked state |
| White, no UI, ~2.6 KB screenshot | display asleep or HMI surface gone |
| Full dash | awake and rendering |
| Black | display off (5 s hold on the red button) |

Because the HMI app is `HOME` and there is no launcher behind it, a dead HMI
leaves a blank framebuffer. The fix is to restart it:

```powershell
py scripts\ola.py hmi-restart --ipv6 fe80::1%6
```

Screenshot size is a cheap state check:

| PNG size | State |
|---|---|
| ~2.6 KB | flat / asleep |
| ~17 KB | keypad |
| > 200 KB | full UI |

---

## What this means for automation

| Goal | Method |
|---|---|
| Open an Android screen | `am start -a ...` |
| Go back to the dash | `input keyevent 3` (HOME) |
| Wake the panel | `input keyevent 224` |
| Press a dash button | real finger, or a CAN command the UI listens for |
| Read what is on screen | `screencap -p`, then look at the PNG |
| Read UI state properly | read the data context via logcat, not the pixels |

Trying to drive Kanzi with coordinates will waste your day. It is not a matter of
finding the right tap duration.

---

## Extracting the UI assets

If you want to look inside `ola_electric.kzb`:

- the `.kzb` is a Kanzi binary bundle; `ola_electric.kzb.txt` is its plain-text
  index and is readable without any tool
- the index alone gives you every screen name, prefab name and texture name
- theme `.mp4` files are ordinary MP4s and can be played directly

The index is the cheap win. Read it before you try to parse the binary bundle.
