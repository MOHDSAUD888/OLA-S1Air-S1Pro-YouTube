# 08 — OTA and firmware

This chapter is here so you understand the update path, not so you use it.

**Do not flash anything.** There is no step in this repo that needs a partition
write, and a failed ECU flash can brick the scooter.

---

## How an update arrives

There is no hardcoded update URL in the OTA app. The link is pushed at runtime
over **MQTT** by Ola's cloud:

```
Ola cloud  →  MQTT ConfigCommand  →  com.olaelectric.ota
           →  download a .tar
           →  unpack, decrypt, verify
           →  Update Engine (A/B) + ECU flashers
```

The OTA app keeps a large local MQTT history database. The push carries a
`ConfigCommand` with a UUID and JSON parameters — the same shape as the service
config blob, which also contains direct CAN write instructions for the BCM.

So the only authenticated part of the chain is the **MQTT session**. The artifact
itself is a plain HTTPS object.

---

## The download is not protected

The URL in the prefs is a presigned CloudFront link with `Expires`, `Signature`
and `Key-Pair-Id`.

Requesting the same object **with no query string at all** returns `200` and the
full body. The signature is not enforced at the edge.

That is not a guess. It was checked with a plain `curl -I`.

This matters because it means the firmware image is effectively public. Anyone
who can guess the object path can download it.

---

## The path is guessable

```
<semver>-IMCU-<DD-Mon>-rel/<semver>-IMCU-<DD-Mon>.ota.tar
```

Version numbers are sequential and the date is in the name. That is enough to
enumerate releases.

The research did probe for versions above the installed one and found nothing
newer at the time. That is a statement about that moment, not a permanent fact.

---

## What is inside the package

The `.tar` contains an **encrypted zip**. The zip holds five payloads plus a
signed manifest.

Install order is fixed by the manifest:

```
%reboot → mcu → bcm → ble → aosp → bms → %reboot
```

| Module | What it is |
|---|---|
| `aosp` | the Android head-unit image |
| `mcu` | motor controller firmware |
| `bcm` | body control module |
| `ble` | Bluetooth security slave |
| `bms` | battery management firmware |

One package covers **every hardware variant**, including parts your scooter may
not have. The manifest decides what applies.

The download is gated on **MD5 only**, even though the manifest carries SHA-256
per module. The strong hash exists but is not what the downloader checks.

---

## The encryption key is in the app

`PackageDecryptor` does not use a device key, a keystore entry, or anything
provisioned per unit. It reads two asset files from the OTA APK itself:

```
HOTA.apk!assets/key.txt
HOTA.apk!assets/iv.txt
```

Both contain the same 32 hex characters, which decode to 16 bytes. The cipher is
`AES/CBC/PKCS5Padding`, and the IV equals the key.

That value is a placeholder pattern, not a secret: it is compiled into a publicly
downloadable APK, so it is **identical on every unit running this build**. One
APK decrypts every bundle Ola publishes for every vehicle, until they rotate it.

**The value is deliberately not reproduced in this repo.** If you need it, read
it out of your own APK. Publishing it adds nothing to the research and turns a
design flaw into a copy-paste tool.

Verification that the scheme is real: decrypting a 64-byte window at the start of
the encrypted payload yields a valid zip local file header (`PK\x03\x04...`).
Wrong keys produce noise.

---

## What the firmware images are

The five payloads were identified at the CPU level, not guessed:

| Image | Architecture | Notes |
|---|---|---|
| BCM | ARM Thumb-2 | Cortex-M, normal Ghidra |
| BMS | ARM Thumb-2 | Cortex-M |
| BLE | ARM Thumb-2 | Cortex-M |
| ABS | PowerPC BookE, VLE | needs a Ghidra module |
| MCU | TI C2000 C28x | needs a third-party Ghidra module |

The method matters, because ISA guessing is where this work usually goes wrong.
"Does it disassemble?" is not evidence — Thumb-2 decodes most random bytes. The
research used a **prologue census** (`bx lr`, `push`, `pop`, `stwu`, `blr`) and a
**skip-data valid-instruction ratio**, with known-Thumb-2 images as controls.

That is how the MCU image was ruled out of Thumb-2 despite a high raw decode
rate: reversing the whole file raised the ratio, which no real instruction stream
does.

---

## What this means for security

The chain is:

```
MQTT (authenticated)
  → ConfigCommand
  → presigned URL (not enforced)
  → 1.17 GB tar
  → encrypted zip (key in the public APK)
  → MD5 check
  → Update Engine A/B + ECU flashers
```

So:

- the artifact is world-readable if you know the path;
- its confidentiality depends on a key shipped in the client;
- its integrity gate is MD5, not the SHA-256 the manifest already carries;
- the manifest contains a placeholder timestamp (`123456789`) in production;
- per-module retries are 5, install timeout 80 minutes.

None of that is a remote code execution bug on its own. It is a supply-chain
design that assumes nobody looks.

---

## What was not tested, and should stay untested

- Calling `SwUpdateFileTransferMeta`, `SwUpdateFileTransfer`,
  `SwUpdateTriggerUpgrade` or `SwUpdateTestSetStatus`
- Writing any partition
- Flashing any ECU
- Pressing **START** on `Provision > Generate Certificates`
- Feeding a modified package to the updater to see if it is accepted

A failed MCU or BMS flash is not recoverable from the dash. Do not "just try it".

---

## If you want to study the firmware safely

1. Download the package on a PC, not on the scooter.
2. Keep it off the vehicle network.
3. Decrypt and extract on the PC.
4. Analyse the images offline with Ghidra.
5. Never copy a modified image back.

The package is 1.17 GB. Put it on a data drive, not in a repo. `.gitignore`
already excludes `*.tar`, `*.zip`, `*.enc` and `*.bin`.

---

## Honest summary

The OTA path is interesting because it shows how the whole product is built:
fast, functional, and trusting the client. The scooter will install what it is
told to install, and the checks in the middle are weak.

That is a finding for Ola to fix. It is not a toy for an owner to poke at.
