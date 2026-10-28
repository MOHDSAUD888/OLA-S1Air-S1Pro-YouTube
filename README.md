# Ola S1 / S1 Pro — Head Unit Reverse Engineering

Notes from taking apart the dashboard of an Ola S1 (E2 platform, software `5.6.1`).
This is a lab notebook, not a product. Everything here was done on an owner's own
scooter, over the local network, with no flashing and no partition writes.

**What you get:** a root shell on the dashboard over IPv6, a one-click script that
opens Android Settings on the scooter, the full CAN map, the HMI architecture, and
a repeatable method for doing the same on your own unit Without Touching the scooty.

**No keys, passwords, tokens or personal data are in this repo.** You make your
own adb key. Nothing here can impersonate a vehicle or a cloud account.

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
