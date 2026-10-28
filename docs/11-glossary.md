# 11 — Glossary

Plain words for the things this repo keeps saying.

---

## Networking

**IPv4** — the old address format: `192.168.1.50`. Four numbers.

**IPv6** — the new format: `fe80::21a:2bff:fe3c:4d5e`. Long, hex, with `::`
standing for a run of zeros.

**Link-local address** — an IPv6 address that only works on the local network
segment. Always starts with `fe80::`. It is not routable and it is not unique
across networks.

**Scope / scope id** — the number or name of the network interface a link-local
address belongs to, written after `%`. `fe80::1%6` means "interface 6".
Without it the kernel does not know which network to send on. On Windows it is a
number; on Linux it is usually a name like `wlan0`.

**EUI-64** — the rule that builds a link-local IPv6 address from a MAC address:
flip one bit, insert `FF:FE` in the middle, prefix `fe80::`. That is why the
address is stable across reboots.

**NDP** — Neighbour Discovery Protocol. IPv6's version of ARP. `ip -6 neigh` or
`Get-NetNeighbor -AddressFamily IPv6` shows the table.

**Port** — a number that picks a service on a machine. 50005 is the root daemon
here; 5555 is adb.

**iptables / ip6tables** — the firewall tools for IPv4 and IPv6. They are
separate. A rule in one does not apply to the other. That separation is the
central mistake in this whole story.

**gRPC** — a remote procedure call system. You call a method on a server as if it
were local. It runs over HTTP/2.

**mTLS** — mutual TLS: both sides show certificates. The code for it exists on
this device but cannot start because the certificate folder is missing.

**protobuf / proto** — the message format gRPC uses. A `.proto` file describes
the methods and fields. Field numbers matter more than field names.

**Wire type** — the low 3 bits of a protobuf field key, saying whether the value
is a number, a string, etc. A guessed schema can work by accident if the wire
type matches.

---

## Android

**ADB** — Android Debug Bridge. The normal developer tool for shell access,
file transfer, screenshots and app installs.

**adbd** — the daemon on the device that adb talks to. Here it is patched by Ola
to pick its trusted key file from a property.

**AOSP** — Android Open Source Project. The base Android that vendors modify.

**SELinux** — the mandatory access control system. `Enforcing` blocks things;
`Permissive` only logs them. This unit runs permissive.

**Context** — the SELinux label a process runs under, e.g. `u:r:shell:s0`.

**uid 0** — root. The user that can do anything.

**Intent** — an Android message asking the system to do something, e.g. "open
Settings". Started with `am start`.

**Activity** — one screen in a normal Android app.

**Package name** — the unique app id, e.g. `com.olaelectric.hmiclient`.

**`am`** — Activity Manager command-line tool. `am start` launches things.

**`input`** — command-line tool that injects key and touch events. Works on
normal apps; does not drive Kanzi.

**`screencap`** — takes a screenshot.

**`dumpsys`** — dumps system service state. `dumpsys activity activities` tells
you what is on screen.

**`logcat`** — the system log. Often the best data source on the device.

**`getprop` / `setprop`** — read and write system properties. Properties starting
with `persist.` survive a reboot.

**test-keys** — a build signed with public test keys instead of the vendor's
private release keys. A debug-build marker.

**`ro.debuggable=1`** — the property that says this is a debuggable build.

---

## The scooter

**Head unit / HMI** — the Android computer behind the dashboard screen.

**HHMIC** — `com.olaelectric.hmiclient`, the app that draws the UI.

**HHMIS** — `com.android.hmiserverapp`, the server half of the UI and its state
machine.

**HVDP** — `com.olaelectric.vehicleparser`, the app that decodes CAN frames.

**HPMS** — `com.olaelectric.profilemanagerservice`, rider profiles and the
passcode check.

**HOTA** — `com.olaelectric.ota`, the updater.

**HCCM** — `com.olaelectric.cloudconnectivitymanager`, the cloud link.

**VCU** — Vehicle Control Unit. The main vehicle computer.

**MCU** — Motor Control Unit. Drives the motor.

**BMS** — Battery Management System. Owns the pack, the cells and the wake logic.

**BCM** — Body Control Module. Lights, locks, body electrics.

**BLE** — Bluetooth Low Energy module (also the protocol).

**ECU** — Electronic Control Unit. Any of the small computers on the bus.

**SOM** — System On Module. The compute board inside the head unit.

---

## CAN

**CAN bus** — the two-wire network the vehicle computers talk on.

**CAN ID** — the number that identifies a frame, e.g. `0x120`. It is an
identifier, not a destination address.

**DLC** — Data Length Code. How many data bytes the frame carries (0–8 here).

**Frame** — one CAN message: an ID plus up to 8 data bytes.

**`vcan0`** — the virtual CAN interface inside Android. A UART converter bridges
it to the real vehicle wire.

**`candump`** — prints frames from an interface.

**`cansend`** — sends one frame.

**Little-endian / big-endian** — byte order. In this repo, the CAN ID in the gRPC
packet is big-endian, while most signal values are little-endian. Getting this
wrong makes writes vanish silently.

**LE16** — a 16-bit little-endian number. `LE32` is 32-bit.

**DTC** — Diagnostic Trouble Code. The standard fault codes. This vehicle does
not expose a DTC read channel; it only broadcasts fault bits.

**SOC** — State of Charge. How full the battery is, in percent.

**SOH** — State of Health. How much capacity the pack has left, in percent.

**Regen** — regenerative braking. The motor charges the battery while slowing.

**Wake reason** — why the vehicle woke up: charger, movement, button, clock, or
factory tool. The head unit cannot add one.

---

## UI

**Kanzi** — the UI framework the dash uses. It draws its own scene graph into one
surface; it is not made of Android views.

**Scene graph** — a tree of UI nodes. Here it holds about 1,943 screens under one
root.

**`.kzb`** — a Kanzi resource bundle. The main one is 644 MB.

**Prefab** — a reusable UI component in Kanzi. Most popups are prefabs toggled by
a boolean.

**Data context** — a set of values bound into the UI. 20 of them here.

**Page transition** — how Kanzi switches screens, inside the same scene graph.

**Virtual display** — a secondary Android display used to host an embedded app
inside the Kanzi surface.

**HOME category** — the intent category that makes an app the launcher. The HMI
has it, so there is no normal launcher behind it.

---

## Tools and techniques

**REA** — reverse engineering and analysis. Taking a shipped thing apart to
understand how it works.

**Decompile** — turn compiled code back into readable source. `jadx` does this
for Android apps.

**Descriptor** — a machine-readable schema embedded in a binary. The gRPC one
here gave the real 40-method service.

**Bind mount** — make a file or folder appear at another path, at runtime, without
changing the disk. Used here to swap the adb key file.

**Chunking** — splitting a big file into small pieces to move it over a channel
that cannot take it whole.

**Base64** — a way to send binary data as text. Costs about 33 % size.

**`${IFS}`** — a shell variable that expands to a space. Used here to send
commands with no literal spaces, because the daemon splits on whitespace.

**Fail closed / fail open** — whether a check denies or allows when it cannot
run. The TLS path here fails closed because it cannot start at all; the firewall
fails open for IPv6 because there is no rule.

**Negative result** — a test that proved something does *not* work. As valuable
as a positive one, and much rarer in write-ups.
