# 12 — The Android app

One APK. Nine screens. You type one address: the scooter's IPv4.

```
Battery     pack health, cells, ride, charging, safety — live from CAN
Signals     the whole signal table, searchable, live
Faults      active, latched, simulated, and all 194 known faults
Log         the raw frame log and the busiest ids
Settings    open the real Android screens on the dash
ADB         a root shell, plus reversible wireless ADB
Mode        Customer / Service, and the vehicle mode read-only
TechPack    the TechPackUpdate demo
Dev         connection state and local-only switches
```

Source is in [`app/`](../app). Nothing in it is special: it is the same
`ExecuteADBCommand` and CAN calls that `scripts/ola.py` makes, with a
UI on top.

The signal and fault tables are data files from Axiom, which is Apache-2.0. See
[`THIRD-PARTY.md`](../THIRD-PARTY.md). No Axiom code is used, and the app has no
Shizuku dependency: it already has root through the scooter's own daemon.

---

## Why you type IPv4 and it talks IPv6

This is the one part that looks like a trick and is not.

When the scooter is in **customer** mode, its own init script adds this rule:

```
iptables -I INPUT -p tcp --match multiport --destination-port 50005,5555 -j DROP
```

That is **IPv4 only**. IPv6 is never filtered. So an IPv4 session dies the
moment the user switches to Customer — which is exactly when the app is most
useful. The app therefore always dials IPv6.

The IPv6 address is not computed from the IPv4 address. It is learned from a
MAC address, and turned into a link-local address with EUI-64: flip the
universal/local bit of the first byte, splice `ff:fe` into the middle, prefix
`fe80::`.

Measured on the scooter, not guessed:

```
MAC     3a:2b:7c:06:5d:a8
        ↓ flip bit 1 of 0x3a  →  0x38
        ↓ insert ff:fe
link-local  fe80::382b:7cff:fe06:5da8
```

The question is *whose* MAC. There are three situations, and the app tries them
in this order:

| Situation | Where the MAC comes from | Address dialled |
|---|---|---|
| The app runs **on the scooter** (you typed its own IPv4) | nothing — no network hop is needed | `[::1]:50005` |
| You joined the **scooter's own hotspot** | the BSSID, which is the scooter's AP | `fe80::<EUI-64>%wlan0` |
| You and the scooter are both on **some other network** | the scooter's MAC in the ARP table, for the IPv4 you typed | `fe80::<EUI-64>%wlan0` |

`::1` rather than `127.0.0.1` for the first case, for the same reason as
everything else here: customer mode drops port 50005 over IPv4, and that rule
covers loopback too. IPv6 is never filtered.

The third case is the one that catches people out. When the scooter is a client
on your home Wi-Fi, the BSSID is your *router*, and its EUI-64 address is the
router — not the scooter. Reading `/proc/net/arp` gives the scooter's real MAC
for the address you typed, which is the one that works. Android still lets an
app read that file on this build, but it is not a public API, so failure here is
handled quietly and just moves on.

If none of the three produce a connection, the app falls back to plain IPv4 and
says so. Mode switching is disabled on that path, because switching to Customer
would cut the connection.

---

## One stream, nine screens

The daemon can push frames with `CanDumpStream`, the unfiltered `vcan0` tap.
`ReadCanStream` looks equivalent but returned zero frames on this unit even
after `Init`, so the app uses the dump tap. That stream can only be opened once:
if two screens opened their own, they would steal frames from each other and the
bus would look half dead. So there is exactly one stream for the whole app,
owned by `Bus.java`.

`Bus` decodes every frame into one `Live` and hands the same snapshot to the
screens ten times a second. A screen only attaches a listener while it is
visible, so switching tabs costs nothing and does not restart the stream. The
stream is closed when the activity is destroyed.

The stream has **no deadline** and is cancelled with a gRPC
`Context.CancellableContext`. A deadline on a streaming call is what wedges the
daemon; a cancel is clean. The unary `ReadCanFrame` loop is kept as a fallback
in the desktop harness, not used by the app.

This is why the app reads a stream where an earlier version of this page said
never to: a stream with no deadline, cancelled properly, is safe, and it is the
only way nine screens can share one bus. The tap is `CanDumpStream`, because
`ReadCanStream` stayed silent here.

### Battery

Reads the shared snapshot. Shows state of charge, display SOC, state of health,
voltage, current, power, pack temperature, cell min/max, cell spread with a
verdict, all 14 cell voltages, speed, range, drive mode, motor rpm, odometer,
charging state, charge rate, cycle count, and the `0x683` safety flags (fall
detection, tow/theft, regen, hill hold).

The spread verdict is the useful one: under 30 mV is healthy, 30–50 mV is worth
watching, more than that under load points at a weak cell.

`Stop` pauses the screen without closing the stream; the other screens keep
reading.

### Signals

The whole decoded table, live. Leave the search box empty and it lists the
messages actually seen on the bus, with a frame count and a few non-zero signals
each. Type part of a message or signal name and it shows every match with its
current value.

Every value is `raw × scaling + offset`, exactly as `can_database.json` says. A
dash means the vehicle has not reported that signal yet.

### Faults

Three lists and one table:

- **active** — the fault bit is 1 or more right now.
- **latched** — it has been 1 at some point, even if it cleared since. An
  intermittent fault is not missed.
- **simulated** — added from the Dev tab; lives in this app only.
- **table** — all 194 known faults, filterable by title, id, component or text.

The 194 rows and their descriptions come from Axiom's fault registry, which is
Apache-2.0 ([`THIRD-PARTY.md`](../THIRD-PARTY.md)). A fault is matched by
turning the CAN signal name into camelCase, which is the id the table uses.

Nothing on this screen is written to the scooter.

### Log

Every frame the daemon pushes, newest first: time, id, message name, eight
payload bytes. Under it, the ten busiest ids — the quickest way to tell a quiet
bus from one missing a single message.

The last 500 frames are kept in memory and never written to disk.

### Settings

Twelve `am start -a android.settings.*` buttons, plus:

- **Back to dash** — `input keyevent 3` (`HOME`). The dash is the home activity.
- **Restart dash** — force-stops `com.olaelectric.hmiclient` and starts it again.
  Confirmation dialog first; the screen goes blank for a few seconds.
- **Diagnostic app** — opens `com.ola.mfgapp`.

`am start` is used and never `input tap`, because the dash is a Kanzi scene
graph that ignores injected touches (doc 07).

### ADB

A text box that runs whatever you type as uid 0.

`ExecuteADBCommand` does not use a shell — it splits the string on whitespace and
`exec`s it. So the app wraps every command in `sh -c` and replaces its spaces
with `${IFS}`, which the shell expands back. That is what makes pipes, flags and
redirection work:

```
dumpsys activity activities | grep ResumedActivity
```

is sent as

```
sh -c dumpsys${IFS}activity${IFS}activities${IFS}|${IFS}grep${IFS}ResumedActivity
```

The same wrapper is in `scripts/ola.py`. For anything longer, push a `.sh` file
and run that instead — `Rpc.runScript` in the source does exactly that.

#### Wireless ADB, reversible

Axiom uses Shizuku to reach a shell. This app already has root, so the same
result is three buttons and no extra service:

| Button | What it does |
|---|---|
| **Unlock adb** | makes an RSA key pair **on the phone**, writes only the public half to `/data/local/tmp/olatool_adb_keys`, bind-mounts it over `/adb_keys`, and sets the network mode to `mfg` so `adbd` listens on 5555 |
| **Undo** | removes the bind mount, deletes the key file, and sets the network mode back to what it was before Unlock (default `cust`) |
| **Status** | shows the mode, whether `/adb_keys` is mounted, and the size of the key file |

The private key never leaves the phone and is never printed. It is kept in the
app's own preferences, and a broken saved key is replaced rather than blocking
the feature.

Nothing is flashed. The bind mount disappears on reboot. The `mfg` mode is the
same `persist.` property the Mode tab writes, so Undo is a plain `setprop` back.

After Unlock, connect from the PC:

```powershell
adb connect <scooter-address>:5555
```

### Mode

Writes `persist.ola.security.scootermode`, either `cust` or `serv`.

That property only feeds two triggers in `/vendor/etc/init/ola_e2.rc`, which add
or remove the iptables rule above and restart `adbd`. It does not touch CAN, the
motor, charging or how the scooter rides, and it is a `persist.` property, so it
survives a reboot and can be set straight back from the same screen.

The vehicle operation mode (`GetVehicleOperationMode`) is shown **read-only**.
`SetVehicleOperationMode` writes `/data/vendor/ola/v-op-mode.json` and has never
been tested on this unit, so the app does not offer it.

Both buttons are disabled when the live link is IPv4, with a line saying why.

### TechPack

Calls `TechPackUpdate(ConfigType{type})` and prints exactly what comes back.

**This is a demo, not a feature.** The call exists and the Ola manufacturing app
uses it, but the value the `type` field expects is not in the daemon's strings
and not a literal in the mfg app's dex. The screen gives you an editable field
and a row of candidates (`on`, `off`, `enable`, `disable`, `techpack_on`, …) and
reports the status and message verbatim. In this demo it may simply do nothing.

Keep the scooter parked and stationary, and note the exact reply before trying a
different value.

### Dev

Connection state and local-only switches. Nothing here is sent to the scooter.

- shows the target, whether the link is IPv6 or IPv4, the bus state, and how
  many messages and faults are loaded
- **Reset live data** — clears the current values; the next frame starts again
- **Clear latched** — empties the latched fault list
- **Clear simulated faults** — removes anything added with the box below
- **Simulate a fault** — type a fault id, which is the camelCase signal name,
  and it is added to the Faults screen's simulated list. It exists only in this
  app's memory. A wrong id is reported, not guessed.

This is how the fault screen is tried without waiting for a real fault.

---

## Build it yourself

You need JDK 17 and an Android SDK with `platforms;android-34` and
`build-tools;34.0.0`. Nothing else — no Android Studio, no `protoc`.

```powershell
cd ola-s1-rea\app

# compile the pure-Java part first; it has no Android dependency
.\gradlew.bat :core:compileJava :cli:compileJava

# the APK
.\gradlew.bat :app:assembleDebug
# -> app\build\outputs\apk\debug\app-debug.apk
```

The Gradle wrapper pins Gradle 8.9, so the first run downloads that
distribution (~130 MB) and then the dependencies. Everything is cached after
that.

### Release build

Release signing is local only. Put a `keystore.properties` next to
`app/build.gradle.kts`:

```properties
storeFile=../keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Both that file and the `.jks` are gitignored, and no APK is ever committed —
release APKs are attached to a GitHub release. Without the file the release APK
is simply unsigned.

### Desktop harness

`:cli` is the same core code with a `main()`, so you can test the protocol from
the PC before touching the phone:

```powershell
.\gradlew.bat :cli:run --args="192.168.1.5 probe"
.\gradlew.bat :cli:run --args="192.168.1.5 battery 10"
.\gradlew.bat :cli:run --args="192.168.1.5 signals 10"
.\gradlew.bat :cli:run --args="192.168.1.5 faults 10"
.\gradlew.bat :cli:run --args="192.168.1.5 db"
.\gradlew.bat :cli:run --args="192.168.1.5 shell `"dumpsys activity activities | head -20`""
.\gradlew.bat :cli:run --args="192.168.1.5 mode"
```

`db` and `selftest` need no scooter at all:

```powershell
.\gradlew.bat :cli:run --args="db"          # 225 messages, 1251 signals, 194 faults
.\gradlew.bat :cli:run --args="selftest"    # 69 offline assertions
```

On the PC you type the IPv6 address directly (`fe80::...%6`), because a desktop
does not report a BSSID for the link-local address it is talking to.

---

## Install

The app has no dependency on the scooter's firmware, so any of these work:

```powershell
# on the scooter itself, through adb
adb install -r app\build\outputs\apk\debug\app-debug.apk

# or on your phone, over the scooter's hotspot — same file
```

Then: join the scooter's Wi-Fi, type its IPv4, press Connect. On a phone, grant
the location permission — it is only used to read the BSSID. Deny it and the app
still tries the ARP path, then IPv4, minus the mode switch.

On the scooter itself no permission is asked: the address you type is the
device's own, so it talks to `::1` and never touches Wi-Fi.

---

## What was actually run on hardware

Not a plan — these were done on the unit, on 29 October 2026, and the screenshots
behind them are in the session log:

| Check | Result |
|---|---|
| APK installs and launches on the dash (800×480) | yes, 1.5 s to first frame, no crash |
| Connect with the scooter's own IPv4 | `IPv6 [::1]:50005`, note reads "running on the scooter" |
| ADB tab, `id -u` | `0` |
| Mode tab | shows `Service`, both buttons enabled over IPv6 |
| Battery tab | "The bus is silent" while the pack slept — correct, and the CLI said the same thing |
| Settings, TechPack | render; buttons not pressed |

The Battery tab was seen reading live frames from the desktop harness
(`:cli battery 12` → SOC 86.7 %, SOH 100 %, 56.76 V, odometer 10693.7 km), but
not yet from the app while the pack was awake. That is the one gap.

The Signals, Faults, Log and Dev screens, the shared `Bus`, and the ADB
provisioning buttons are newer than that table: they build, and the 69 offline
assertions in `:cli selftest` pass, but they have not yet been pressed on the
dash. Treat them as untested on hardware until they are.

---

## Limits

- **Android 7 and up.** `minSdk 24`, `targetSdk 34`. grpc-java needs a little
  `java.util` desugaring on old releases; that is configured.
- **No AndroidX.** Nine screens and one gRPC client do not need it, so the app
  uses the platform theme and stays small.
- **One stream, shared.** `Bus` owns the single `CanDumpStream`; screens attach
  and detach listeners instead of opening their own. Opening a second stream
  would split the frames.
- **The app cannot wake the vehicle.** If the bus is silent, the pack is asleep:
  press the red start button or plug in the charger.
- **IPv4 fallback cannot switch mode.** By design, see above.
- **Signals come from the table, not the firmware.** A wrong name or scaling in
  `can_database.json` shows a wrong value; the Log tab is there to check the raw
  bytes.

## Safety

Everything the app does is either a read, an Android intent, or the one
`persist.` property from doc 03. The ADB unlock adds a bind mount over
`/adb_keys`; that is a mount, not a write to a partition, and it is gone on
reboot.

It does not flash, does not write partitions, and does not call any `SwUpdate*`,
`Provision*`, `GenerateCerts`, `RemoveWifiCredentials` or `Deinit` method.

The one thing to be careful with is TechPack, because its effect is unknown.
Keep the scooter stationary, and change one thing at a time.
