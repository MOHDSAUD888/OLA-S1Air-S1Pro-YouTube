# Ola Electric companion app — cloud API map (exact, from decompile)

Reverse-engineered from `OLA APK.apk` (`com.olaelectric.companion`, app version **v5.4.1**)
via jadx. Native Kotlin, okhttp3 + Retrofit + Gson, R8-obfuscated. Main API interface =
`KongService` (decompiled `tq/e.java`). **Read-only telemetry only** (battery + location).

## Services & base URLs (from the Hilt graph, `ak/r.java`)
| Service (iface) | Base URL | Interceptor | Auth headers it adds |
|---|---|---|---|
| **KongService** `tq.e` (vehicle/battery/location) | `https://kong2.olaelectric.com/` | `KongInterceptor` (`sq.e`) | `X-ACCESS-TOKEN`, `X-UDA-UUID` + device headers |
| Maps `tq.f` | `https://maps.olaelectric.com/` | `LbsInterceptor` (`sq.f`) | `Authorization: Bearer <jwt>`, `X-Client-Id` |
| EV-UI `tq.g` | `https://ola-ev-ui.olaelectric.com/` | `sq.a`/`sq.g` | same Kong-style |
| legacy olacabs `tq.d` | `https://om.olacabs.com/` | `sq.d` | — |

A `ServiceAuthenticator` is attached to Kong/EV-UI clients and does the 401 → token-refresh.

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
Hardcoded constants found: `X-APP-VERSION = v5.4.1`; maps `X-Client-Id = acfd432e-ffe1-4875-8732-18b71639ccfc`.

## Telemetry endpoints (the goal) — all on kong2, GET
| Method / path | Retrofit | Response model → key fields |
|---|---|---|
| `GET api/config/v2/poll/vehicle/statistics` | `L0()` | **`ScooterStatusResponseData`** — everything in one call |
| `GET thor/v1/vehicle/info` | `u1()` | `ScooterInfoResponseData` (VIN, variant, uuid) |
| `GET /v1/vehicles/location?uda_uuid=<uuid>` | `A0()` | `ScooterLocationResultData {status, data:{…}}` |
| `GET thor-stc/v1/users/vehicles/batteryConsumptionStatistics` | `m1()` | `BatteryConsumptionStatisticsResponse` |
| `GET /v1/locations?uda_uuid=&since=&till=` | `n1()` | `PushedLocationResponseData` (location history) |

### `ScooterStatusResponseData` JSON fields (Gson `@SerializedName`)
Battery/charge: `battery_soc` (Float %), `charging_status`, `charging_status_ex`,
`time_to_full_charge`, `time_to_80_charge`, `range` (obj), `home_range`, `regen_status`.
Location: `latitude`, `longitude`, `horizontal_accuracy`, `vertical_accuracy`,
`network_latitude`, `network_longitude`, `location`, `last_updated`.
State: `lock_status`, `vehicle_status`, `vehicle_mode`, `sleep_state`, `is_connected`,
`side_stand_status`, `trunk_status`, `ota_status`, `odo_details`, `active_uda_uuid`, `status`.

## Login / token source (layered — the only hard part)
- OTP (soteria): `POST /thor/soteria/otp/generate` (`GenerateOtpRequestBody`→`GenerateOtpResponseData`),
  `POST /thor/soteria/otp/verify` (`VerifyOtpRequestBody`).
- olacabs SSO: `com.olacabs.login.*` ("yodac"), host `apps2.olacabs.com`, `/v3/user/login`.
- Token exchange: `POST thor-stc/v2/consumer/2w/auth` (`UserAuthToken`→`AuthenticateConsumerSerializedData`),
  `GET /v1/oauth2/channel/token` (`@Header Authorization`→`JwtTokenResponseData`).
- Result stored in token repo → surfaced as `X-ACCESS-TOKEN` (kong2) and `Bearer` (maps).
- No Play Integrity / SafetyNet / attestation found → a client with valid `X-ACCESS-TOKEN` works.

## What OUR Cloud tab needs
**Auth material:** the user's `X-ACCESS-TOKEN` + `X-UDA-UUID`. With those two values, a single
`GET kong2/api/config/v2/poll/vehicle/statistics` (plus the fixed device headers above) returns
remote battery % + GPS — no vehicle contact, works when the scooter is asleep.

**Getting the token — two strategies:**
1. **Import once:** grab `X-ACCESS-TOKEN` + `X-UDA-UUID` from the logged-in session once; store in our
   app; rely on refresh. Minimal build.
2. **Full in-app OTP login:** reimplement soteria OTP (+olacabs SSO + token exchange) inside our app.
   Clean UX, but heavy and brittle to Ola-side changes.
