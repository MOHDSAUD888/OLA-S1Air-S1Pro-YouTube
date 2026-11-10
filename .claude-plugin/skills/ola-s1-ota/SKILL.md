---
name: ola-s1-ota
description: The Ola S1 OTA / firmware update path: how an update arrives over MQTT, the unprotected CloudFront download, and why not to flash. Use when the user wants to understand the update chain, grab a firmware image, or assess the OTA security. Trigger: OTA, firmware, update, MQTT ConfigCommand, CloudFront presigned, .tar, Update Engine, A/B, ECU flash, bricking, download firmware.
---

# Ola S1 — OTA and firmware

This chapter is here so you understand the update path, not so you use it.

**Do not flash anything.** There is no step in this repo that needs a partition
write, and a failed ECU flash can brick the scooter.

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

## The download is not protected

The URL in the prefs is a presigned CloudFront link with `Expires`, `Signature`
and `Key-Pair-Id`.

Requesting the same object **with no query string at all** returns `200` and the
full body. The signature is not enforced at the edge.

That is not a guess. It was checked with a plain `curl -I`.

This matters because it means the firmware image is effectively public. Anyone who
can guess the object path can download it.

## The loop

`behaviour → hypothesis → smallest test → evidence → read the code`, then
refine. Do not stop at "it works once" — one working call proves one call, not a
schema (docs/10). And never treat a decompiled symbol as proof; prove the call
with the machine first.

## When to use this skill

- User wants to understand the OTA update chain.
- User wants to grab a firmware image and knows the risk.
- User wants to assess OTA security: the MQTT auth boundary and the unsigned
  CloudFront download.
- User is deciding whether to attempt any flashing and needs the facts first.