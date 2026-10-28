# 09 — Security findings

A clean list of what is actually wrong, with severity and how sure we are.

No keys, tokens, identifiers, or personal data are reproduced here. Where a
finding depends on a secret, the secret is described, not printed.

Confidence levels:

- **Confirmed** — seen working on a live unit.
- **Partially confirmed** — the mechanism is confirmed, the full impact is not.
- **Unverified** — identified from schema or config only; deliberately not tested.

---

## Summary table

| # | Finding | Severity | Confidence |
|---|---|---|---|
| 1 | Unauthenticated root command execution | Critical | Confirmed |
| 2 | Service listens on every interface | High | Partially confirmed |
| 3 | Port block is IPv4 only | High | Confirmed |
| 4 | No audit trail | Medium | Confirmed |
| 5 | Unauthenticated CAN write access | Critical (potential) | Partially confirmed |
| 6 | Unauthenticated firmware-update RPCs | Critical (potential) | Unverified |
| 7 | Firmware download without credentials | High | Confirmed |
| 8 | OTA decryption key inside the app | High | Confirmed for this bundle |
| 9 | ECU firmware stored world-readable | High | Confirmed |
| 10 | Device private key readable at rest | High | Confirmed |
| 11 | Static cloud credentials on device | High | Confirmed |
| 12 | Owner personal data in logs and prefs | Medium | Confirmed |
| 13 | Platform hardening switched off | High | Confirmed |
| 14 | adb auth bypassable by root | High | Confirmed |
| 15 | Hotspot exposes the root channel | High (conditional) | Partially confirmed |

---

## 1. Unauthenticated root command execution

**Critical · Confirmed**

`/vendor/bin/diagnostics_server` is a Go daemon started by `init` as root. It
listens on TCP 50005 with plain gRPC, no TLS, no token, no pairing.

Its `ExecuteADBCommand` method runs the supplied string as **uid 0** in context
`u:r:shell:s0`.

The shipped config sets `auth_enabled: false`. The TLS/mTLS code exists but
cannot start because its certificate directory is missing. So there is no
authentication path to enable, and no way to turn it on accidentally.

Impact: anyone who can reach the port gets root on the head unit.

---

## 2. Exposure on every network interface

**High · Partially confirmed**

The daemon binds to the IPv6 wildcard (`::`), so it listens on the station
Wi-Fi link, the hotspot, and the cellular interface.

The cellular interface has a global IPv6 address. Whether the carrier allows
inbound connections to it was **not tested**. Do not claim internet-wide
exposure without that test.

---

## 3. Port blocking is IPv4 only

**High · Confirmed**

In customer mode an init service adds:

```sh
iptables -I INPUT -p tcp --match multiport --dports 50005,5555 -j DROP
```

There is no matching `ip6tables` rule. IPv6 traffic to the same ports is
unaffected.

Measured in customer mode:

| Family | Rule |
|---|---|
| IPv4 | DROP on 50005, 5555 |
| IPv6 | none |

This is the whole bypass. It is a one-line mistake with a large consequence.

---

## 4. No audit trail

**Medium · Confirmed (configuration)**

The daemon config sets `enable_logging: false`, and the log file does not exist.
Commands sent to the service are not recorded by the daemon.

Whether `logcat` records anything about them was not tested. Do not assume
either way.

---

## 5. Unauthenticated CAN write access

**Critical if the vehicle obeys · Partially confirmed**

The RPC exposes `WriteCanFrame`, `WriteCanStream`, `ReadCanStream` and
`CanDumpStream` with no authentication.

Writes appear on `vcan0` and in the parser log. `vcan0` is bridged to the vehicle
bus through a UART converter, so the frames leave the head unit.

What was **not** demonstrated is that an ECU acts on an arbitrary written frame.
The one end-to-end attempt (`CTA_UNLOCK` on the real bus) produced no response.
That is a negative result, not proof of safety.

The correct statement is: unauthenticated write access exists, and its impact is
not fully characterised.

---

## 6. Unauthenticated firmware-update surface

**Critical (potential) · Unverified**

The service exposes:

- `SwUpdateFileTransferMeta`
- `SwUpdateFileTransfer`
- `SwUpdateTriggerUpgrade` (ALL, BCM, BMS, BLE, MCU, AOSP)
- `SwUpdateTestSetStatus`

The integrity check appears to rely on a checksum supplied by the caller.

These were identified from the schema only. They were **deliberately not
invoked**, because a failed ECU flash is not recoverable from the dash. Whether
unsigned images are accepted is unknown.

---

## 7. Firmware download without credentials

**High · Confirmed**

The OTA object for the installed release (about 1.14 GB) downloads with a plain
unauthenticated GET. The presigned URL has an expiry and a signature, but the
object serves **without any query string**.

The bucket ACL was not inspected, so "public bucket" is the wrong claim. The
correct claim is: the object is readable without credentials.

---

## 8. OTA decryption key inside the app

**High · Confirmed for this bundle**

The OTA package is AES-128-CBC encrypted. The key and IV are hardcoded in the
OTA APK as two asset files, and the two values are identical.

With those values the bundle decrypts and the extracted files match the manifest
hashes.

Whether the same key protects other bundles was not tested, but the key is
compiled into a downloadable APK, so it is not per-device and not secret.

**The value is not reproduced in this repo.**

---

## 9. ECU firmware stored world-readable

**High · Confirmed**

Decrypted ECU images (BCM, BMS, BLE, MCU) sit in
`/data/vendor/ola/recovery/firmwares/` with mode `0777`.

Any root process can read **or replace** them. Combined with finding 6, that is
the interesting part: the updater trusts a directory anyone with root can write.

---

## 10. Device private key readable at rest

**High · Confirmed**

A per-device EC P-256 private key is stored as a PEM file under
`/data/vendor/ola/secure/` and is readable by root through the RPC.

The research notes suggest it could impersonate the vehicle to the backend, but
that use was **not tested**. Treat "can impersonate" as a hypothesis.

---

## 11. Static cloud credentials on device

**High · Confirmed**

The daemon configuration stores a static provisioning API key and OAuth client
credentials (client ID and secret). Both were read through the RPC.

These should be rotated by the vendor. They are not reproduced here.

---

## 12. Owner personal data in logs and prefs

**Medium · Confirmed**

- **Hotspot password logged in plaintext.** This is the worst item in the group,
  because the hotspot password gates the LAN that serves the root channel.
- **Precise GPS coordinates** logged repeatedly at info level.
- **VIN** present in world-readable app preferences, logs, and CAN frames, while
  the manifest VIN field is blank.
- **Other owner data**: insurance document scans, the paired phone's name, and a
  crash-reporting device ID, all stored unencrypted.

None of it is reproduced in this repo.

---

## 13. Platform hardening switched off

**High · Confirmed**

| Setting | Value |
|---|---|
| Build tags | `test-keys` (not `release-keys`) |
| `ro.debuggable` | `1` |
| SELinux | permissive at runtime |
| Security patch | 2023-09-05 |

The security patch is roughly 20 months older than the build date. A debug build
with permissive SELinux removes most of the boundaries the rest of this document
relies on.

---

## 14. adb authentication bypassable by root

**High · Confirmed**

`adbd` is patched to choose its trusted key file from
`persist.ola.security.scootermode`. The factory key files are baked into the
image.

A root process can change the mode, then bind-mount its own public key over the
factory file. adb then accepts it.

This is not an independent vulnerability in practice: anyone who can do it
already has root through finding 1. Its value is as evidence that adb is not a
security boundary.

---

## 15. Hotspot exposes the root channel

**High (conditional) · Partially confirmed**

The diagnostic app can enable a Wi-Fi hotspot. It holds
`android.permission.TETHER_PRIVILEGED`, so it does this without user
confirmation.

When the hotspot is on, the daemon and adbd listen on the hotspot gateway, and
the port ACCEPT rule applies to that interface.

A client joining the hotspot should therefore reach the root service. That was
**not tested end-to-end**. In customer mode the IPv4 block still applies, but
IPv6 still does not.

The hotspot SSID and password change on every start, which limits the window but
does not fix the design.

---

## What is not claimed

This list deliberately leaves out:

- anything that was not reproduced on a live unit or read from a shipped file;
- guesses about backend behaviour;
- exploit steps that would be needed to weaponise a finding;
- any secret value.

If you are writing this up for the vendor, keep the confidence column. A
"Critical (potential)" is not the same as a "Critical (Confirmed)", and mixing
them destroys trust in the whole report.

---

## If you report this

Report privately first. Give the vendor time to fix before publishing.

A useful report contains:

1. the exact build and patch level you tested,
2. the smallest reproduction that proves the issue,
3. what you did **not** test,
4. a suggested fix.

A useful fix list for this platform:

- add an `ip6tables` rule that matches the `iptables` one;
- bind the daemon to `lo` or to a specific interface, not `::`;
- enable authentication and ship the certificates;
- remove `test-keys`, `ro.debuggable=1` and permissive SELinux from production;
- stop logging credentials and location;
- do not ship a shared OTA key in the client;
- verify the OTA download with the SHA-256 the manifest already carries;
- restrict `/data/vendor/ola/recovery/firmwares/` to the updater.
