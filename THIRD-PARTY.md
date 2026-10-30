# Third-party material

This repository ships two data files that were not written here. Both come from
**Axiom**, a separate Android app for the same scooter, which is published under
the Apache License 2.0.

| File | Where it is used | Origin |
| --- | --- | --- |
| `app/app/src/main/assets/can_database.json` | signal table for every screen | Axiom `app/src/main/assets/can_database.json` |
| `app/app/src/main/assets/faults.json` | fault names and descriptions | Axiom `FaultRegistry` |

Axiom: <https://codeberg.org/pitzel/axiom>

## Licence

Axiom is licensed under the Apache License, Version 2.0. A copy is in
[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).

The two files above remain under that licence. They are redistributed here
unchanged in content, only renamed for this project. The rest of this repository
is under the MIT licence in [`LICENSE`](LICENSE).

## What was not copied

The app does not use Axiom's code. It is a separate Java implementation. In
particular:

- **No Shizuku.** Axiom uses Shizuku to reach a shell. This app already has
  root over the scooter's own gRPC daemon, so that dependency is gone.
- **No `su` or `sqlite3` binaries.** Axiom pushes those to the device. This app
  does not.
- **No automatic SQLite edits.** Axiom's `drive_mode_restriction` fix writes to
  the vehicle database. That is described in the docs as something Axiom does,
  but it is never run by this app.
- **Different Motorola decode.** Axiom's Motorola bit reader shifts the wrong
  way. This app follows the DBC sawtooth rule instead. The shipped table is all
  Intel, so the difference only matters for a hand-edited table.

The signal table is data, not code. It was used as the reference for the CAN
ids, bit positions and scaling that this project decodes.
