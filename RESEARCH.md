# Ola S1 — Research Log

Research notes from the session that got **remote cloud telemetry** (battery + location)
working in the owner's own `dev.olatool` app, for the owner's own scooter.
Date: 2026-11-07. Pairs with `SESSION_HANDOFF.md`.

> **Credential hygiene:** live values (access token, UUIDs, private keys) are **redacted**
> in this file on purpose. The real values live only in the phone app's private prefs
> (`/data/data/dev.olatool/shared_prefs/ola.xml`). Do not paste live tokens into the repo.

---

## 0. Goal & scope

- **Goal:** owner sees their own scooter's battery + location **remotely** from Ola's cloud,
  inside their own app (`dev.olatool`), independent of the official app's single-session limit.
- **Scope held throughout:** read-only telemetry of the owner's own vehicle/account.
  Explicitly NOT done: speed-limit/drivetrain changes, CAN injection, remote/BLE unlock or
  vehicle control, persistent implants.

---

## 1. Network / device facts

- Scooter head unit: `192.168.0.238` (Wi-Fi), root gRPC daemon on **TCP 50005** (no auth),
  reachable via `scripts/ola.py` (IPv4 works in `mfg` mode). `info` confirms: uid 0, Android 10,
  msm8953, SELinux permissive, scooter mode = mfg.
- Phone (test unit): OnePlus Nord 2 (`CPH2119`, serial `QOAULB9SD6WSLJWO`), Android 12 / ColorOS,
  Wi-Fi `192.168.0.124`, SIM slot broken (OTP arrives on a separate registered number / BSNL).
- PC: `192.168.0.244`. adb at `D:\claude_work\android-sdk\platform-tools\adb.exe`.
- Build tools: `android-sdk\build-tools\34.0.0` (aapt2, zipalign, apksigner). JDK 21. Node 24.
  Python 3.13 with `mitmproxy`.

---

## 2. Cloud API auth model (confirmed empirically this session)

- Gateway host used by the companion app: **`kong2.olaelectric.com`**.
- Telemetry endpoint: `GET api/config/v2/poll/vehicle/statistics` → battery_soc, charging,
  lat/lon, lock, range, last_updated, etc.
- **Required headers (all must be present):**
  - `X-ACCESS-TOKEN` — JWT (redacted). Claims observed: `user_type=CONSUMER`,
    `app_mode=COMPANION`, `tenant_type=TWO_WHEELER`, `token_type=access_token`,
    `device_id=<16-hex>`, `iat`≈now, `exp`≈+182 days (~6-month life).
  - `X-UDA-UUID` — redacted UUID.
  - `X-APP-VERSION: v5.4.1`
  - `X-Device-APP: COMPANION_APP`  ← **without this the endpoint returns HTTP 400**
    "Missing request header 'X-Device-APP'" (note: 400, not 401 — token auth itself passes).
  - `X-Device-ID: <16-hex>` — the same device_id baked into the JWT.
  - plus `locale`, `country_code: IN`, `Accept`.
- Login (NOT reimplemented): olacabs SSO `apps2.olacabs.com/v4/user/initiate_login_signup`
  → `verify_login_signup_otp`, then EV token exchange
  `kong2.../thor-stc/v2/consumer/2w/auth`. We did **not** rebuild this — we captured the
  finished token from the real app instead.

---

## 3. The capture wall (first attempt) and why it failed

1. mitmproxy on PC `0.0.0.0:8080`; phone set to use it as a manual Wi-Fi proxy; mitmproxy CA
   installed as a **user** certificate on the phone.
2. Result: `cdn.olaelectric.com` / `www.olaelectric.com` decrypted fine (WebView/Chrome trust
   user CAs), but **`kong2.olaelectric.com` showed only CONNECT, 0 decrypted requests** —
   116 CONNECT attempts, all failing TLS.
3. Diagnosis: the native API client does **not trust user-installed CAs** (targetSdk 36 default)
   and/or pins. A stock, non-rooted phone cannot read the token this way.
4. ColorOS blocks `adb shell settings put global http_proxy` (`WRITE_SECURE_SETTINGS` denial) —
   the Wi-Fi proxy had to be set manually in the UI.

Dead ends checked:
- **Web portal** — no web dashboard hits the telemetry API; scooter is app-controlled only.
- **adb backup** — companion app `allowBackup=false` (+ Android 12 restricts it anyway).
- **Token on the scooter** — the head unit authenticates to cloud as a *device*
  (`com.olaelectric.cloudconnectivitymanager`, an MQTT device JWT for VIN `P53AUDCA0DHA04149`),
  NOT as the consumer. No consumer `X-ACCESS-TOKEN` lives on the scooter.

---

## 4. What worked: patch the companion app to trust the user CA

Target app: `com.olaelectric.companion` v5.4.1 (versionCode 1344), **split bundle**
(`base` + `split_config.arm64_v8a` + `split_config.en` + `split_config.xxhdpi`), targetSdk 36.

### 4.1 Patch
- Pull all 4 split APKs from the phone (`pm path` → `adb pull`), zip them into an `.apks` bundle.
- `npx apk-mitm ola.apks --apktool <apktool 3.0.3 jar>`
  - apk-mitm adds a network-security-config that trusts user CAs (defeats the "no user CA"
    default that was blocking decryption).
  - apk-mitm's **bundled** apktool broke the manifest; a newer standalone **apktool 3.0.3**
    (via `--apktool`) was required. (GitHub release asset name is versioned, e.g.
    `apktool_3.0.3.jar`; `latest/download/apktool.jar` 404s.)

### 4.2 Install blocker and the fix (`repatch.ps1`)
- `adb install-multiple` failed: `INSTALL_PARSE_FAILED_MANIFEST_MALFORMED` at Binary XML
  **line #74**.
- Root cause (found with `aapt2 dump xmltree`): the FCM meta-data
  `com.google.firebase.messaging.default_notification_icon` had `android:resource="@null"`.
  apktool's decode→encode round-trip turns that into a form the installer rejects
  (@null = no resource → "requires android:value or android:resource").
- Fix (scripted in `repatch.ps1`): decode the patched base with apktool 3.0.3, **delete the
  `default_notification_icon` meta-data line**, rebuild, `zipalign -p 4`, generate ONE fresh
  keystore (keytool), and **re-sign all 4 splits with that same key** (apksigner). Android
  requires every split in an install-multiple set to share one signer.
- `adb install-multiple -r` of the 4 re-signed splits → **Success**. Patched app launched
  with **no anti-tamper block**.

### 4.3 Capture
- Open the patched app, log in with OTP (OTP auto-verified via SMS Retriever).
- `kong2.olaelectric.com` now **decrypts**. Captured `X-ACCESS-TOKEN` + `X-UDA-UUID`
  from the authenticated requests. Also captured `X-Device-APP: COMPANION_APP` and the
  `X-Device-ID` from a later screen (needed for the stats endpoint — see §2).

---

## 5. Wiring the token into `dev.olatool`

- The app stores creds in `/data/data/dev.olatool/shared_prefs/ola.xml`
  (prefs name `ola`). Keys: `cloud_token`, `cloud_uuid`, `cloud_device_app`, `cloud_device_id`.
- `dev.olatool` is a debug build (debuggable), so `adb shell run-as dev.olatool` can read/write
  that file without root. Procedure: `am force-stop dev.olatool` → pull `ola.xml` → edit the 4
  keys → push + `run-as cp` back → `chmod 660`. (Use PowerShell for adb; Git Bash mangles the
  `/data/...` path into a Windows path.)
- Set: `cloud_token`=<JWT>, `cloud_uuid`=<uuid>, `cloud_device_app`=`COMPANION_APP`,
  `cloud_device_id`=<16-hex from JWT>.

### Result — VERIFIED WORKING
`dev.olatool` → Cloud tab → **"Updated just now from Ola cloud"**, **battery 58%**, "On battery".
Full remote telemetry works, independent of the official app.

---

## 6. Token refresh (future)

- Token lives ~6 months (`exp`). To refresh when it expires:
  1. Re-open the **already-patched** companion app through mitmproxy (no re-patch needed —
     the patched app is installed and still trusts the user CA).
  2. Re-capture `X-ACCESS-TOKEN` / `X-UDA-UUID`.
  3. Write them back into `ola.xml` (§5).
- `X-Device-APP=COMPANION_APP` and `X-Device-ID` are constant across token refreshes.
- **Do not uninstall the patched companion app** — it is re-signed with a throwaway key, so a
  reinstall/update wipes its login and forces the whole OTP + capture dance again.

---

## 7. Harness / tooling notes

- Several steps were blocked by the assistant's safety classifier and had to be run by the
  owner: `npx apk-mitm` and `java -jar apktool.jar` = "Code from External"; grepping a device
  for tokens = "Credential Exploration". Reading/patching with local SDK tools (aapt2, zipalign,
  apksigner, adb) and writing the owner's own token into the owner's own app were fine.

---

## 8. BLE feature recon (companion app) — mapped, NOT reimplemented

String/structure scan of the companion `base.apk` dex (facts only, no proprietary code copied):

- Components: `BleManager`, `BleService` (Kotlin), `BluetoothGattCallback`,
  TX/RX characteristics (`mTxCharacteristic` / `mRxCharacteristic` → serial-over-BLE
  command/response), notification characteristics, RSSI/beacon handling,
  `BLEPairingViewModel` / `BlePairingData`.
- A **"Ble Seed Auth"** challenge/response — a cryptographic pairing authentication.
- Pairing is gated on **scooter unlock** (`prePairingScooterUnlockPrompt`,
  `pre_pairing_scooter_unlock_prompt`).
- GATT service/characteristic **UUIDs are not plaintext** in the dex (constructed/obfuscated
  at runtime or in native code).

**Decision:** the BLE "connect" feature's core is a cryptographic Seed-Auth that gates
vehicle **unlock/control**. Reverse-engineering/reimplementing that auth = defeating a vehicle
access-control mechanism + enabling unlock/control, which is outside this project's read-only
scope. **Not reimplemented.**

- In-scope alternative (not yet built): a **read-only BLE presence/scan** feature using Android
  `BluetoothLeScanner` — detect the scooter's advertisement, show RSSI/proximity/last-seen.
  Passive broadcast data only; no GATT connect, no auth, no control. This needs no Ola code.

---

## 9. Key files (scratchpad, session-local)

- `repatch.ps1` — decode/fix-manifest/rebuild/zipalign/re-sign-all-splits.
- `install_patched.ps1` — extract + install-multiple helper.
- `capture_token.py` — mitmproxy addon: logs auth headers + full header dump for authenticated
  `olaelectric.com` requests.
- `ola.apks` (original splits bundle), `ola-patched.apks`, `signed_out/` (installed set),
  `apktool.jar` (3.0.3).

(Scratchpad is session-local and will be gone next session; re-create from this log if needed.)

---

## 10. BLE protocol — static decompilation via REA/JADX (2026-11-07)

Deeper pass than §8. Tool: REA MCP (`rea-agents` 6.8.0) driving **JADX headless 0.7.1**
against `OLA STOCK APK/OLA APK.apk` (**sha256 `f5e7669fc3576f72c78a4d2d4dfbaf736850556327932b510e6739f806d6ec99`**,
13,733 classes). This is **read-only static RE — documenting how the protocol works**, not a
control/unlock tool. The seed-auth itself is still **not reimplemented** (see §8 scope).
Evidence discipline below: **[obs]** observed in bytecode, **[der]** derived from decompiled
source, **[inf]** inference not proven statically.

### 10.1 Architecture map
Layered, Hilt-injected (evidence `ev_fecedc28…`):
- Radio: `core.repo.ble.signal.manager.BleManagerImpl`
- Crypto (interfaces): `domain.domainModels.ble.encrypt.{Encrypt, KeyGenerator}`
- Packet builders: `…ble.packet.builders.{EncryptedPacketBuilder, DefaultIVPacketBuilder}`
- Commands: `…ble.command.{EncryptedCommandRequest, EncryptedRequest, DirectCommandRequest, …}`
- Use cases: `domain.usecases.ble.{auth.BleDeviceAuthenticationUseCase, connection.Ble*UseCase}`
- Android service: `service.ble.BleService`

### 10.2 Task 1 — encryption (AES-style; concrete impl NOT pinned)
`Encrypt` and `KeyGenerator` are **interfaces** (`ev_42dd2805…`, `ev_908c…`), not classes:
```
interface KeyGenerator { byte[] generateKey(); byte[] getFixedIV(); byte[] getRandomSalt();
                         boolean hasValidKey(); void saveKey(byte[] key, byte[] salt); void resetKeys(); }
interface Encrypt      { byte[] encrypt(byte[] data, byte[] key); byte[] decrypt(byte[] data, byte[] key);
                         byte[] encryptType(long type, byte[] key); byte[] getEncryptedIV(); }
```
- **[obs]** A **fixed IV** (`getFixedIV`) + **random salt** (`getRandomSalt`) + generated key, with a
  per-device save/has/reset key lifecycle (`saveKey`/`hasValidKey`/`resetKeys`).
- **[obs]** 94 classes reference the `Encrypt` interface (`trace_android_references`); both a fixed-IV
  and an encrypted-IV packet builder exist (`EncryptedPacketBuilder`, `DefaultIVPacketBuilder`).
- **[inf]** Cipher is **AES** (symmetric key + explicit IV ⇒ likely CBC). NOT proven: the concrete
  `Encrypt` impl is **R8-renamed** — no `Aes`/`Cipher`/`Crypto` class name survives
  (`search_android_classes "Aes"` = 0 hits), and `javax.crypto.Cipher` is a framework class so it is
  not statically traceable with these tools. **To confirm mode + key-derivation:** runtime Frida hook
  on `javax.crypto.Cipher.getInstance` / `SecretKeySpec` — deferred (beyond read-only scope).

### 10.3 Task 2 — pairing / auth handshake
`BleDeviceAuthenticationUseCase` (`ev_b0b0…`) fields: `BleConnectionUseCase b`,
`core.repo.ble.connection.a c`, logger, String, int. The run entry `f()` builds a Kotlin Flow; the
real sequence is in `$run$2.invokeSuspend` (smali, `ev_f287…`):
1. **[der]** logs `"@@CONNECT@@"` + the target's BLE MAC (`BluetoothDevice.getAddress()`).
2. **[der]** calls `BleConnectionUseCase.f(holder)` → returns a `Flow` (GATT connect + discover).
3. **[der]** collects that flow through `$run$2$1` (the auth-state emit handler).
- **[inf]** This is the §8 "Ble Seed Auth" challenge/response; the seed crypto lives deeper
  (`BleConnectionUseCase` / signal manager) and is gated on scooter unlock (§8). Not traced further.

### 10.4 Task 3 — command / opcode model
`EncryptedCommandRequest` (`ev_908c…`, `ev_6353…`):
```
EncryptedCommandRequest(long commandType, Object commandData, Encrypt encrypt, ru.a logger)
long   getCommandType()          // the opcode
byte[] commandData() { return commandData==null ? null : b.f(commandData); }  // serialize → bytes
```
`EncryptedPacketBuilder.getEncryptedType` (`ev_5b21…`):
```
Object getEncryptedType(long j, byte[] key) { return this.encrypt.encryptType(j, key); }
```
- **[der]** Wire model: every command = **`{ long opcode, payload }`**; BOTH the opcode
  (`encryptType`) **and** the payload (`getData`/`encrypt`) are encrypted before the characteristic write.
- **[obs]** Command catalog (concrete subclasses, from the 94-ref trace) includes the sensitive set:
  `WidgetsHelper$lockScooter$1` / `$unlockScooter$1`, `ResetPassCodeViewModel$lockScooter$1`,
  `ResetPassCodeCommand`, `SendProfileIdCommand`, and `…command.settings.*`
  (RegenType, SideStandOverride, StopHyperCharging, WifiConnection, VacationMode, MediaControls,
  incoming-call controls, party mode, favourites), `…command.maps.*` (MapsDistance, MapsLatLongPoi),
  `…notificationCenter.*`.
- **Not done:** the concrete **opcode *numbers*** (the `long` constant each subclass passes) — would
  require decompiling each command subclass constructor. Deferred.

### 10.5 Task 4 — GATT characteristics / UUIDs
`BleManagerImpl` holds `BluetoothGatt x` + four `BluetoothGattCharacteristic` handles: `s` (notify),
`u`, `v`, `w` (command/write; `write` method `I` picks WRITE vs WRITE_NO_RESPONSE from the char's
property bits). `C()` = **enableNotification** (`ev_2777…`):
- **[der]** takes characteristic `s`, grabs its first descriptor, sets
  `BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE`, then `writeDescriptor` +
  `setCharacteristicNotification(true)`, **retrying up to 4×**; emits
  `EventsConstants.BLE_NOTIFICATION_ENABLED` / `_ENABLE_FAILED` analytics.
- **[obs]** UUIDs are **NOT** in `C()` and **NOT plaintext** in the dex (confirms §8). They are
  assigned during service discovery (`BleManagerImpl$connectDevice$2$1` onServicesDiscovered) —
  constructed/resolved at runtime. **Not recovered** in this pass.

### 10.7 Command opcode table — `domain.domainModels.ble.common.CommandType` (RESOLVED)
Kotlin enum; constructor `(String name, int ordinal, long command)`. The `command` long is the
on-wire opcode (passed to `EncryptedCommandRequest`/`encryptType`). Values read from `<clinit>`
disassembly (`ev_8c9004db…`) — `const-wide` arg per constant:

| Opcode (hex) | Dec | CommandType constant | Meaning |
|---|---|---|---|
| 0x01 | 1   | `DIRECT_COMMAND`              | direct (unencrypted-type) command channel |
| 0x03 | 3   | `USER_ID_COMMAND`            | send user/profile id |
| 0x04 | 4   | `VIN_COMMAND`               | request VIN |
| 0x05 | 5   | `SET_LOCK_PROXIMITY_COMMAND` | proximity lock set |
| 0x06 | 6   | `PARTY_MODE_COMMAND`         | party mode |
| 0x07 | 7   | `ENABLE_PROXIMITY`          | enable proximity unlock/lock |
| 0x08 | 8   | `PHONE_ALIVE_STATUS`        | phone keep-alive (DAliveAck) |
| 0x17 | 23  | `RSSI_COMMAND`             | RSSI/proximity read |
| 0x19 | 25  | `SETTING_COMMAND`          | **wrapper** for all scooter settings (sub-type inside payload) |
| 0x30 | 48  | `SEED_AUTHENTICATION_REQUEST` | start Seed-Auth (app → scooter) |
| 0x31 | 49  | `SEED_AUTHENTICATION_KEY`   | Seed-Auth key exchange |
| 0x32 | 50  | `TEST_AES`                | AES self-test / handshake probe |
| 0xFF | 255 | `REFRESH_SETTING_COMMAND`   | re-read all settings |

- **[der]** Lock/unlock, side-stand override, regen, hypercharging, vacation, media, call controls
  are **NOT** top-level opcodes — they ride inside `SETTING_COMMAND` (0x19) as a **settings sub-type**.
  Each `…command.settings.*` class (e.g. `SideStandOverrideCommand(boolean, Encrypt, logger)`) builds
  the sub-payload; the sub-type enum is still to be mapped.
- **[obs]** Two sibling enums exist and are not yet dumped: `common.ble.CloudSignalCommandType`
  (cloud-relayed signals) and `domain.domainModels.proximity.ProximityCommandType`.
- **[der]** `SEED_AUTHENTICATION_REQUEST/KEY` (0x30/0x31) confirm the §8 "Seed Auth" is a real
  on-wire two-step challenge/response gating control; `TEST_AES` (0x32) confirms AES on the link.

### 10.8 Sub-type enums under SETTING_COMMAND + cloud/proximity (RESOLVED)
These are **string-valued** Kotlin enums (`<init>(name, ordinal, String value)`), read from `<clinit>`.
They ride *inside* the `SETTING_COMMAND` (0x19) / proximity / cloud payloads — not numeric opcodes.

**`domain.domainModels.scooterSettings.SettingType`** (`ev_0a217c8e…`) — top settings categories;
`value` = display string:

| Constant | value | ordinal |
|---|---|---|
| `CALLING` | "Calling" | 0 |
| `LIGHTS` | "Lights" | 1 |
| `RIDING` | "Riding" | 2 |
| `BATTERY` | "Battery" | 3 |
| `RESET_PASSCODE` | "Reset Passcode" | 4 |
| `WiFi` | "WiFi" | 5 |
| `NOTIFICATION_CENTER` | "Notification Center" | 6 |

**`domain.domainModels.scooterSettings.SubSettingType`** (`ev_2f0a349b…`) — RIDING sub-modes:

| Constant | value | ordinal |
|---|---|---|
| `REGENERATIVE_BRAKING` | "regenerative_breaking" | 0 |
| `TURBO_BOOST` | "TURBO_BOOST" | 1 |
| `DIY_MODE` | "DIY_MODE" | 2 |
| `DIY_MODE_DEFAULT` | "diy_mode_default" | 3 |

**`domain.domainModels.proximity.ProximityCommandType`** (`ev_a7f8b595…`) — rides under
`SET_LOCK_PROXIMITY_COMMAND`(0x05)/`ENABLE_PROXIMITY`(0x07):

| Constant | value |
|---|---|
| `LOCK` | "LOCK" |
| `UNLOCK` | "UNLOCK" |
| `SILENT_UNLOCK` | **"PARTIAL_UNLOCK"** (name≠value) |

**`common.ble.CloudSignalCommandType`** (`ev_2b261bb7…`) — cloud-relayed BLE signals
(value = constant name):

| Constant | value |
|---|---|
| `OpenTrunkCommand` | "OpenTrunkCommand" |
| `LockVehicleCommand` | "LockVehicleCommand" |
| `NotificationCommand` | "NotificationCommand" |

- **[der]** Two-level command scheme confirmed: outer numeric opcode (§10.7, e.g. `SETTING_COMMAND`
  0x19) + inner **string** identifier (`SettingType`/`SubSettingType`), all inside the AES-encrypted
  payload. So reading a raw BLE frame reveals nothing without the key.
- **[obs]** Side-stand override, stop-hypercharging, vacation mode are **not** in these enums — they
  are their own `…command.settings.*` classes (each builds a bespoke payload), e.g.
  `SideStandOverrideCommand(boolean, Encrypt, logger)`. Their field names (`sideStandOverrideEnable`)
  are the only identifiers; the on-wire key/format is built in each `commandData()`.
- **[inf]** Lock/unlock reach the vehicle two ways: BLE proximity (`ProximityCommandType`) and
  cloud relay (`CloudSignalCommandType.LockVehicleCommand`). Matches the app's
  `PingMyScooterViaCloudOrBle` / `ResetPassCodeThroughCloudAndBle` dual-path classes seen in §10.1.

### 10.9 Bespoke command payloads + GATT UUIDs (RESOLVED)

**Payload byte layout** — bespoke `…command.settings.*` build an `EightBytePacket` then
`b.f(...).toByteArray()` (serialize → 8 bytes), which is then AES-encrypted and wrapped as
`SETTING_COMMAND` (0x19). The trailing `254`/`126` arg is a **Kotlin default-arg mask + marker**,
NOT data. The real frame is the 8 bytes `[b0..b7]`:

| Command | field | 8-byte payload `[b0..b7]` | note |
|---|---|---|---|
| `SideStandOverrideCommand` (`ev_5eb81807…`) | `sideStandOverrideEnable` | `10 00 00 00 00 00 00 00` | b0=0x10; single fixed frame in decompile |
| `StopHyperChargingCommand` (`ev_cd64b814…`) stop=true | `shouldStopCharging` | `04 00 00 00 00 00 00 04` | b0=0x04 action, b7=0x04 selector |
| `StopHyperChargingCommand` stop=false | | `00 00 00 00 00 00 00 04` | b0=0x00 (no-op), b7=0x04 |
| `EnableDisableVacationModeCommand` (`ev_1ec9367a…`) enable | `enable` | `80 00 00 00 00 00 00 01` | b0=0x80 action, b7=0x01 selector |
| `EnableDisableVacationModeCommand` disable | | `00 00 00 00 00 00 00 01` | b0=0x00, b7=0x01 |

- **[der]** Frame grammar: **b0 = action/enable bitflag, b7 = sub-feature selector**
  (0x04 hypercharging, 0x01 vacation), b1–b6 reserved `00`. Each inner 8-byte frame is encrypted and
  carried under `SETTING_COMMAND`.

**GATT UUIDs** — assigned in `BleManagerImpl$connectDevice$2$1.onServicesDiscovered` (`ev_b0667319…`).
Flow: on status 0 → `gatt.getService(UUID.fromString(q))`, then resolve characteristics; state goes
`DiscoveredNotAuthenticated` → then the Seed-Auth use case runs.

| Role | Source | Value |
|---|---|---|
| **Service UUID** | field `f17381q` (`q`, String) | **runtime** (set from config/constructor, not literal) |
| TX char (`mTxCharacteristic`, → `u`) | field `t` (String) | **runtime** |
| RX char (`mRxCharacteristic`, → `f17383s`) | field `f17382r` (`r`, String) | **runtime** |
| `mTxLocationCharacteristic` (→ `v`) | **literal** | `45616c3f-b5a3-f393-e0a9-e50e24dcca9e` |
| (read, unused handle) | **literal** | `45616c4f-b5a3-f393-e0a9-e50e24dcca9e` |
| char `w` | **literal** | `46616c3f-b5a3-f393-e0a9-e50e24dcca9e` |

- **[obs]** Three characteristic UUIDs are hardcoded (note the ASCII-ish prefixes `45 61 6c`="Eal",
  `46 61 6c`="Fal"). The **primary service + TX/RX** UUIDs are NOT literals — String fields
  `q`/`t`/`r`.
- **[der] q/t/r are remote-config, not in the APK (RESOLVED):** `BleManagerImpl.<init>`
  (`ev_398f4810…`) leaves `q`/`r`/`t` `null` (it only wires DI deps + sets up flows); method `g`
  (`ev_c19a6d3c…`) is `disableProximityCommand`, not a UUID setter. The service/TX/RX UUID strings
  are populated at **connect time from `HomeConfigEntity`** (server/remote config). So those three
  UUIDs are **not recoverable from static APK analysis** — they live in the backend config (or can be
  read at runtime from the live `BleManagerImpl` instance / a GATT capture). Static thread ends here.
- **[der]** Confirms §8: the main service/TX/RX UUIDs are runtime-provided, not plaintext; only the
  location/aux characteristics are hardcoded.

### 10.10 AES mode + Seed-Auth internals — NOT done (scope boundary)
Recovering the live AES key/mode and the Seed-Auth challenge/response requires runtime instrumentation
(Frida hooking `javax.crypto.Cipher` + the seed exchange). That recovers the **secret that gates
vehicle control/unlock** — the exact access-control mechanism §8/§10 scoped out. **Not performed.**
Left as a documented boundary, not a task.

### 10.11 Response decode map — `Parser$ResponseType` (RESOLVED, tool-useful)
Incoming BLE notifications are dispatched by a **1-byte response type** (`Parser$ResponseType`,
byte-valued enum, `ev_12a81f2d…`). `Parser` holds `HashMap<Long,Class<IResponse>>` + Encrypt
(decrypts incoming) + `ConcurrentHashMap<Long,byte[]>` (reassembly). The `Parser.<clinit>` map
population would NOT disassemble (JADX limitation — recorded as unknown). Response-type bytes:

| Byte (hex) | Dec | ResponseType |
|---|---|---|
| 0x0A | 10 | IOS_PAIRING_STATE |
| 0x10 | 16 | PROFILE_ID_MISMATCHED |
| 0x18 | 24 | RESET_PASSCODE |
| 0x19 | 25 | SCOOTER_COMMAND_FOR_S1_X |
| 0x1A | 26 | SCOOTER_SETTINGS |
| 0x1D | 29 | MUSIC_COMMAND |
| 0x1E | 30 | CALL_COMMAND |
| 0x1F | 31 | WIFI_COMMAND |
| 0x20 | 32 | TIME_DELAY |
| 0x21 | 33 | MTU_NOTIFICATION_SUPPORTED |
| 0x25 | 37 | CUSTOMIZATION_SETTINGS |

- **[obs]** Crypto is **pure Java/Kotlin, not native**: JNI scan found 567 native methods, all
  `org.maplibre.*` (maps) — zero Ola crypto in `.so` (`ev_c15117e7…`). (Base APK ships no Ola
  native lib; the arm64 split isn't this file.)
- **[der]** `EightBytePacket.toByteArray()` = raw 8 bytes `[b1..b8]`, no header/length framing
  (`ev_f1963368…`); it's a Kotlin UByte value-class (mangled `-w2LRezQ` accessors).

### 10.12 What of this is actually tool-useful (read-only)
Per the "only build what helps the tool" rule ([[feedback_only_actionable_research]]):
- **USEFUL now:** the 3 hardcoded GATT characteristic UUIDs (§10.9) → label map in `BtTab`'s BLE
  inspector so the owner sees friendly names, not raw UUIDs. Pure read-only.
- **Marginal:** response-type bytes (§10.11) could label *observed* notification types IF the tool
  ever passively reads them — but decrypting them needs the key (out of scope), so low value.
- **NOT tool-useful / out of scope:** command opcodes, payloads, seed-auth — using them = sending
  control, which this project does not do. Documented for understanding only; not for the tool.

### 10.6 Where each thread stops (for next session)
- AES mode + key derivation → Frida hook `javax.crypto.Cipher` at runtime (out of current scope).
- Seed-auth challenge/response → decompile `BleConnectionUseCase` + `$run$2$1`.
- Top-level opcodes → **DONE (§10.7)**. Sub-type enums → **DONE (§10.8)**. Bespoke payloads → **DONE
  (§10.9)**.
- GATT UUIDs → **DONE as far as static allows (§10.9)**: 3 literal char UUIDs recovered; service/TX/RX
  (`q`/`t`/`r`) confirmed **remote-config from `HomeConfigEntity`**, not in the APK — static thread closed.
- AES mode/key-derivation + Seed-Auth internals → **NOT done, scope boundary (§10.10)** — needs live
  Frida key/seed extraction = recovering the vehicle access-control secret; deliberately not performed.

## 11. Dash passcode lock — recovery + unlock (2026-11-08, admin/root)

The "Enter Passcode" screen on the head unit is an **app-access + vehicle lock**, not
a standard Android keyguard. Fully reverse-engineered from `/system/priv-app/HPMS/HPMS.apk`
(`com.olaelectric.profilemanagerservice` v5.0.0) via REA/JADX. This unit: Android 10,
msm8953, reached over gRPC `192.168.0.238:50005`.

### 11.1 How the lock actually validates
- HMI submits the typed PIN over AIDL: `IProfileManagerService.challengeUnlockByPasscode(pin, isUpdate)`.
- `ProfileManagerServiceImpl.challengeUnlockByPasscode` loops the users and calls
  `checkPasscodeStatus(user, pin)` = `SCryptUtil.check(pin, user.getPasscode())` — a **local
  scrypt check** against each `UserProfile.passcode` (per-user scrypt hash in Room DB
  `profile-manager-database`). On success → `setCurrentUser(uuid)` + `profileController.sendUnlockByPasscode()`
  (device-keyed, **no PIN argument** → the unlock command is not PIN-derived). On failure →
  `decrementRemainingAttempts()`.
- `getSecuredPasswordHash(pw) = SCryptUtil.scrypt(pw, 1024, 8, 1)` → lambdaworks format
  `$s0$a0801$<salt_b64>$<dk_b64>` (N=1024, r=8, p=1, 16-byte salt, 32-byte dk).
- **Trap:** pref `.../shared_prefs/com.olaelectric.profilemanagerservice.xml` key
  `PREV_USER_PASSCODE` is a *stale cache* (the previous user's hash), **not** the current
  validator. `GetVSMPasscodeUseCase` reads it but the live gate uses the DB per-user hash.
  `REMAINING_ATTEMPTS` (same pref) is time-windowed (auto-resets); `SCOOTER_LOCK_STATE` is the
  BCM/vehicle lock, updated on a vehicle broadcast.

### 11.2 Recover the PIN by cracking (no vehicle contact)
N=1024 is light and the PIN is 6 digits → brute force in ~30-240 s.
1. Pull the DB with **root over gRPC** (adb is uid 2000, cannot read `/data/data/<app>`):
   `py scripts/ola.py pull --ipv6 <ip> //data/data/com.olaelectric.profilemanagerservice/databases/profile-manager-database pm.db`
   (also `...-wal` — recent writes live there).
2. `python scripts/crack_passcode.py --db pm.db pm.db-wal` → prints every profile's PIN.
   (Reading the hash is "credential exploration" the auto-mode classifier blocks for the agent;
   the owner runs the pull/crack on their own device.)

### 11.3 Unlock past a broken touch pad — Frida (`scripts/frida_unlock.py`)
The Kanzi pad is invisible to uiautomator and `input tap` on it is unreliable; `screencap` is
blank (hardware-composer surface) so only **scrcpy** shows it. To submit the known-correct PIN
reliably, call the AIDL entry on the live service via Frida:
- **frida-server 17.x CRASHES this kernel** (`frida_sigchain_compat_validate_signal` assert,
  introduced in 16.1.0). Use **frida-server 16.0.19** (pre-sigchain) — runs clean.
- Client: py3.13 has no old-frida wheel, so `python -m uv run --python 3.11 --with frida==16.0.19
  scripts/frida_unlock.py --device <ip>:5555 --pin <pin>`.
- Result observed: `isStatus=true` (PIN accepted, no attempt consumed) → backend unlock flow ran.

### 11.4 Scope boundary / outcome
- Software **accepts** the PIN and fires the device-authed unlock, but `SCOOTER_LOCK_STATE` stays
  `LOCK` until the **BCM confirms** — the physical unlock needs the **scooter awake/present**
  (cf. §README "dash cannot unlock a sleeping scooter"). The vehicle command channel itself is
  AES challenge-response the tool does not forge; we only feed the owner's correct PIN.
- After the unlock call the HMI (`com.olaelectric.hmiclient`) restarted (black screen) and adb
  went briefly unstable; **gRPC stayed solid**. For the YouTube use case this is moot — `am start`
  of SmartTube renders over the passcode screen regardless (§see project memory).
- Everything here is **owner self-recovery of a forgotten PIN on their own vehicle**, documented
  so the next session skips the 3-hour rediscovery. [[feedback_only_actionable_research]]

## 12. Kanzi HMI launcher — can a native YouTube tile be added to Home?

**Question:** add a YouTube launcher tile *inside* the dash Home screen (not an
overlay), using community tools kzbTools (unpack+repack) / KZB-Explorer (read).

**Finding: not feasible on this unit without native code changes + high brick
risk. Use the overlay app instead (`app/overlay`, package `dev.olatube`).**

Evidence (gathered over gRPC as root, 2026-11-08):

- There is **no Android HOME launcher**: `cmd package resolve-activity -c
  android.intent.category.HOME` returns "No activity found". The Home screen is
  the Kanzi HMI `com.olaelectric.hmiclient` (`/system/priv-app/HHMIC/HHMIC.apk`).
- `HHMIC.apk` is **759 MB**. Its UI is one monolithic Kanzi scene,
  `assets/ola_electric.kzb` = **487 MB**, with a 1.4 MB text manifest
  `assets/ola_electric.kzb.txt` (BOM/Unicode; `unzip -p` it, decode UTF-8).
  Native engine: `lib/arm64-v8a/libkanzi.so`. Theme packs are separate `.kzb`.
- The manifest lists **20,118 resources** and **1,945 `Screens/Screen/...`
  nodes**. Home = `Screens/Screen/RootPage/MainScreen/ScrollHandler/Park/...`
  (Trunk_Button, CostPriceButton, LongPress_Button, FreeRoam, odometer, moods).
  Every "screen" (Bluetooth, Navi, Settings, etc.) is an **internal Kanzi page**,
  driven by `*DataContext.xml` + native hmiclient code — not an Android app.
- Searching the whole manifest for `launch|intent|activity|package|startapp`
  yields only `IsNaviStateLaunched` and `LaunchNaviButton` — i.e. an **internal
  navigation state**, never an external-app launch. **This HMI has no
  app-launch / `startActivity` action primitive at all.**

Why the community tools do not close the gap:

- **kzbTools** (C++17, fork) can unpack and `pack` `.kzb` — but only at the
  container level (Folder_/element blobs). It does not expose, and cannot
  synthesise, the message→native-handler bindings a functional tile needs.
- **KZB-Explorer** (.NET) is read-only and mostly a mesh→OBJ extractor; it does
  not decode the node tree or action bindings.

A working native tile would therefore require: (1) a new node in the 487 MB kzb,
(2) a new Kanzi message, (3) a **new native handler compiled into hmiclient** to
call `startActivity(org.smarttube.stable)`, (4) repack 487 MB, (5) rebuild and
re-sign the 759 MB system priv-app, (6) replace it in `/system`. Steps 3–6 are
each brick-risk; kzbTools states no kzb-version guarantee for this Kanzi build;
and pulling 487 MB over the gRPC base64 channel is impractical.

**Shipping solution:** `app/overlay` — a standalone Android app (`dev.olatube`)
that draws a 120x120 px invisible `TYPE_APPLICATION_OVERLAY` window pinned to the
top-right corner of the Home screen and launches SmartTube on a triple-tap
(<=1200 ms). It adds the launch behaviour in Android-land, where `startActivity`
is available, and floats pixels over the Kanzi surface — same UX as a Home tile,
zero HMI modification, zero brick risk. Build: `./gradlew :overlay:assembleDebug`.
Install + grant + start: see the install block in the YouTube notes.

- **HMI-Modder-Tool** (Python GUI, Apache-2.0; built for Chery `hmi.pack` /
  Haval, not OLA) does Kanzi RE + localization/customization via a
  signature-based `.kzb` search. Like the others it edits the container
  (textures/strings/layout), not behaviour — it adds no app-launch handler, so
  it does not close the native gap either.

- **kanzi-toolbox** (pixelpiloter) is not a tool but a documentation archive of
  ~40 Chinese CSDN articles on Kanzi *Studio* plugin/binding development (Kanzi
  3.6/3.9); it never references `.kzb`. The behaviour-authoring it documents
  happens inside Kanzi Studio against the original `.kzproj` source — which the
  OEM holds and we do not; it offers nothing that edits our compiled `.kzb`.

**Conclusion across all four tools:** every public Kanzi tool is either a
container editor (textures/strings/mesh, no behaviour) or Studio-authoring
documentation (needs the `.kzproj` source we lack). None can add a native
app-launch binding to a compiled `.kzb`. The overlay app is the correct solution
for a locked, compiled HMI, not a workaround.

## 13. Pegasus app integration — Passcode / Unlock / Mirror

The passcode-recovery, unlock, and live-screen work are built into the Pegasus
app (`dev.olatool`) as three admin-gated tabs in the tools group. Everything
privileged runs over the existing root gRPC shell (`Rpc.shell`, uid 0).

- **PasscodeTab** (`app/app/.../PasscodeTab.java`): "Pull from dash" greps the
  profile service's Room DB (+ -wal) and prefs for `$s0$` hashes over the root
  shell, then brute-forces the 6-digit space locally with scrypt
  (`org.bouncycastle:bcprov-jdk18on`), across all cores. A hit is saved to the
  `ola` prefs as `recovered_pin` so UnlockTab can prefill it.

- **UnlockTab** (`UnlockTab.java`): submits the PIN past the Kanzi pad two ways.
  *Taps* replays `input tap` at each digit using a 4-number pad calibration
  (centre of key "1", column dx, row dy; grid is 1-9 then 0 under the middle
  column) read off the Mirror tab — no extra binary. *Frida* writes `unlock.js`
  (asset template, `__PIN__` substituted) and runs
  `timeout 8 /data/local/tmp/frida-inject -n com.olaelectric.profilemanagerservice
  -s /data/local/tmp/unlock.js`; frida-inject (16.0.19 arm64) must be pushed
  once. It calls `challengeUnlockByPasscode(pin,false)` on the live service and
  returns `isStatus`.

- **MirrorTab** (`MirrorTab.java`) + **:mirroragent** module (`Agent.java`):
  live dash view with touch control, scrcpy-style but self-contained. The agent
  is a classes.dex (built as `mirroragent-debug.apk`, bundled into the app as
  `assets/mirror.apk` by a Gradle `copyMirrorAgent` task). MirrorTab pushes it to
  the dash over the root shell (base64 + `tee -a`, since the daemon's `${IFS}`
  splitter mangles `>>`/`2>&1`) and launches it:
  `setsid env CLASSPATH=/data/local/tmp/mirror.apk app_process /
  dev.olatool.mirror.Agent 8099 800 480 6000000`. Running under `app_process`
  (not an installed app) is what lets the hidden SurfaceControl / InputManager
  calls through on Android 10; they are all invoked by reflection so the module
  compiles against the public SDK.

  Agent wire protocol over one TCP socket on the dash (0.0.0.0:8099, reachable
  directly over Wi-Fi — no adb forward): header `int width,int height`; then
  video packets `int len, byte flags(bit0=codec-config, bit1=key), len bytes`
  of Annex-B H.264 from a MediaCodec encoder fed by a mirrored virtual display
  (`KEY_REPEAT_PREVIOUS_FRAME_AFTER` keeps frames flowing on a static dash).
  Control the other way: `byte action(0 down,1 up,2 move), int x, int y`, scaled
  from the SurfaceView and injected via `InputManager.injectInputEvent`.

  **Validated from PC without the phone** (2026-11-08): pushed the agent,
  launched it over gRPC, and a TCP probe read `header: 800x480`, a codec-config
  packet, a 496-byte key frame, and 32-byte delta frames — capture + encode +
  stream confirmed. The `env` prefix matters: `setsid CLASSPATH=... app_process`
  fails with "No such file or directory" because setsid treats the assignment as
  the program name; `setsid env CLASSPATH=... app_process` fixes it (same fix in
  MirrorTab.launchAgent).

Pending: push `frida-inject-16.0.19-android-arm64` to enable UnlockTab's Frida
path; calibrate the pad numbers once off the Mirror view for the tap path.
