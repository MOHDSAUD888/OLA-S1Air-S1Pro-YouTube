# 05 — The CAN bus

Everything the scooter knows about itself travels on a CAN bus. The head unit
does not invent those numbers; it reads them.

This page is the map: which ID carries what, and how to turn bytes into numbers.

---

## How the bus reaches Android

```
vehicle CAN wire
      │
      ▼
UART converter  /dev/ttyS1 @ 921600
      │
      ▼
vcan0   (virtual CAN interface inside Android)
```

`vcan0` is not filtered. If it is quiet, the vehicle really is quiet — it is not
a cable fault and not a missing ID.

Ways to read it:

| Path | What you get | Notes |
|---|---|---|
| `candump vcan0` | raw frames | simplest, works from root shell |
| gRPC `CanDumpStream` | the same tap | unfiltered; this is the path the app uses |
| gRPC `ReadCanStream` | frames after `Init`, in theory | stayed silent on our unit even after `Init`; prefer the dump tap |
| gRPC `ReadCanFrame` | only IDs you subscribed to with `Init` | blocks forever if none arrive |
| `logcat` from the parser app | every frame the app parsed | full rate, no wedge risk |

On the unit this was tested against, `ReadCanStream` returned zero frames even
after `Init` with the live IDs, while `CanDumpStream` delivered exactly the same
sleep trickle that `candump` showed. Treat the dump tap as the reliable push
path, and do not assume a silent `ReadCanStream` means a silent bus.

The `logcat` path is underrated. The vehicle parser logs each frame it decodes,
so `logcat` gives you a decoded stream without writing any code.

---

## Awake vs asleep

| State | Traffic |
|---|---|
| Awake | ~188 IDs, ~970 frames/s |
| Asleep | ~2 frames/s, only a trickle |

The trickle IDs are `6D2`, `643`, `6DB`, `66D`, `65D`. If you see only those,
the vehicle is asleep and no health data will arrive.

```sh
timeout 3 candump vcan0 | wc -l
#   < 100  -> asleep
#  ~3000  -> awake
```

This is the single most common source of "the data is missing" confusion.

---

## Frame format for writing

The gRPC `CANPacket.can_frame` field is 16 bytes:

```
[0:4]  CAN ID, BIG-endian
[4]    DLC
[5:8]  padding
[8:16] data
```

A little-endian ID is silently dropped. This cost real debugging time:

```python
frame = struct.pack(">I", can_id) + bytes([dlc, 0, 0, 0]) + data8
```

Proof that a write is not silently dropped: writing ID `0x123` with `DE AD BE EF`
appeared on `vcan0` and in the parser log as
`Raw Can ID : 123 ---- Raw Data : [DE, AD, BE, EF, 00, ...]`.

What is **not** proven is that an ECU always acts on a written frame. The one
command tested end-to-end on the real bus (`CTA_UNLOCK`) produced no response at
all — see doc 06. So: writes leave the head unit, but you cannot assume the
vehicle obeys them.

From a shell, `cansend` is simpler:

```sh
cansend vcan0 123#DEADBEEF00000000
```

---

## The IDs that matter

### Battery

| Metric | ID | Bytes | Formula |
|---|---|---|---|
| Overall SOC | `0x120` | 2–3 | LE16 × 0.01 → % |
| Pack current | `0x140` | 4–5 | LE16 × 0.01 − 300 → A |
| Pack voltage | `0x150` | 0–1 | LE16 × 0.001 → V |
| Pack SOH | `0x150` | 5 | raw byte → % |
| Effective pack temp | `0x160` | 1–2 | LE16 × 0.1 − 50 → °C |
| Pack temp min / max | `0x160` | 3–4 / 5–6 | same |
| Cell min / max voltage | `0x170` | 2–3 / 4–5 | LE16 → mV |
| Pack temps 1–4 | `0x1A4` | 8 slots | LE16 × 0.1 − 50 |
| Pack temps 5–6 | `0x1A7` | 0–3 | same |
| Pack voltage (2nd sensor) | `0x1A8` | 4–5 | LE16 × 0.001 |
| PDU / AFE temps | `0x1A8` | 0–1 / 2–3 | LE16 × 0.1 − 50 |
| Individual cells 1–4 | `0x1A5` | 4 slots | LE16 → mV |
| Cells 5–8 | `0x1A6` | 4 slots | same |
| Cells 9–12 | `0x1A9` | 4 slots | same |
| Cells 13–16 | `0x1AA` | 4 slots | same |

The pack is **14S NMC**. Slot 16 in `0x1AA` is zero, and slot 15 repeats cell 13.
That is how the cell count was settled — by the data, not by a config file.

Sanity check: 14 × ~4.10 V ≈ 57.4 V, and 4.10/4.20 ≈ 97.6 %, which matches the
reported SOC. A 15S or 16S pack cannot produce that voltage at that SOC.

Cell spread (`max − min`) is the health number that matters. Under ~30–50 mV is
healthy. A big spread under load means a weak cell.

### Motor and controller

| Metric | ID | Bytes | Formula |
|---|---|---|---|
| Motor RPM | `0x313` | 0–1 | LE16 |
| MCU temperature | `0x313` | 2–3 | LE16 × 0.01 → °C |
| Motor temperature | `0x313` | 4–5 | LE16 × 0.01 → °C |
| Speed | `0x352` | — | parser `parseSpeedData` |
| MCU voltage | `0x113` | — | — |
| Motor current / torque | `0x223` | — | — |
| VCU / SOM temperature | `0x64B` | — | — |

Temperature thresholds come from the shipped config:

| Limit | MCU | Motor |
|---|---|---|
| Warn | 95 °C | 147 °C |
| Derate | 105 °C | 157 °C |
| Cut | 115 °C | 165 °C |
| Reset | 85 °C | 90 / 140 °C |

These are far above anything you will see parked. A parked unit sits around
25–30 °C.

### Dashboard and body

| Metric | ID | Notes |
|---|---|---|
| Display SOC | `0x352` | byte0, raw % |
| Speed | `0x352` | same frame |
| Odometer | `0x356` | bytes0–3, LE32, metres |
| Range per mode | `0x6F4` | Eco / Sport / Normal / Hyper |
| Side stand, trunk, lights, brake wear | `0x353` | bit fields |
| Vehicle safety state | `0x683` | see below |

---

## `0x683` — the safety state frame

This one is nibble-based, not one-bit-per-fault. That catches people out.

| Field | Location | Mask |
|---|---|---|
| Fall detection | byte0 low nibble | `b0 & 0x0F` |
| Tow / theft detection | byte0 high nibble | `(b0 >> 4) & 0x0F` |
| Regen availability | byte1 low nibble | `b1 & 0x0F` |
| Hill hold | byte1 high nibble | `(b1 >> 4) & 0x0F` |
| Tamper pseudo-wakeup | byte2 bit1 | `b2 & 0x02` |

Enums:

```
FallDetection     0 SNA | 1 ACTIVE | 2 INACTIVE | 3 RECOVERED
TowTheftDetection 0 SNA | 1 ACTIVE | 2 INACTIVE
RegenAvailability 0 NOT_AVAILABLE | 1 AVAILABLE
                  2 NOT_AVAILABLE_THROTTLE | 3 Reserved
HillHold          0 SNA | 1 UNAVAILABLE | 2 ACTIVE | 3 NOT_ACTIVE
                  4 ABT_TO_DEACTIVATE | 5 ABT_TO_ACTIVATE | 6 ACTIVATED
```

A payload of `00 02 00 00 00 00 00 00` means:

- fall detection: **off in settings** (`SNA`), not "no hardware"
- tow/theft: **off in settings**
- regen: **not available, throttle-limited** (`2`)

That `SNA` distinction matters. It means the feature exists but the rider turned
it off, not that the scooter lacks it.

### Regen

Regen availability `2` was confirmed by the owner: regen is cut above roughly
**90 % SOC**. The dial has four positions, and they map to a strength:

| Dial | Strength |
|---|---|
| Off | 0 % |
| Low | 5 % |
| Medium | 10 % |
| High | 15 % |

The owner's unit was on **Medium**. This is a behaviour the owner confirmed, not
something readable from a label.

---

## Faults

There is **no DTC read channel** on this bus. No UDS request/response service is
exposed for diagnostics in the normal sense.

What you get is broadcast fault bits inside the normal frames, plus fault-bucket
metadata in the shipped `som_service_config.json`. That config names signals and
CAN IDs but carries no bit offsets or scaling — so it tells you a fault exists,
not how to decode it.

The bit-to-name map is **not present in any APK on the unit**. That was searched
for and came back negative. So a raw fault bit can be seen but not always named.

Practical rule: if you need a real diagnostic trouble code, this vehicle does not
give you one over CAN. You get symptoms.

---

## Reading health without writing code

The quickest reliable path:

```sh
# on the device, as root
logcat -c
logcat -s VehicleParser:* > /data/local/tmp/parse.log &
sleep 10
kill %1
```

Then grep for the signal names. The parser logs decoded values, so you get
`Battery_Current_00`, `Overall_SOC`, `MCU_Temperatute` (sic — the typo is in the
shipped config) and so on without writing a decoder.

For raw frames:

```sh
timeout 15 candump -ta vcan0 > /data/local/tmp/can.log
```

Pull it and decode offline. Note that with `candump -ta`, the CAN ID is field 3,
not field 2 — a classic `awk` mistake.

---

## Writing to the bus

`cansend` and `WriteCanFrame` both transmit. Treat this as a loaded gun.

- A frame can move the vehicle, lock it, or trip a fault.
- Some IDs are commands (`0x6BC`, `0x6A1`); writing them can start a real
  action on the VCU.
- The passcode unlock was tested deliberately, by the owner, on a stationary
  scooter. See doc 06.

Do not write IDs you have not decoded. Do not replay a captured frame "to see
what happens" on a moving vehicle.

---

## Sleep: the rule that explains everything

The head unit cannot wake the scooter. Wake is owned by the BMS, and it accepts
only these reasons:

| Reason | Trigger |
|---|---|
| `Charger_CAN_Wakeup` | charger plugged in |
| `IMU_Wakeup` | movement / tamper |
| `SoC_Push_Button_Wakeup` | the red Start/Stop button |
| `RTC_Wakeup` | clock / schedule |
| `EOL_WakeUp` | factory end-of-line tool |

No CAN frame from the dash substitutes for these. That is why a correct passcode
can fail, why remote unlock does nothing, and why health data disappears while
Android is still perfectly alive.

---

## What is proven and what is inferred

Proven, from live captures and the app's own parser code:

- frame layout and byte order for writes
- SOC, voltage, current, temperature, SOH formulas
- cell count (14S) and cell layout
- odometer and range frames
- `0x683` bit layout
- regen availability enum, and the 90 % cut-off (owner-confirmed)

Inferred, marked as such in the research notes:

- cycle count and lifetime energy counters (`0x6D8`, `0x6DC`, `0x6E0`, `0x6E2`, `0x6E4`)
- PDU/AFE temperature byte positions
- some SOH array bytes

If a number matters for a decision, verify it against a second source before you
trust it.
