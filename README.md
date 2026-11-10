<div align="center">

# ⚡ Ola S1 Air / S1 Pro — Head Unit Reverse Engineering

**▶️ Run YouTube on the dash — root it over IPv6, read the whole CAN bus, open Android on the screen.**

_No flashing. No keys. No cloud. Your own scooter only._

<br/>

![Tested on](https://img.shields.io/badge/tested%20on-Ola%20S1%20Air%20%C2%B7%20S1%20Pro-ff6a00?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Linux%20%7C%20macOS-0a7bbb?style=flat-square)
![App](https://img.shields.io/badge/app-Android%20(Java)%20%2B%20Python-3DDC84?style=flat-square&logo=android&logoColor=white)
![Access](https://img.shields.io/badge/access-root%20via%20gRPC%20over%20IPv6-c0392b?style=flat-square)
![Method](https://img.shields.io/badge/method-no%20flashing%20%C2%B7%20no%20partition%20writes-2ecc71?style=flat-square)
![Secrets](https://img.shields.io/badge/secrets-none%20in%20repo-999?style=flat-square&logo=githubactions&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square)

<br/>

### ▶️ Featured — YouTube, playing on the scooter's dashboard

The dash is Android under the hood. With the root access this repo documents, it
runs ordinary apps — so it plays **full-screen YouTube** on the same screen that
normally only shows speed, battery and maps. &nbsp;[**See how →**](#youtube-on-the-dashboard)

<!-- 📸 THE money shot: drop a photo or GIF of YouTube on the dash right here.
     Save it to docs/img/ and uncomment:
<img src="docs/img/youtube-on-dash.jpg" width="680" alt="YouTube playing on the Ola S1 dashboard">
-->

</div>

---

Notes from taking apart the dashboard of an Ola S1 — **tested on the S1 Air and
S1 Pro** (E2 platform, software `5.6.1`). This is a lab notebook, not a product.
Everything here was done on an owner's own scooter, over the local network, with
no flashing and no partition writes.

**What you get:** a root shell on the dashboard over IPv6, a one-click script that
opens Android Settings on the scooter, the full CAN map, the HMI architecture, and
a repeatable method for doing the same on your own unit Without Touching the scooty.

**No keys, passwords, tokens or personal data are in this repo.** You make your
own adb key. Nothing here can impersonate a vehicle or a cloud account.

> New here? The full walkthrough is [How to do it yourself — zero to real
> adb](#how-to-do-it-yourself--zero-to-real-adb).

---

## Contents

- [**▶️ YouTube on the dashboard**](#youtube-on-the-dashboard) — the headline: real YouTube on the S1 screen
- [**Get the repo on any platform**](#get-the-repo-on-any-platform) — clone & set up on Windows / Linux / macOS
- [**Install the Claude Code plugin**](#install-the-claude-code-plugin) — the skills and slash commands that ship with this repo
- [**Repository layout**](#repository-layout) — the folder tree, explained
- [The short version](#the-short-version) — the one trick, in a paragraph
- [60-second start](#60-second-start-open-android-settings-on-the-scooter) — open Android Settings on the dash
- [**How to do it yourself — zero to real adb**](#how-to-do-it-yourself--zero-to-real-adb) — the full, nothing-skipped walkthrough
- [What is in the box](#what-is-in-the-box) — the hardware, the apps, the bus
- [The Android app](#the-android-app) — the nine-screen companion APK
- [Docs](#docs) — the deep-dive notebook (12 topics)
- [What is proven, and what is not](#what-is-proven-and-what-is-not)
- [Safety](#safety) · [Legal](#legal) · [License](#license)

---

<a id="youtube-on-the-dashboard"></a>

## ▶️ YouTube on the dashboard

The headline hack: **real YouTube, full-screen on the Ola S1's dash** — the same
screen that normally only shows speed, battery and maps. The dashboard is Android
under the HMI, so once you have root and adb it runs ordinary apps.

Two ways, both built on the root access documented here:

- **`olatube` overlay** — [`app/overlay`](app/overlay). A floating web player
  (`WebPlayerService` / `OverlayService`) with an accessibility helper
  (`YtAccessibilityService`) that starts on boot (`BootReceiver`) and draws over
  the HMI. One-time setup via `SetupActivity`.
- **SmartTube + picture-in-picture** — a sideloaded YouTube client (e.g. SmartTube)
  running in a PIP window, for a native-app feel.

> **How it's possible:** the dash is a normal Android head unit sitting behind
> Ola's HMI. Get root + adb with the [walkthrough below](#how-to-do-it-yourself--zero-to-real-adb),
> and it will launch and draw any Android app you install — YouTube included.

<!-- 📸 A photo or GIF of YouTube playing on the dash is the single best thing you
     can add here. Save it to docs/img/ and embed it, e.g.:
<p align="center"><img src="docs/img/youtube-on-dash.jpg" width="680" alt="YouTube playing on the Ola S1 dashboard"></p>
-->

---

## Get the repo on any platform

This repo is self-contained: clone it on Windows, Linux or macOS and you have
everything — scripts, the Android app source, the proto, and all the docs. No
secret, key or token is stored here; you generate your own as you go.

```bash
git clone https://github.com/MOHDSAUD888/OLA-S1Air-S1Pro-YouTube.git
cd OLA-S1Air-S1Pro-YouTube
```

What you need per platform (install once):

| Platform | Python 3 | adb / scrcpy (optional) | Build the app (optional) |
|---|---|---|---|
| **Windows** | `winget install Python.Python.3` | `winget install Google.PlatformTools` · `scrcpy` from its releases | JDK 17 + `app\gradlew.bat` |
| **Linux** | `sudo apt install python3 python3-pip` | `sudo apt install adb scrcpy` | JDK 17 + `app/gradlew` |
| **macOS** | `brew install python` | `brew install android-platform-tools scrcpy` | JDK 17 + `app/gradlew` |

Then the one Python dependency used by every script:

```bash
python -m pip install -r scripts/requirements.txt    # installs grpcio
```

That is all. The scripts are pure Python/PowerShell/shell and the app builds with
the bundled Gradle wrapper — nothing is tied to one machine.

> **Secrets stay out of git.** Your adb key, any token you capture, and local
> tooling config are all `.gitignore`d. Keep them on your machine only. If you
> ever find a real key or token, rotate it and never commit it.

---

<a id="install-the-claude-code-plugin"></a>

## Install the Claude Code plugin

This repo ships a [Claude Code plugin](.claude-plugin/) — skills and slash
commands that wrap the research in these pages so you can invoke it by name.

**Skills** (auto-loaded when relevant):

| Skill | What it covers |
|---|---|
| [`ola-s1-connect`](.claude-plugin/skills/ola-s1-connect/SKILL.md) | Root gRPC on TCP 50005, `ola.py`, all the remote actions |
| [`ola-s1-canbus`](.claude-plugin/skills/ola-s1-canbus/SKILL.md) | `vcan0`, `CanDumpStream`, the CAN map in `can_database.json` |
| [`ola-s1-passcode`](.claude-plugin/skills/ola-s1-passcode/SKILL.md) | The scrypt `$s0$` hash, `crack_passcode.py`, the Frida unlock |
| [`ola-s1-youtube`](.claude-plugin/skills/ola-s1-youtube/SKILL.md) | The `olatube` overlay and SmartTube PIP |
| [`ola-s1-mirror`](.claude-plugin/skills/ola-s1-mirror/SKILL.md) | The on-dash H.264 agent, wireless adb, touch injection |
| [`ola-s1-ota`](.claude-plugin/skills/ola-s1-ota/SKILL.md) | The MQTT update chain and the unsigned CloudFront download |
| [`ola-s1-cloud`](.claude-plugin/skills/ola-s1-cloud/SKILL.md) | `kong2.olaelectric.com`, the header set, the telemetry endpoints |

**Slash commands** (type `/` in any prompt):

- `/ola-connect <action> --ipv6 <addr>` — run a root action on the scooter
- `/ola-canbus` — read `vcan0` or the parser's `logcat`
- `/ola-youtube overlay|smartube` — set up the dash player

### Install it

The plugin ships as a one-plugin **marketplace** (`.claude-plugin/marketplace.json`).
Claude Code installs it in two steps — add this repo as a marketplace, then
install the plugin from it:

```sh
# 1 — add this repo as a plugin marketplace (one time)
/plugin marketplace add MOHDSAUD888/OLA-S1Air-S1Pro-YouTube

# 2 — install the plugin from it   (name is  plugin@marketplace)
/plugin install ola-s1-rea@ola-s1-rea
```

Already cloned the repo? Point the marketplace at the local folder instead of the
GitHub path:

```sh
/plugin marketplace add ./OLA-S1Air-S1Pro-YouTube
/plugin install ola-s1-rea@ola-s1-rea
```

Run `/plugin` any time to view, toggle, or update it. Once installed, the skills
load themselves whenever your prompt matches one, and the three slash commands
appear under `/`.

The plugin carries no secrets and no vehicle state — it is just the method. Your
adb key, any captured token, and local tooling config stay `.gitignore`d on your
machine.

---

## Repository layout

```
OLA-S1Air-S1Pro-YouTube/
├── README.md              ← you are here: the full zero-to-adb walkthrough
├── DISCLAIMER.md          ← legal / "your own scooter only" notice
├── LICENSE                ← MIT, for the scripts and docs
├── THIRD-PARTY.md         ← attribution (Axiom signal & fault data, Apache-2.0)
│
├── .claude-plugin/        ← Claude Code plugin: skills + slash commands
│   ├── marketplace.json        makes this repo an installable marketplace
│   ├── plugin.json             the plugin manifest (name, version, keywords)
│   ├── skills/                 7 ola-s1-* skills (connect, canbus, passcode, …)
│   └── commands/               /ola-connect, /ola-canbus, /ola-youtube
│
├── docs/                  ← the deep-dive notebook, one file per topic
│   ├── 01-the-scooter.md        hardware, OS, apps, CAN bridge, sleep/wake
│   ├── 02-root-over-ipv6.md     the TCP 50005 hole and the shell trick
│   ├── 03-adb-and-full-access.md unlocking adb, scrcpy, undo
│   ├── 04-open-settings.md      the one-click Settings script
│   ├── 05-can-bus.md            CAN ids, formulas, battery, faults, regen
│   ├── 06-lock-and-passcode.md  keypad chain, why asleep blocks unlock
│   ├── 07-hmi-ui.md             Kanzi renderer, screens, injected-tap limits
│   ├── 08-ota-and-firmware.md   update path — what not to touch
│   ├── 09-security-findings.md  the clean findings list, with proof
│   ├── 10-how-to-do-rea.md      the method, reusable on another target
│   ├── 11-glossary.md           plain-word definitions
│   └── 12-android-app.md        the Android app, screen by screen
│
├── scripts/               ← the tools you run from your PC
│   ├── ola.py                   root shell, Settings, screenshots, push/pull
│   ├── find-scooter.ps1         find the scooter's IPv6 + test port 50005
│   ├── open-settings.ps1 / .sh  one-click Android Settings (Win / *nix)
│   ├── unlock-adb.ps1           reversible wireless-adb unlock for scrcpy
│   ├── crack_passcode.py        passcode research helper
│   ├── frida_unlock.py          Frida-based unlock research helper
│   └── requirements.txt         one dependency: grpcio
│
├── proto/                 ← the recovered gRPC service definition
│   ├── wifican.proto            the real 40-method service (from the binary)
│   └── wifican-first-attempt.proto  the wrong first guess, kept as a lesson
│
├── licenses/              ← full license texts for third-party data
│
└── app/                   ← the Android companion app (Gradle multi-module)
    ├── gradlew / gradlew.bat    build without installing Gradle
    ├── settings.gradle.kts      wires the modules below together
    ├── app/                     the UI: all the on-device screens
    ├── core/                    shared logic: CAN codec, gRPC, fault tables
    ├── cli/                     a command-line build of the same core
    ├── mirroragent/             on-device screen-mirror agent
    └── overlay/                 the YouTube-on-dash overlay service
```

**Not in this repo (intentionally):** the stock Ola APK, any extracted `.dex`/
firmware, private research notes, local agent config, and anything holding a
secret. Those are kept off GitHub on purpose — see the `.gitignore`.

---

## The short version

The dashboard runs a small Go program called `diagnostics_server`. It listens on
**TCP 50005**, speaks plain gRPC with **no password**, and runs commands as
**root**. The normal IPv4 firewall blocks that port. The firewall rule is
`iptables`, not `ip6tables`, so **IPv6 walks straight through**.

That is the whole trick.

```
your PC ── IPv6 ──> [fe80::....]:50005 ──> ExecuteADBCommand("...") ──> root
```

Once you have that, you can start apps, read the battery, take screenshots, pull
files, watch CAN, or unlock adb for scrcpy. No pairing. No key. No button.

---

## 60-second start: open Android Settings on the scooter

You need: Python 3, this repo, and the scooter's IPv6 address.

```powershell
py -m pip install -r scripts\requirements.txt

# Windows
.\scripts\open-settings.ps1 -IPv6 fe80::1234:5678:9abc:def0

# Linux / macOS
./scripts/open-settings.sh fe80::1234:5678:9abc:def0
```

Replace the example address with your scooter's. If it is a link-local address
(`fe80::...`), the Windows script finds the interface number for you. On Linux,
write it as `fe80::1234:5678:9abc:def0%wlan0` or pass `--scope`.

Don't know the address? Run:

```powershell
.\scripts\find-scooter.ps1
```

It pings the local IPv6 neighbourhood and tests port 50005 on every neighbour.
The one marked `OPEN` is your scooter.

Return to the normal dash:

```powershell
py scripts\ola.py home --ipv6 fe80::1234:5678:9abc:def0
```

More actions:

```powershell
py scripts\ola.py diag    --ipv6 ...   # open the Ola service/diagnostic app
py scripts\ola.py info    --ipv6 ...   # uid, Android version, scooter mode
py scripts\ola.py shell   --ipv6 ... "ls -l /data/vendor/ola"
py scripts\ola.py screenshot --ipv6 ... shot.png
```

`ola.py --help` lists everything.

---

## How to do it yourself — zero to real adb

This is the whole path, in order. Every command is real. Nothing is skipped.

You go from "I have a scooter and a laptop" to "adb says `device` and I can run
`scrcpy`". No flashing, no partition writes, no Ola account, no key from anyone
else.

```
Step 1  find the address
Step 2  prove root over IPv6
Step 3  run commands
Step 4  open Settings (optional, proves intents work)
Step 5  real adb by swapping the key file
```

### What you need

| Thing | Why | Check |
|---|---|---|
| The scooter, awake | asleep = no network, no data | press the red button |
| Same Wi-Fi | the daemon is on the LAN | `ping` works |
| IPv6 on your PC's Wi-Fi | the whole trick is IPv6 | step 1 |
| Python 3 | runs `ola.py` | `py --version` |
| `grpcio` | the only Python package | `py -m pip install -r scripts\requirements.txt` |
| adb | only needed at step 5 | `adb version` |
| scrcpy | optional, step 5 | `scrcpy --version` |

Windows, Linux and macOS all work. The commands below are PowerShell; the Linux
equivalents are in the docs.

Where a command shows `--ipv6 ...`, that `...` means your address plus scope:

```powershell
--ipv6 fe80::1234:5678:9abc:def0 --scope 6
```

Write it out in full when you run it. It is shortened here only for width.

---

### Step 1 — Find the scooter's IPv6 address

The easy way:

```powershell
.\scripts\find-scooter.ps1
```

It pings the IPv6 neighbourhood and tests port 50005 on every neighbour. The one
marked `OPEN` is your scooter.

The manual way:

```powershell
# IPv6 must be on for the Wi-Fi adapter (admin PowerShell, once)
Enable-NetAdapterBinding -Name "Wi-Fi" -ComponentID ms_tcpip6

# ask every IPv6 host on the link to answer
ping -6 -n 2 ff02::1%6

# read the neighbour table
Get-NetNeighbor -AddressFamily IPv6 | Format-Table IPAddress,LinkLayerAddress,InterfaceIndex
```

You want a `fe80::...` address. The `InterfaceIndex` next to it is your **scope**.
Write the pair down:

```
address  fe80::1234:5678:9abc:def0
scope    6
```

Linux/macOS:

```sh
ping -6 -c 2 ff02::1%wlan0
ip -6 neigh
```

Why link-local: the address is built from the MAC (EUI-64), so it does not change
when DHCP hands out a new IPv4 lease. It is the stable way to find the unit.

---

### Step 2 — Prove root over IPv6

```powershell
py scripts\ola.py info --ipv6 fe80::1234:5678:9abc:def0 --scope 6
```

Expected, roughly:

```
uid            0
android        13
build          ...
patch          2023-09-05
model          ...
scooter mode   cust
selinux        Permissive
uptime         ...
```

The important line is **`uid  0`**. That is root, with no password, over plain
gRPC on port 50005.

If it fails:

| Message | Meaning | Fix |
|---|---|---|
| `No answer ... after 8s` | wrong address, wrong scope, or asleep | re-run step 1, wake the scooter |
| `This is a link-local address` | scope missing | add `--scope 6` or `%6` |
| `Missing grpcio` | package not installed | `py -m pip install grpcio` |
| Timeout but `ping` works | port blocked | you are on IPv4, not IPv6 |

IPv4 will **not** work in customer mode. The firewall drops 50005 there. IPv6 is
the way in.

---

### Step 3 — Run commands

`ola.py shell` wraps your command in `sh -c` for you, so spaces, pipes and quotes
are fine:

```powershell
py scripts\ola.py shell --ipv6 fe80::1234:5678:9abc:def0 --scope 6 "id; getprop ro.build.version.release"
py scripts\ola.py shell --ipv6 ... "ls -l /data/vendor/ola"
py scripts\ola.py shell --ipv6 ... "netstat -tlnp | head -20"
```

Useful things you can do right away:

```powershell
py scripts\ola.py screenshot --ipv6 ... shot.png      # what is on screen
py scripts\ola.py top        --ipv6 ...               # which activity is in front
py scripts\ola.py wake       --ipv6 ...               # turn the panel on
py scripts\ola.py home       --ipv6 ...               # back to the dash
py scripts\ola.py diag       --ipv6 ...               # Ola technician app
py scripts\ola.py push       --ipv6 ... .\file.bin /data/local/tmp/file.bin
py scripts\ola.py pull       --ipv6 ... /data/local/tmp/file.bin .\file.bin
```

**The one rule that saves hours:** if a command has flags with values, quotes,
pipes or redirection, put it in a `.sh` file, push it, and run the file.

```powershell
# write mycmd.sh locally, then:
py scripts\ola.py push --ipv6 ... .\mycmd.sh /data/local/tmp/mycmd.sh
py scripts\ola.py shell --ipv6 ... "chmod 755 /data/local/tmp/mycmd.sh"
py scripts\ola.py shell --ipv6 ... "sh /data/local/tmp/mycmd.sh"
```

The daemon itself is not a shell: it splits its input on whitespace and `exec`s
it. `ola.py shell` hides that by wrapping your command in `sh -c` and replacing
spaces with `${IFS}`. That is why normal commands work.

`--raw` sends your string with no wrapper, straight at the splitter. That is why
`mount --bind` fails with `--raw` and works from a pushed file.

---

### Step 4 — Open Android Settings (optional)

```powershell
.\scripts\open-settings.ps1 -IPv6 fe80::1234:5678:9abc:def0
```

The scooter's screen shows the real Android Settings app. Back to the dash:

```powershell
py scripts\ola.py home --ipv6 ...
```

This step is optional, but do it once: it proves intents work, which is the same
mechanism used later to restart apps. See [doc 04](docs/04-open-settings.md).

---

### Step 5 — Real adb, by changing the keys

adb is **not** needed for anything above. This step is only for `scrcpy`,
Android Studio, `logcat`, `dumpsys`, and fast file transfers.

#### 5.1 — Why plain adb fails

Flipping the scooter out of customer mode is not enough. Ola patched `adbd` so it
reads a different key file depending on the mode:

| mode | key file adbd trusts |
|---|---|
| `cust` | none — it refuses before loading a key |
| `serv` | `/adbkeymoveos3_pub_prod` |
| `mfg` | `/adb_keys` |

Both files are baked into the read-only system image and hold Ola's build keys.
Your key in `/data/misc/adb/adb_keys` is ignored. That is why you get
`failed to authenticate` even when your key looks trusted.

The fix is a **bind mount**: make your own public key appear at the path adbd
actually reads. It lives in RAM only and disappears on reboot.

#### 5.2 — Make your own key

Use your own key. Never use someone else's, and never commit it.

```powershell
# makes %USERPROFILE%\.android\adbkey and adbkey.pub
adb keygen "$env:USERPROFILE\.android\adbkey"
```

If you already have `~/.android/adbkey.pub`, skip this. It is the same key adb
uses everywhere.

#### 5.3 — Push the public key

Only the **public** key goes to the scooter. The private key stays on your PC.

```powershell
py scripts\ola.py push --ipv6 ... "$env:USERPROFILE\.android\adbkey.pub" /data/local/tmp/adb_keys_mine
py scripts\ola.py shell --ipv6 ... "chmod 644 /data/local/tmp/adb_keys_mine"
```

#### 5.4 — Switch the mode

This opens port 5555 and restarts adbd. It does not reboot, does not touch CAN,
and does not change any vehicle setting.

```powershell
py scripts\ola.py shell --ipv6 ... "setprop persist.ola.security.scootermode serv"
```

Wait two seconds. `serv` uses `/adbkeymoveos3_pub_prod`. Use `mfg` if you prefer
`/adb_keys` — both behave the same on the network.

> This is a `persist.` property, so it **survives a reboot**. Remember the undo
> in 5.7.

#### 5.5 — Bind your key over the factory key

`mount --bind` cannot be sent inline — the whitespace splitter mangles it. Put it
in a file:

```sh
#!/system/bin/sh
MODE="$1"
if [ "$MODE" = "mfg" ]; then T=/adb_keys; else T=/adbkeymoveos3_pub_prod; fi
mount | grep -q " $T " && umount "$T"
mount --bind /data/local/tmp/adb_keys_mine "$T" || exit 1
echo "bound $T"
```

Save it as `bind_key.sh`, then:

```powershell
py scripts\ola.py push --ipv6 ... .\bind_key.sh /data/local/tmp/bind_key.sh
py scripts\ola.py shell --ipv6 ... "chmod 755 /data/local/tmp/bind_key.sh"
py scripts\ola.py shell --ipv6 ... "sh /data/local/tmp/bind_key.sh serv"
```

You should see `bound /adbkeymoveos3_pub_prod`.

#### 5.6 — Connect

```powershell
adb connect "[fe80::1234:5678:9abc:def0%6]:5555"
adb devices -l
```

`device` means you are in. `unauthorized` or `failed to authenticate` means the
mount is not in place, or you used the wrong mode for the file you mounted.

```powershell
adb shell id
```

That returns **uid 2000 (shell)**, not root. adb is for convenience; the gRPC
channel is still the root path. For `scrcpy` that is all you need:

```powershell
scrcpy --tcpip --max-size 800
```

> **Never restart adbd while scrcpy is connected.** You will lose the stream and
> sometimes the encoder. Reconnect instead.

#### 5.7 — Undo

```powershell
py scripts\ola.py shell --ipv6 ... "umount /adbkeymoveos3_pub_prod"
py scripts\ola.py shell --ipv6 ... "umount /adb_keys"
py scripts\ola.py shell --ipv6 ... "setprop persist.ola.security.scootermode cust"
adb disconnect "[fe80::1234:5678:9abc:def0%6]:5555"
```

A reboot also clears the bind mount, but **not** the `persist.` property — set it
back to `cust` if you want the IPv4 ports closed again.

#### All of step 5 in one command

```powershell
.\scripts\unlock-adb.ps1 -IPv6 fe80::1234:5678:9abc:def0
.\scripts\unlock-adb.ps1 -IPv6 fe80::1234:5678:9abc:def0 -Undo
```

The script does 5.2 to 5.6 in order, including finding your scope. Use `-Mode mfg`
for the other key path, `-Adb <path>` if adb is not on `PATH`.

---

### You are done when

```powershell
py scripts\ola.py info --ipv6 ...   # uid 0
adb devices -l                      # device
scrcpy --tcpip                      # mirror works
```

At that point you have root on the dash, a normal adb connection, and a screen
mirror. Everything else in this repo builds on those three.

Full detail: [doc 02](docs/02-root-over-ipv6.md) for the channel,
[doc 03](docs/03-adb-and-full-access.md) for adb, [doc 10](docs/10-how-to-do-rea.md)
for the method on a different target.

---

## What is in the box

| Part | What it is |
|---|---|
| Head unit | Android 13, MediaTek MT8768, 2.9 GB RAM, 800×480 screen |
| HMI app | `com.olaelectric.hmiclient` — one activity, Kanzi renderer, holds `HOME` |
| Vehicle apps | `com.android.hmiserverapp`, `profilemanagerservice`, `vehicleparser`, `ota`, … |
| Service app | `com.ola.mfgapp` — the technician app with the hotspot toggle |
| Root daemon | `/vendor/bin/diagnostics_server` — the 50005 hole |
| Vehicle bus | CAN, bridged to Android as `vcan0` |
| ECUs | VCU/MCU, BMS, BCM, BLE — separate computers on that bus |

The dashboard is **not** the vehicle. It is a screen and a gateway. The motor,
battery and locks are controlled by the ECUs. That distinction explains most of
what you will read here — including why the dash cannot unlock a sleeping scooter.

```
        ┌──────────────────────────┐
        │  Head unit (Android)     │
        │  HMI · cloud · CAN bridge│
        └────────────┬─────────────┘
                     │ vcan0  (UART → CAN)
        ┌────────────┴─────────────┐
        │  Vehicle CAN bus         │
        └──┬──────┬──────┬──────┬──┘
           │      │      │      │
          VCU    BMS    BCM    BLE
```

---

## The Android app

Nine screens, one APK, one address to type. Source in [`app/`](app).

| Screen | What it does |
|---|---|
| Battery | pack health, all 14 cells, ride, charging, `0x683` safety flags — live from CAN |
| Signals | the whole decoded signal table, searchable, live |
| Faults | active, latched and simulated faults, plus all 194 known rows |
| Log | the raw frame log and the busiest ids |
| Settings | opens the real Android screens on the dash (`am start`, never a tap) |
| ADB | a root shell over gRPC, plus reversible wireless ADB |
| Mode | Customer / Service, plus the vehicle mode read-only |
| TechPack | `TechPackUpdate` demo — may do nothing, see doc 12 |
| Dev | connection state and local-only switches |

All the live screens read one shared `CanDumpStream` owned by `Bus.java`, fanned
out ten times a second. A screen only listens while it is visible, so switching
tabs does not restart or split the stream.

The signal table and the 194 fault rows are data files from
[Axiom](https://codeberg.org/pitzel/axiom), which is Apache-2.0. No Axiom code is
used, and there is no Shizuku dependency — this app already has root through the
scooter's own daemon. Attribution is in [`THIRD-PARTY.md`](THIRD-PARTY.md).

You type the scooter's **IPv4**; the app dials **IPv6**. That is not cosmetic:
in customer mode the scooter drops TCP 50005 and 5555 over IPv4, so an IPv4
session kills itself the moment you switch. The IPv6 address is found from a MAC
address with EUI-64 — measured, not guessed:

```
3a:2b:7c:06:5d:a8   →   fe80::382b:7cff:fe06:5da8
```

Whose MAC depends on where you are: none at all when the app runs on the scooter
itself (it uses `::1`), the BSSID when you joined the scooter's hotspot, or the
scooter's ARP entry when both of you are on some other Wi-Fi. Only if all three
fail does it drop to IPv4, and then mode switching is disabled.

Build:

```powershell
cd OLA-S1Air-S1Pro-YouTube\app
.\gradlew.bat :app:assembleDebug
```

Full detail, including why each screen is written the way it is:
[doc 12](docs/12-android-app.md).

---

## Docs

| # | File | What it covers |
|---|---|---|
| 01 | [The scooter](docs/01-the-scooter.md) | Hardware, OS, apps, CAN bridge, sleep/wake |
| 02 | [Root over IPv6](docs/02-root-over-ipv6.md) | The 50005 hole, the proto, the shell trick |
| 03 | [ADB and full access](docs/03-adb-and-full-access.md) | Unlocking adb, scrcpy, undo |
| 04 | [Open Settings](docs/04-open-settings.md) | The one-click script and what it does |
| 05 | [CAN bus](docs/05-can-bus.md) | IDs, formulas, battery, faults, regen |
| 06 | [Lock and passcode](docs/06-lock-and-passcode.md) | Keypad chain, why asleep blocks unlock |
| 07 | [HMI UI](docs/07-hmi-ui.md) | Kanzi, screens, why `input tap` fails |
| 08 | [OTA and firmware](docs/08-ota-and-firmware.md) | Update path, what is inside, what not to touch |
| 09 | [Security findings](docs/09-security-findings.md) | The clean list, with severity and proof |
| 10 | [How to do REA](docs/10-how-to-do-rea.md) | The method, tools, and traps |
| 11 | [Glossary](docs/11-glossary.md) | Plain-word definitions |
| 12 | [Android app](docs/12-android-app.md) | The nine-screen APK, IPv4 in / IPv6 out, build |

Scripts:

| File | Use |
|---|---|
| `scripts/ola.py` | Root shell, Settings, screenshots, push/pull, UI keys |
| `scripts/open-settings.ps1` | Windows one-click Settings |
| `scripts/open-settings.sh` | Linux/macOS one-click Settings |
| `scripts/find-scooter.ps1` | Find the scooter's IPv6 and test 50005 |
| `scripts/unlock-adb.ps1` | Make adb work (reversible), for scrcpy |
| `scripts/requirements.txt` | One dependency: `grpcio` |

Proto:

| File | Use |
|---|---|
| `proto/wifican.proto` | The real 40-method service, recovered from the daemon binary |
| `proto/wifican-first-attempt.proto` | The wrong first guess — kept as a lesson, not for use |

---

## What is proven, and what is not

This repo only states things that were checked on a live unit, read from a
shipped file, or confirmed by the owner. If something was not proven, it is
either left out or marked **not tested**.

Proven:

- 50005 is open over IPv6 and closed over IPv4.
- `ExecuteADBCommand` runs as uid 0, no auth.
- `am start` opens Settings and the diagnostic app.
- adb can be unlocked by bind-mounting your own public key.
- CAN health values decode with the formulas in doc 05.
- The dash cannot wake or unlock a sleeping scooter.
- The HMI ignores injected taps; normal Android apps do not.

Not tested, and deliberately not done:

- Flashing or writing any partition.
- Calling the firmware-update RPCs.
- Pressing **START** on `Provision > Generate Certificates`.
- Sending random CAN frames that could move or lock the vehicle.
- Whether Ola's servers accept a cloned device identity.

---

## Safety

- **Your own scooter only.** This is not a tool for someone else's vehicle.
- **No flashing.** Nothing here needs a partition write. Do not start.
- **Do not call** `SwUpdate*`, `ProvisionVin`, `ProvisionConfig`,
  `RemoveWifiCredentials`, `ScooterModeChange`, `GenerateCerts`,
  `RegisterInstallCerts`, `Deinit`. They change state or brick things.
- **Do not send CAN frames you do not understand.** The bus moves the vehicle.
- **Keep a way back.** Before changing the scooter mode, know the undo command
  (doc 03). A reboot also clears the adb bind mount.
- **Do not publish secrets.** If you find a key or token, rotate it and leave it
  out of your notes.

---

## Legal

Reverse engineering for interoperability, repair and security research is legal
in many places, but the rules differ. You are responsible for what you do with
this. The authors are not. See [DISCLAIMER.md](DISCLAIMER.md).

---

## License

MIT for the scripts and docs. See [LICENSE](LICENSE).
