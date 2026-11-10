---
name: ola-s1-passcode
description: The Ola S1 passcode lock: two authorities, the scrypt hash, and how to recover or feed a PIN. Use when the user wants to understand, crack, or bypass the dash passcode, or to unlock the scooter programmatically. Trigger: passcode, PIN, scrypt, $s0$ hash, crack_passcode.py, profile-manager-database, challengeUnlockByPasscode, frida unlock, Kanzi keypad, lock status.
---

# Ola S1 — Lock, unlock, and the passcode

The part people most want to "fix" and least understand.

## Two authorities

```
┌─────────────────────────────┐        ┌──────────────────────────┐
│ Head unit (Android)         │        │ Vehicle (VCU / BMS)      │
│                             │        │                          │
│ keypad                      │        │ awake?  yes / no         │
│ passcode check (scrypt)     │        │ accepts PASSCODE_AUTH?   │
│ sends PASSCODE_AUTH ────────┼───────►│ replies on COMMAND_ACK   │
│ shows result ◄──────────────┼────────│                          │
└─────────────────────────────┘        └──────────────────────────┘
```

**The head unit can say "the code is right". Only the vehicle can say "you are
unlocked".** When it is asleep it says nothing, and the UI treats silence as
failure. No amount of root on the head unit changes that.

## The keypad is not an Android dialog

It looks like an app screen. It is a **Kanzi scene** inside the HMI surface, raised
by a CAN frame, and it belongs to `UserProfileManager` — not to the reset /
passcode manager people assume.

Consequences:

- `input tap` does **not** press its keys. Kanzi ignores injected touches.
- `uiautomator dump` shows nothing useful; there are no Android views.
- The only things that move it are real touch (a finger), the vehicle's own state
  frames, or restarting the HMI app.

The keypad appears when the vehicle broadcasts `ACTIVATE_PASSCODE_WINDOW` on
`0x6D0`. It does not appear because an app decided to show it.

## The hash

The dash stores each profile's passcode as a lambdaworks scrypt hash:

```
$s0$<paramsHex>$<salt_b64>$<dk_b64>
```

produced by `SCryptUtil.scrypt(pin, N=1024, r=8, p=1)` (RESEARCH.md §11).

## Cracking it

`scripts/crack_passcode.py` recovers the PIN from a hash you already hold. The
PIN is 6 digits, so the full keyspace (000000–999999) brute-forces in
seconds-to-minutes. It never contacts the vehicle.

```sh
# crack one or more hashes passed on the command line
python scripts/crack_passcode.py '$s0$a0801$...salt...$...dk...'

# or scan a pulled Room DB (+ -wal/-shm) for every $s0$ hash and crack each
python scripts/crack_passcode.py --db profile-manager-database [more files...]
```

Pull the DB first with root over gRPC — `adb shell` is uid 2000 and cannot read
it:

```sh
python scripts/ola.py pull --ipv6 <addr> \
   //data/data/com.olaelectric.profilemanagerservice/databases/profile-manager-database pm.db
python scripts/ola.py pull --ipv6 <addr> \
   //data/data/com.olaelectric.profilemanagerservice/databases/profile-manager-database-wal pm.db-wal
```

## Feeding a known-correct PIN

`app/app/src/main/assets/unlock.js` is a Frida script run on the dash by
frida-inject (see `UnlockTab`). The app writes a copy with `__PIN__` replaced by
the recovered passcode, then injects it into
`com.olaelectric.profilemanagerservice`. It calls the real AIDL entry point on the
live service instance, feeding the known-correct PIN past the flaky Kanzi touch
pad. No PIN is faked and no crypto is forged:

```js
inst.challengeUnlockByPasscode('__PIN__', false);
```

## When to use this skill

- User wants to understand, crack, or bypass the dash passcode.
- User has a `$s0$` hash or a pulled Room DB and wants the PIN.
- User wants to unlock the scooter programmatically via Frida.
- User is stuck on the Kanzi keypad rejecting `input tap`.
- User asks about lock status, `PASSCODE_AUTH`, or `COMMAND_ACK`.