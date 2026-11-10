---
name: ola-s1-mirror
description: Mirror the Ola S1 dash screen to a computer and inject touch back: the on-dash scrcpy-style agent, adb, and the unlock-adb path. Use when the user wants to see or control the screen remotely, run the mirror agent, or set up wireless adb. Trigger: screen mirror, scrcpy, mirror agent, app_process, SurfaceControl, InputManager, wireless adb, unlock-adb, adbkey, touch injection, H.264 stream.
---

# Ola S1 — Mirror the screen

Three things get you a live, touchable view of the dash: root on the head unit
(the connect skill), a normal adb connection, and a screen mirror. Everything
else in this repo builds on those three.

## The on-dash agent

`app/mirroragent` is an on-device screen agent. Run as root via `app_process`
(not installed), so the hidden `SurfaceControl` / `InputManager` calls are
allowed:

```
CLASSPATH=/data/local/tmp/mirror.apk app_process / \
    dev.olatool.mirror.Agent [port] [width] [height] [bitrate]
```

It mirrors the built-in display into an H.264 encoder (exactly scrcpy's
Android-10 technique) and streams it over a plain TCP socket the phone connects
to directly over Wi-Fi. The same socket carries touch events back, which are
injected into the system.

Wire protocol (all big-endian):

```
server -> client: header  = int width, int height
                    packet  = int len, byte flags, len bytes of H.264
                              flags bit0 = codec config (SPS/PPS)
                              flags bit1 = key frame
client -> server: control = byte action (0 down,1 up,2 move), int x, int y
```

Everything hidden is called by reflection so the module compiles against the
public SDK with no `@hide` stubs.

## adb

`scripts/unlock-adb.sh` / `unlock-adb.ps1` take you the rest of the way to a
normal `adb` connection over Wi-Fi. You are done when:

```sh
python scripts/ola.py info --ipv6 ...   # uid 0
adb devices -l                          # device
scrcpy --tcpip                          # mirror works
```

## The loop

`behaviour → hypothesis → smallest test → evidence → read the code`, then
refine. Do not stop at "it works once" — one working call proves one call, not a
schema (docs/10).

## When to use this skill

- User wants to see or control the screen remotely.
- User wants to run the mirror agent or understand its wire protocol.
- User wants to set up wireless adb on the scooter.
- User is debugging touch injection or the H.264 stream.
- User has reached the "root + adb + mirror" milestone and wants to know what's next.