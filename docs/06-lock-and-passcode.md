# 06 — Lock, unlock, and the passcode

This is the part people most want to "fix" and least understand.

The short version: **the dash verifies the code, but the vehicle decides whether
to unlock.** If the vehicle is asleep, a correct code still fails. No amount of
root on the head unit changes that.

---

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

The head unit can say "the code is right". Only the vehicle can say "you are
unlocked". When it is asleep it says nothing, and the UI treats silence as
failure.

---

## The keypad is not an Android dialog

It looks like an app screen. It is a Kanzi scene inside the HMI surface, raised
by a CAN frame, and it belongs to `UserProfileManager` — not to the reset /
passcode manager people assume.

Consequences:

- `input tap` does not press its keys. Kanzi ignores injected touches from
  `input` (doc 07).
- `uiautomator dump` shows nothing useful; there are no Android views.
- The only things that move it are real touch (a finger), the vehicle's own state
  frames, or restarting the HMI app.

The keypad appears when the vehicle broadcasts `ACTIVATE_PASSCODE_WINDOW` on
`0x6D0`. It does not appear because an app decided to show it.

---

## The full chain

```
keypad 0–9
  → UserProfileManager.onUpdatePasscode(char)
      builds the six-digit string
  → onPasscodeEntered(code, false)          false = not a change-passcode
  → HHMIS VehicleProfileManager.onPasscodeComplete(code, false)
  → IProfileManagerService.challengeUnlockByPasscode(code, false)   [binder]
      → HPMS checkPasscodeStatus → scrypt compare
          wrong → PasscodeStatus(remaining − 1, false)
          right → setCurrentUser
                  → sendUnlockByPasscode()
                  → VDP ICommanderService.unlockedByPasscode(true)
                  → MasterCommander.scooterUnlockedByPassCode(true)
                       loop 30× every 100 ms:
                         sendScooterCommands(25)   // PASSCODE_AUTH
                         wait for ACK
  → StateController.onPasscodeVerified(...)
  → HHMIC onPassCodeVerify(...)
```

`PASSCODE_AUTH` is command ordinal **25**. It goes out on **`0x6BC`** as
`19 00 00 00 00 00 00 00`. The vehicle is supposed to answer on **`0x6C0`**
with byte0 = 25.

That ACK is the *only* thing that sets the "unlocked" flag. The head unit sends
the request up to 30 times over 3 seconds, then gives up.

So the three-second freeze on the dots is not a bug. It is a blocking binder call
waiting out the vehicle's reply window.

---

## Why a correct code fails

If the vehicle is asleep, `0x6C0` never answers. The loop exhausts, the flag
stays false, and the HMI reports failure even though the passcode matched.

This was proven directly: the same code, entered correctly, failed while the bus
was asleep and succeeded when it was awake. The passcode was never the variable.

Diagnostic rule:

- **Wrong code** → attempt counter drops, and it fails fast.
- **Right code, asleep vehicle** → dots sit for ~3 s, then fail, counter may drop.

If it fails slowly, suspect sleep, not the code.

---

## Attempt budget

Five attempts. The remaining count is reported when the keypad activates.

A partial entry followed by Delete costs nothing, which makes input testing safe
if you are careful never to complete six digits with a wrong code.

The counter lives in the profile service, not in the HMI. Restarting the HMI does
not reset it.

---

## The red button

| Action | Result |
|---|---|
| Short press | wake / ask for passcode |
| Hold ~5 s | lock the scooter and turn the display off |

The button is not a simple GPIO. It shows up as CAN `0x153`, which is a status
broadcast, not a press edge. Sending `0x153` does **not** fake a press — that was
tried and failed.

The real trigger for the keypad is `ACTIVATE_PASSCODE_WINDOW` on `0x6D0`.

---

## What does not work (tested, negative)

Being honest about the dead ends saves you hours.

| Idea | Result |
|---|---|
| `input tap` on the keypad | nothing — Kanzi ignores it |
| `sendevent` / raw evdev writes | inconsistent; the HMI consumes real input, not injected frames |
| `cansend` a fake `0x153` | does not wake or trigger the keypad |
| Send `CTA_UNLOCK` (ordinal 23) on `0x6BC` | frame goes out, **no ACK**, no effect |
| Send `Vehicle_UNLOCK` on `0x6A1` | that ID is the cloud path; the dash never emits it |
| Restart the HMI while locked | clears the keypad **visually**, does not unlock |
| Wait for the dash to unlock itself | no such path exists |

The `CTA_UNLOCK` test was done on the real bus with the vehicle awake. The frame
was accepted by the bridge, appeared on the bus, and nothing answered. That is a
negative result, and it is worth more than a guess.

The only working unlock path from outside is Ola's own: phone app → cloud → TCU
→ VCU. The head unit is not part of that chain.

---

## What restarting the HMI does

```powershell
py scripts\ola.py hmi-restart --ipv6 fe80::1%6
```

This force-stops `com.olaelectric.hmiclient`, starts it again, and wakes the
panel. Effects:

- the keypad disappears from the screen
- the HMI re-reads vehicle state
- the attempt counter is **not** reset
- the scooter is **not** unlocked

It is a UI reset, not a security bypass. Useful when the dash is stuck or white.

---

## Can root unlock the scooter?

No.

Root gives you the head unit. The unlock decision is in the VCU, behind a CAN
ACK. You can send the request; you cannot make the vehicle answer. If the vehicle
is awake and the code is right, it answers. If either is false, nothing you do on
the Android side changes the outcome.

This is a good design, even though the surrounding code is messy. The screen is
not the authority.

---

## The passcode itself

Not published here, deliberately.

It is a six-digit code tied to a rider profile on a specific vehicle. Publishing
one would be publishing someone's key. If you are researching your own unit, use
your own code, and do not put it in a repo, a screenshot, or a log you share.

If you need to test the input path without spending attempts, enter fewer than
six digits and press Delete.
