---
name: ola-s1-cloud
description: Read your own scooter's telemetry from Ola's cloud: the exact header set, the KongService endpoints, and the read-only battery/location calls. Use when the user wants remote battery, location, or fleet data, or to understand the cloud API this repo reverse-engineered. Trigger: Ola cloud, kong2.olaelectric.com, X-ACCESS-TOKEN, vehicle statistics, battery, location, fleet, OlaCloud, KongService, telemetry, UDA-UUID.
---

# Ola S1 — The Ola cloud (read-only telemetry)

Reverse-engineered from the official companion APK (`com.olaelectric.companion`, app
version **v5.4.1**) via jadx. Native Kotlin, okhttp3 + Retrofit + Gson, R8-obfuscated.
Main API interface = `KongService` (decompiled `tq/e.java`). **Read-only telemetry
only** — battery + location. The full map is [OLA STOCK APK/OLA_CLOUD_API.md](OLA%20STOCK%20APK/OLA_CLOUD_API.md).

## Services & base URLs

| Service (iface) | Base URL | Interceptor | Auth headers it adds |
|---|---|---|---|
| **KongService** `tq.e` (vehicle/battery/location) | `https://kong2.olaelectric.com/` | `KongInterceptor` (`sq.e`) | `X-ACCESS-TOKEN`, `X-UDA-UUID` + device headers |
| Maps `tq.f` | `https://maps.olaelectric.com/` | `LbsInterceptor` (`sq.f`) | `Authorization: Bearer <jwt>`, `X-Client-Id` |
| EV-UI `tq.g` | `https://ola-ev-ui.olaelectric.com/` | `sq.a`/`sq.g` | same Kong-style |
| legacy olacabs `tq.d` | `https://om.olacabs.com/` | `sq.d` | — |

A `ServiceAuthenticator` is attached to Kong/EV-UI clients and does the 401 →
token-refresh.

## Exact header set the KongInterceptor adds to EVERY kong2 call

```
X-Device-ID:    <device id>
X-Request-ID:   <random uuid per request>
X-APP-VERSION:  v5.4.1
X-Device-APP:   <device-app value>
locale:         en
country_code:   IN
config_locale:  en
User-Agent:     <ua value>
X-UDA-UUID:     <account/vehicle uuid>   # only if present
X-ACCESS-TOKEN: <access token>           # only if present  <-- the auth token
```

Hardcoded constants: `X-APP-VERSION = v5.4.1`; maps `X-Client-Id =
acfd432e-ffe1-4875-8732-18b71639ccfc`.

## Telemetry endpoints (the goal) — all on kong2, GET

| Method / path | Retrofit | Response model → key fields |
|---|---|---|
| `GET api/config/v2/poll/vehicle/statistics` | `L0()` | **`ScooterStatusResponseData`** — everything in one call |
| `GET thor/v1/vehicle/info` | `u1()` | `ScooterInfoResponseData` (VIN, variant, uuid) |
| `GET /v1/vehicles/location?uda_uuid=<uuid>` | `A0()` | `ScooterLocationResultData {status, data:{…}}` |
| `GET thor-stc/v1/users/vehicles/batteryConsumptionStatistics` | `m1()` | `BatteryConsumptionStatisticsResponse` |
| `GET /v1/locations?uda_uuid=&since=&till=` | `n1()` | `PushedLocationResponseData` (location history) |

## Auth = headers, not a body

The Kong interceptor adds the auth headers. **Login is NOT reimplemented here** —
olacabs SSO runs on `apps2.olacabs.com`. The app in this repo (`OlaCloud.java`)
lets the owner drop in their own `X-ACCESS-TOKEN` and `X-UDA-UUID` via the Cloud
tab's "Set token" dialog, then polls `vehicle/statistics` for battery, charging,
range, lock status, and last location.

## The loop

`behaviour → hypothesis → smallest test → evidence → read the code`, then
refine. Do not stop at "it works once" — one working call proves one call, not a
schema (docs/10).

## When to use this skill

- User wants remote battery, range, or location for their own scooter.
- User wants the exact header set or endpoint list for the Ola cloud.
- User is wiring up `OlaCloud.java` or the Cloud tab.
- User wants a multi-unit fleet view (`FleetTab`).
- User asks about the cloud API this repo reverse-engineered.