---
allowed-tools: Read, Glob, Grep, Bash(python scripts/ola.py *), Bash(python scripts/ola.py)
description: Connect to an Ola S1 head unit over IPv6 and run a root action
user-invocable: true
argument-hint: "<action> --ipv6 <addr>  (settings|shell|screenshot|push|pull|info|diag|hmi|...)"
argument: action and scooter address
---

# /ola-connect

Get a root shell on an Ola S1 Air / S1 Pro head unit over IPv6, with no adb and no
pairing. The tool is `scripts/ola.py`.

## Instructions

1. If the user gave an action and address, run it directly:

   ```sh
   python scripts/ola.py <action> --ipv6 <addr>
   ```

   Connection options (`--ipv6`, `--scope`, `--port`, `--timeout`, `--wait`,
   `--raw`) can go before or after the action. A link-local address needs a scope:
   `--ipv6 fe80::1234:5678:9abc:def0%6` or `--scope 6`.

2. If they only pasted an address, ask what they want to do. The actions are:

   | Command | What it does |
   |---|---|
   | `settings`, `wifi`, `bluetooth`, `apps`, `display`, `sound`, `battery`, `storage`, `about`, `notifications`, `location`, `date` | Open that Android Settings screen |
   | `diag` | Open the Ola diagnostic/service app |
   | `hmi` / `hmi-restart` | Bring the HMI to the front / restart it |
   | `home` / `back` / `recents` / `wake` / `top` | Key events and screen wake |
   | `key <code>` | Send an Android key code |
   | `tap x y` / `swipe x1 y1 x2 y2 [ms]` | Inject touch |
   | `shell <cmd>` | Root shell command (wrapped in `sh -c`) |
   | `screenshot <path>` | Save a PNG of the screen |
   | `push <local> <remote>` / `pull <remote> <local>` | Transfer files |
   | `info` | uid, Android build, scooter mode |

3. Diagnose failures against the known modes:
   - **"No answer after 8s"** — scooter asleep (red button / charger), wrong
     network, or wrong address/scope.
   - **`ola.py shell` needs `--raw`** to send a command exactly as typed — it wraps
     in `sh -c` and replaces spaces with `${IFS}` by default.
   - **`input tap` won't press the passcode keypad** — it is a Kanzi scene, not an
     Android dialog. Use the passcode skill instead.

4. Read the evidence, not the labels: `behaviour → hypothesis → smallest test →
   evidence → read the code`, then refine (docs/10).

Refer to `skills/ola-s1-connect/SKILL.md` for the full reference.