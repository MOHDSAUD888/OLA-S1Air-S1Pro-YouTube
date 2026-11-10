---
name: ola-s1-connect
description: Get a root shell on an Ola S1 Air / S1 Pro head unit over IPv6, with no adb and no pairing. Use when the user wants to connect to a scooter, open Android Settings on the dash, run a root shell command, take a screenshot, push/pull files, or send key events — or when they paste an IPv6 address and ask what to do with it. Trigger: connect to scooter, root shell, ola.py, gRPC 50005, IPv6, open settings on dash, scooter remote shell, push file to scooter, pull file from scooter, screenshot scooter, tap swipe key scooter.
---

# Ola S1 — Connect to the head unit over IPv6

The Ola S1 dash runs Android under a Kanzi HMI. From your own Wi-Fi it exposes a
**root gRPC channel on TCP 50005** (`wifican.WifiCanRpc/ExecuteADBCommand`) with no
authentication, no adb, and no pairing. Every documented action in this repo
starts here.

The tool is `scripts/ola.py`. It is the only thing you need to talk to the scooter.

## The one-liner

```sh
python scripts/ola.py settings --ipv6 <addr>
```

`<addr>` is the scooter's IPv6 address. Find it from your phone while connected to
the scooter's Wi-Fi or hotspot — or from the app's "Connect" tab, which derives it
from the Wi-Fi BSSID. A link-local address (`fe80::…`) needs a scope (interface
number): `--ipv6 fe80::1234:5678:9abc:def0%6` or `--scope 6`.

## What ola.py can do

| Command | What it does |
|---|---|
| `settings`, `wifi`, `bluetooth`, `apps`, `display`, `sound`, `battery`, `storage`, `about`, `notifications`, `location`, `date` | Open that Android Settings screen |
| `diag` | Open the Ola diagnostic/service app (`com.ola.mfgapp`) |
| `hmi` / `hmi-restart` | Bring the HMI to the front / restart it (clears a stuck UI) |
| `home` / `back` / `recents` / `wake` / `top` | Key events and screen wake |
| `key <code>` | Send any Android key code (see `KEYS` in the script) |
| `tap x y` / `swipe x1 y1 x2 y2 [ms]` | Inject touch |
| `shell <cmd>` | Root shell command (wrapped in `sh -c`) |
| `screenshot <path>` | Save a PNG of the screen |
| `push <local> <remote>` / `pull <remote> <local>` | Transfer files |

Connection options (`--ipv6`, `--scope`, `--port`, `--timeout`, `--wait`, `--raw`)
can go **before or after** the action.

## The gRPC detail that matters

`ola.py` speaks protobuf by hand because the server is weakly typed. It sends a
varint-tagged payload and reads back a two-field response. The RPC runs commands
as **root**, but it does **not** use a shell — so `ola.py shell` wraps your command
in `sh -c` and replaces spaces with `${IFS}` so pipes and redirection survive.
Use `--raw` to send a command exactly as typed.

Common failure modes, in order:

- **"No answer from … after 8s"** — scooter is asleep (hold the red button or plug
  in), wrong network, or wrong address/scope.
- **`ReadCanStream` silent but `candump vcan0` fine** — normal on this unit. The
  dump tap (`CanDumpStream`) is the reliable push path; don't read silence from
  `ReadCanStream` as a silent bus (docs/05).
- **`input tap` doesn't press the passcode keypad** — the keypad is a Kanzi scene,
  not an Android dialog. Nothing injected through `input` moves it (docs/06).

## Reading the evidence, not the labels

The repo's loop (docs/10) is: **behaviour → hypothesis → smallest test → evidence
→ read the code**, then refine. Decompiled labels lie; behaviour does not. Before
trusting any decompiled symbol, prove the call with the machine.

## When to use this skill

- User asks to connect to, shell, or control a scooter.
- User pastes an IPv6 address and wants to know what to do.
- User wants to open Settings / diag / HMI on the dash remotely.
- User wants to push or pull a file, or grab a screenshot.
- Any task that needs a root shell on the head unit and `ola.py` is the path.