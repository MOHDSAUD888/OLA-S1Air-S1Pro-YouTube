# 12 — The Android app

One APK. Five screens. You type one address: the scooter's IPv4.

```
Battery     pack health, cells, motor, safety state — live from CAN
Settings    open the real Android screens on the dash
ADB         a root shell, over gRPC, with pipes
Mode        Customer / Service, and the vehicle mode read-only
TechPack    the TechPackUpdate demo
```

Source is in [`app/`](../app). Nothing in it is special: it is the same
`ExecuteADBCommand` and `ReadCanFrame` calls that `scripts/ola.py` makes, with a
UI on top.

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

## The five screens

### Battery

Subscribes with `Init(Register)` and then calls unary `ReadCanFrame` in a loop.
It never opens `CanDumpStream` or `ReadCanStream`: a deadline on one of those
wedges the daemon. A timeout on the unary call is harmless, and while the
vehicle sleeps you simply get nothing.

Shows state of charge, display SOC, state of health, voltage, current, power,
pack temperature, temperature min/max, cell min/max, cell spread with a verdict,
all 14 cell voltages, MCU and motor temperature, motor rpm, odometer, and the
`0x683` safety flags (fall detection, tow/theft, regen, hill hold).

The spread verdict is the useful one: under 30 mV is healthy, 30–50 mV is worth
watching, more than that under load points at a weak cell.

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
.\gradlew.bat :cli:run --args="192.168.1.5 shell `"dumpsys activity activities | head -20`""
.\gradlew.bat :cli:run --args="192.168.1.5 mode"
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

---

## Limits

- **Android 7 and up.** `minSdk 24`, `targetSdk 34`. grpc-java needs a little
  `java.util` desugaring on old releases; that is configured.
- **No AndroidX.** Five screens and one gRPC client do not need it, so the app
  uses the platform theme and stays small.
- **One connection at a time.** The battery reader owns `ReadCanFrame` while it
  runs; the other screens only use unary calls that do not collide.
- **The app cannot wake the vehicle.** If the bus is silent, the pack is asleep:
  press the red start button or plug in the charger.
- **IPv4 fallback cannot switch mode.** By design, see above.

## Safety

Everything the app does is either a read, an Android intent, or the one
`persist.` property from doc 03. It does not flash, does not write partitions,
and does not call any `SwUpdate*`, `Provision*`, `GenerateCerts`,
`RemoveWifiCredentials` or `Deinit` method.

The one thing to be careful with is TechPack, because its effect is unknown.
Keep the scooter stationary, and change one thing at a time.
