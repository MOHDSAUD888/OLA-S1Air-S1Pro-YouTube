# 04 — Open Android Settings on the scooter

This is the headline script: one command, one IPv6 address, and the scooter's
screen shows the real Android Settings app.

```powershell
.\scripts\open-settings.ps1 -IPv6 fe80::1234:5678:9abc:def0
```

That is it. No adb, no pairing, no password, no key.

---

## What the script does

1. Finds Python.
2. Installs `grpcio` if it is missing (once).
3. If the address is link-local and has no `%scope`, looks the interface number
   up in the Windows neighbour table.
4. Calls `ola.py settings`, which connects to `[addr]:50005` and sends:

```
am start -a android.settings.SETTINGS
```

5. Waits 1.5 s, then prints the activity that is now on screen.

Nothing is written to the scooter. This is the same thing as tapping a Settings
icon on a phone.

---

## Why `am start` and not a tap

The dash is not a normal Android app. It is a Kanzi scene graph rendered into a
single surface (doc 07). Android's `input tap` reaches the surface, but the
Kanzi runtime does not consume synthetic touches from `input`.

`am start` does not go through the UI at all. It asks Android's activity manager
to launch an activity. That works regardless of what is drawing the screen.

| Method | Works? | Why |
|---|---|---|
| `am start -a android.settings.SETTINGS` | **Yes** | activity manager, no touch needed |
| `input tap <x> <y>` on the HMI | No | Kanzi ignores injected touches |
| `input keyevent 3` (HOME) | Yes | system key, handled by Android |
| `am start -n com.ola.mfgapp/.MainActivity` | **Yes** | same reason |

So: **launch intents, do not tap.**

---

## The scripts

### Windows

```powershell
# Settings
.\scripts\open-settings.ps1 -IPv6 fe80::1234:5678:9abc:def0

# Settings, force-stopped first (clears a stale Settings screen)
.\scripts\open-settings.ps1 -IPv6 fe80::1234:5678:9abc:def0 -Fresh

# The Ola technician app instead
.\scripts\open-settings.ps1 -IPv6 fe80::1234:5678:9abc:def0 -Diag

# Back to the dash
.\scripts\open-settings.ps1 -IPv6 fe80::1234:5678:9abc:def0 -Home
```

If the address is link-local, the script finds the scope itself. You can also
pass `-Scope 6` explicitly.

### Linux / macOS

```sh
./scripts/open-settings.sh fe80::1234:5678:9abc:def0%6
./scripts/open-settings.sh fe80::1234:5678:9abc:def0%6 --diag
./scripts/open-settings.sh fe80::1234:5678:9abc:def0%6 --home
```

On Linux the scope is the interface **name** (`%wlan0`), not a number. Use
`--scope` if you prefer the number.

### Python, directly

```powershell
py scripts\ola.py settings --ipv6 fe80::1234:5678:9abc:def0 --scope 6
```

The options work before or after the action, so both of these are the same:

```powershell
py scripts\ola.py settings --ipv6 fe80::1%6
py scripts\ola.py --ipv6 fe80::1%6 settings
```

---

## Other screens you can open

`ola.py` maps a set of standard Android settings intents:

| Command | Opens |
|---|---|
| `settings` | main Settings |
| `wifi` | Wi-Fi |
| `bluetooth` | Bluetooth |
| `apps` | Apps |
| `display` | Display |
| `sound` | Sound |
| `battery` | Battery saver |
| `storage` | Storage |
| `about` | About device |
| `notifications` | Notifications |
| `location` | Location |
| `date` | Date and time |

```powershell
py scripts\ola.py wifi --ipv6 fe80::1%6
```

And two Ola-specific ones:

| Command | Opens |
|---|---|
| `diag` | `com.ola.mfgapp` — the technician app with the hotspot toggle |
| `hmi` | brings the dash back to the front |

---

## Getting back to the dash

```powershell
py scripts\ola.py home --ipv6 fe80::1%6
```

That sends the HOME key. Because the HMI app declares `android.intent.category.HOME`,
HOME lands back on the dash, not on a launcher.

If the dash is blank or white instead of drawing, the HMI surface is gone. Restart
it:

```powershell
py scripts\ola.py hmi-restart --ipv6 fe80::1%6
```

That force-stops `com.olaelectric.hmiclient`, starts it again, then sends
`KEYCODE_WAKEUP` and `KEYCODE_MENU` to wake the panel. It takes a few seconds.
Do not do it mid-drive.

---

## What you can actually change in Settings

Settings is the real AOSP app running as a system app. Over the gRPC root shell
you can also change things Settings will not show you, for example:

```powershell
py scripts\ola.py shell --ipv6 fe80::1%6 "settings put system screen_off_timeout 600000"
py scripts\ola.py shell --ipv6 fe80::1%6 "svc power stayon true"
```

Be careful. Some settings affect the dash, and some are reset by the HMI on boot.
The vehicle itself is not reachable from here — Settings cannot unlock, start, or
charge the scooter.

---

## If it does not work

| Symptom | Check |
|---|---|
| `No answer from ... after 8s` | is the scooter awake? same Wi-Fi? right address? |
| `This is a link-local address...` | add `%6` or `--scope 6` |
| Command returns, screen unchanged | the display may be off: `ola.py wake` |
| Settings opens then closes | something else took focus; try `--fresh` |
| Works once, then stops | DHCP changed the address; re-run `find-scooter.ps1` |
| `Missing grpcio` | `py -m pip install -r scripts\requirements.txt` |

The fastest sanity check is:

```powershell
py scripts\ola.py info --ipv6 fe80::1%6
```

If that prints `uid 0`, the channel is fine and the problem is the intent, not
the network.
