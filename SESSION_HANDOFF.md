# Ola S1 — Session Handoff / Continuation Map

Paste this into a new session to continue. Everything below reflects work done so far.

## Goal
Extend the user's own Android app (`dev.olatool`, repo `D:\claude_work\ola-s1-rea`) so the
owner can see **their own** scooter's **battery + location remotely** from Ola's cloud, plus a
multi-unit fleet view. **Read-only telemetry.** Owner's own scooter, VIN `P53AUDCA0DHA04149`
(last seen ~Lucknow, 26.8601, 80.9018).

## Done this session

### A. App changes (repo `D:\claude_work\ola-s1-rea\app`, package `dev.olatool`, Java + Gradle)
- **Cloud tab (NEW):**
  - `app/app/src/main/java/dev/olatool/OlaCloud.java` — HTTPS client to `kong2.olaelectric.com`,
    `GET api/config/v2/poll/vehicle/statistics`, parses battery/charging/location. No new deps
    (`HttpsURLConnection` + `org.json`).
  - `app/app/src/main/java/dev/olatool/CloudTab.java` — UI: Battery %, Range, Charging, Lock,
    Vehicle state, Location (lat/lon/acc) + "Open in Maps", **"Set token"** dialog, **"App PIN"** button.
  - Wired as `tabs[12] = new CloudTab(this)` ("Cloud") in `MainActivity.java`.
- **App PIN lock (NEW):**
  - `app/app/src/main/java/dev/olatool/Lock.java` — salted SHA-256 PIN, constant-time verify.
  - `app/app/src/main/java/dev/olatool/PinView.java` — full-screen numeric unlock gate.
  - `MainActivity.java` — gates on cold start + re-locks when app returns from background.
- **Earlier (already in repo):** `Scooter.java`, `Fleet.java`, `FleetTab.java` (fleet dashboard:
  manual add + /24 discovery), skull `res/drawable/ic_launcher.xml`, `app/local.properties`.
- **Build:** JDK 21, gradle wrapper in `app/`. `cd app && ./gradlew :app:assembleDebug` →
  `app/app/build/outputs/apk/debug/app-debug.apk`. **Builds clean.** Installed + launched on the
  phone (OnePlus, `192.168.0.244` over wireless adb).

### B. Ola cloud API (reverse-engineered from official APK with jadx)
- **Full map:** [OLA STOCK APK/OLA_CLOUD_API.md](OLA%20STOCK%20APK/OLA_CLOUD_API.md). Key points:
  - Gateway `https://kong2.olaelectric.com/`. Interface decompiled = `KongService`.
  - **Telemetry (read-only):** `GET api/config/v2/poll/vehicle/statistics` → `ScooterStatusResponseData`
    (`battery_soc`, `charging_status`, `latitude`, `longitude`, `lock_status`, `range`, `last_updated`…).
    Also `thor/v1/vehicle/info`, `GET /v1/vehicles/location?uda_uuid=`.
  - **Auth = headers** added by the Kong interceptor: **`X-ACCESS-TOKEN`** + **`X-UDA-UUID`** +
    `X-APP-VERSION: v5.4.1` + `locale: en` + `country_code: IN` + `X-Device-ID`/`X-Request-ID`.
    (Maps service uses `Authorization: Bearer` + `X-Client-Id: acfd432e-ffe1-4875-8732-18b71639ccfc`.)
  - **Login (NOT reimplemented):** olacabs SSO on `apps2.olacabs.com`
    (`v4/user/initiate_login_signup` → `verify_login_signup_otp` → `confirm_login`) + EV token
    exchange (`thor-stc/v2/consumer/2w/auth`). Heavy/fragile. The EV `/thor/soteria/otp/*` is only
    for already-logged-in users (no phone field) — cannot do first login.
  - No Play Integrity / attestation on the API → a client with a valid `X-ACCESS-TOKEN` works.

### C. Scooter head unit (context)
- `192.168.0.238`, root gRPC daemon on TCP **50005** (no auth), via `scripts/ola.py`
  (`info`/`shell`/`screenshot`/`push`/`pull`/`wake`). Qualcomm msm8953, Android 10, SELinux permissive.
- `persist.ola.security.scootermode = mfg` (full access; `adb_enabled=1`). Modes: mfg/serv/customer;
  customer blocks IPv4:50005 → the app uses IPv6 (`Net.java` derives scooter IPv6 via BSSID/ARP EUI-64,
  IPv4 fallback). Drivetrain ECUs were **asleep** (CAN only ~1 frame/sec), so no live speed/battery on CAN.

## Current state
- App on phone has the Cloud tab + PIN lock. **Cloud tab shows nothing until the token is set.**

## Next step (where to resume)
1. **Get the token once** (user-performed): HTTP Toolkit on PC → intercept phone over ADB → open the
   official Ola app → copy `X-ACCESS-TOKEN` + `X-UDA-UUID` from a `kong2.olaelectric.com` request.
   Caveat: if the Ola app pins certificates, a stock (non-rooted) phone can't decrypt it → wall.
2. In the app: **Cloud → Set token** (paste both) → **Refresh**. Set an **App PIN** too.
3. Future: handle token expiry/refresh.

## Dual-rider note
The app's Cloud tab only does read-only cloud GETs, so it sidesteps the official app's single-session
limit — both riders can run this app (same token, each their own PIN).

## Boundaries held (keep these in the next session)
- **No** extracting tokens/credentials from device files or logs (blocked as credential exploration).
- **No** removing the scooter's speed limit / drivetrain or CAN injection / remote wake-unlock /
  persistent implants / tunnels — not directly and not as an in-app feature.
- **No** reimplementing auth/app-security bypasses; **no** "unchained"/jailbreak repos.
- Read-only telemetry + user-performed token capture only.

## Handy facts
- Scooter `192.168.0.238` (gRPC 50005). Phone `192.168.0.244` (wireless adb; current port via
  `adb mdns services`). adb at `D:\claude_work\android-sdk\platform-tools\adb.exe`.
- Official APK: `OLA STOCK APK/OLA APK.apk`. jadx decompile was to a temp scratch dir (gone in a new
  session) — re-run jadx on the APK if you need the sources again; the API map doc persists.
