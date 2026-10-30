# Ola S1 / S1 Pro — Head Unit Reverse Engineering

Notes from taking apart the dashboard of an Ola S1 (E2 platform, software `5.6.1`).
This is a lab notebook, not a product. Everything here was done on an owner's own
scooter, over the local network, with no flashing and no partition writes.

**What you get:** a root shell on the dashboard over IPv6, a one-click script that
opens Android Settings on the scooter, the full CAN map, the HMI architecture, and
a repeatable method for doing the same on your own unit Without Touching the scooty.

**No keys, passwords, tokens or personal data are in this repo.** You make your
own adb key. Nothing here can impersonate a vehicle or a cloud account.

> New here? The full walkthrough is [How to do it yourself — zero to real
> adb](#how-to-do-it-yourself--zero-to-real-adb).

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
cd ola-s1-rea\app
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
