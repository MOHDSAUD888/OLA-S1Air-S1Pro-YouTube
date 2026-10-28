# 03 — ADB and full access

You do **not** need adb to run the Settings script. The gRPC channel already
gives you root. Use adb only when you want the normal tools: `scrcpy`, Android
Studio, `adb push/pull`, `logcat`, `dumpsys`.

This page is the whole adb unlock, start to finish, and how to undo it.

---

## What "adb blocked" actually means

It is not a security feature. It is 30 lines of init script in
`/vendor/etc/init/ola_e2.rc`:

```
on property:persist.ola.security.scootermode="cust"
    start close_port
    stop adbd
    start adbd

on property:persist.ola.security.scootermode="mfg"
    start open_port
    stop adbd
    start adbd
```

and two one-shot services that add an `iptables` rule:

```
service close_port ... iptables -I INPUT -p tcp --match multiport \
        --destination-port 50005,5555 -j DROP
service open_port  ... iptables -I INPUT -p tcp --match multiport \
        --destination-port 50005,5555 -j ACCEPT
```

Three things follow from that:

1. The property is a plain `persist.` property. Root can write it.
2. `mfg` and `serv` behave the same for networking. They differ only in which
   key file adbd reads.
3. The rule is **IPv4 only**. There is no `ip6tables` rule. That is why IPv6
   works even in `cust`.

The mode change does not reboot, does not touch the CAN path, and does not
change any vehicle setting. It opens a port and restarts adbd.

---

## The part that surprises people

Flipping the mode is **not enough**. adbd has an Ola patch: the key file it
trusts depends on the mode.

| mode | key file adbd reads |
|---|---|
| `mfg` | `/adb_keys` |
| `serv` | `/adbkeymoveos3_pub_prod` |
| `cust` | refuses before it loads any key |

Both of those files are baked into the read-only system image and contain keys
belonging to Ola's build machines. Your key in `/data/misc/adb/adb_keys` is
**ignored** — adbd never reads that path in these builds.

So you get `failed to authenticate` even though your key looks "already
trusted". The fix is to bind-mount your own public key over the file adbd
actually reads.

`/` is mounted read-only, and you should not edit it anyway. A bind mount is
runtime-only and disappears on reboot.

---

## The three commands that matter

```sh
setprop persist.ola.security.scootermode serv
mount --bind /data/local/tmp/adb_keys_mine /adbkeymoveos3_pub_prod
adb connect [fe80::1234:5678:9abc:def0%6]:5555
```

That is the whole trick. `scripts/unlock-adb.ps1` does all of it, including
making the key and pushing it.

```powershell
.\scripts\unlock-adb.ps1 -IPv6 fe80::1234:5678:9abc:def0
```

Use `-Mode mfg` if you prefer the other key path. Use `-Undo` to put everything
back.

---

## Why `mount` must go in a script

A bare `mount --bind ...` sent to `ExecuteADBCommand` returns `status=2` with no
output, even though `/system/bin/mount` exists. The daemon's whitespace splitter
mangles the arguments.

It works from a pushed `.sh` file:

```sh
sh -c echo${IFS}<base64>|base64${IFS}-d>/data/local/tmp/bind_key.sh
sh -c chmod${IFS}755${IFS}/data/local/tmp/bind_key.sh
sh /data/local/tmp/bind_key.sh serv
```

This is a general rule, not an adb rule: **if a command has flags with values,
put it in a `.sh` file and run the file.**

---

## The watchdog

Before changing the mode, arm something that changes it back. Otherwise a
dropped connection leaves the unit in service mode.

`unlock-adb.ps1` does not arm one automatically, because a watchdog that fires
mid-session is its own problem. If you are experimenting by hand, push a small
script that:

- sleeps in 1 s steps for up to 180 s,
- exits early if a cancel file appears,
- otherwise runs `setprop persist.ola.security.scootermode cust`.

Then start it detached so the RPC returns immediately. `touch` the cancel file
when you are done.

A reboot is also a full reset. That is the simplest way back.

---

## The adb binary

`platform-tools` **37.0.1** failed to start its daemon on the test machine: it
could not open its own log file in `%TEMP%`, and moving `TEMP` did not help.
That is a fault in that build, not a permission problem.

**35.0.2** works. If your adb behaves strangely, try a different version before
you blame the scooter.

```powershell
adb version
adb devices -l
```

A working connection looks like:

```
[fe80::1234:5678:9abc:def0%6]:5555   device   product:vnd_tb8768p1_64_bsp
```

Note: adb shell runs as **uid 2000 (shell)**, not root. The gRPC channel is
still the root path. Use adb for convenience, not for privilege.

---

## scrcpy

With adb connected:

```powershell
scrcpy --tcpip
```

or point it at the address directly. The screen is 800×480, which is small on a
desktop; `--max-size 800` keeps it sharp without scaling artefacts.

Two warnings:

- **Never restart adbd while scrcpy is connected.** You will lose the stream and
  sometimes the video encoder. Reconnect instead.
- scrcpy input goes through Android's input system, so it works on Settings and
  other normal apps. It does **not** drive the Kanzi HMI — see doc 07.

---

## What survives a reboot

| Thing | Survives reboot? |
|---|---|
| `setprop persist.ola.security.scootermode` | **Yes** (it is a `persist.` property) |
| `mount --bind` of the key | No |
| `/data/local/tmp` files | Usually yes |
| Your adb authorisation | Only if the bind mount is still there |

So after a reboot the port is open but adb will refuse you again. Re-run the
bind mount, or just use the gRPC channel, which needs no key at all.

There is no on-device boot hook that survives: property triggers do not fire at
boot in the way you would need, and `/system` is read-only. The practical
solution is a small PC-side loop that re-applies the bind mount when adb drops.

---

## Undo

```powershell
.\scripts\unlock-adb.ps1 -IPv6 fe80::1234:5678:9abc:def0 -Undo
```

Which does:

```sh
umount /adbkeymoveos3_pub_prod
umount /adb_keys
setprop persist.ola.security.scootermode cust
adb disconnect [fe80::1234:5678:9abc:def0%6]:5555
```

Then the IPv4 ports are dropped again and adbd refuses keys. The IPv6 gRPC
channel keeps working, because the firewall rule was never IPv6.

---

## Gotchas

| Symptom | Cause | Fix |
|---|---|---|
| `failed to authenticate` | key file not the one adbd reads | bind-mount it (above) |
| `connection refused` | mode still `cust`, or adbd restarting | wait 2 s, check the property |
| `mount` returns 2, no output | whitespace splitter | run it from a `.sh` |
| adb daemon will not start | bad platform-tools build | use 35.0.2 |
| scrcpy shows a black screen | display is off | `input keyevent 224` over gRPC |
| adb drops every few minutes | Wi-Fi roaming / DHCP change | reconnect by address |

---

## Do you actually need this?

For most of what this repo does, no.

| Task | Needs adb? |
|---|---|
| Open Settings | No |
| Root shell | No |
| Screenshot | No |
| Read CAN | No |
| Pull a file | No (chunked base64) |
| scrcpy mirror | Yes |
| Android Studio / ddms | Yes |
| Normal `adb push` of large files | Yes (faster) |

The gRPC channel is the primary tool. adb is the comfortable one.
