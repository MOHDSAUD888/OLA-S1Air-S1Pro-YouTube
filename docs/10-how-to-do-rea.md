# 10 — How to do this yourself

This is the method, not the result. If you have a different Ola unit, or a
different vehicle entirely, this is the part that transfers.

The short version: **watch behaviour, find the channel, prove it with the
machine, then read the code that made it.**

---

## The loop

```
behaviour  →  hypothesis  →  smallest test  →  evidence  →  read the code
     ↑                                                            │
     └──────────────────── refine ────────────────────────────────┘
```

Do not skip to "read the code". Decompiled labels lie. Behaviour does not.

Do not stop at "it works once". One working call proves one call, not a schema.

---

## Step 1 — Get a shell you can trust

Before anything else, find out what the device is:

```sh
getprop ro.build.version.release
getprop ro.build.display.id
getprop ro.build.version.security_patch
getprop ro.product.model
getprop ro.debuggable
getenforce
id
```

On this unit that gives Android 13, `test-keys`, `ro.debuggable=1`, SELinux
permissive. Those four facts tell you the device is a debug build, which changes
what is worth trying.

Write them down with a date. You will need them for any report.

---

## Step 2 — Find the listening sockets

```sh
netstat -tlnp
ss -tlnp
```

Look for anything on a non-standard port, and look at the **address** column.
`::` means every interface. `0.0.0.0` means every IPv4 interface.

Then check the firewall, and check it separately per family:

```sh
iptables -S INPUT
ip6tables -S INPUT
```

That comparison is how the IPv4-only block was found. One rule on one family is
a common mistake, and it is easy to see once you look.

---

## Step 3 — Identify what owns the port

```sh
ls -l /proc/<pid>/exe
cat /proc/<pid>/cmdline
cat /proc/<pid>/status | head
```

Then read the init script that started it:

```sh
grep -r 50005 /vendor/etc/init/
grep -r diagnostics_server /vendor/etc/init/
```

Init scripts are the best documentation on an Android device. They tell you the
service name, the user, the SELinux label, and the conditions under which it
starts.

---

## Step 4 — Recover the protocol, do not guess it

A Go binary built with `protoc-gen-go` embeds its own `FileDescriptorProto`. That
is the real schema, not a guess.

How to find it: search the binary for the descriptor magic and walk the length-
delimited fields. The descriptor starts near a `FileDescriptorProto` marker and
contains the service name, method names, and message definitions in order.

Why this matters: a hand-written proto **appeared** to work here. It called
`ExecuteADBCommand` correctly because the field number and wire type happened to
match. It was wrong about 23 other methods and invented three message types.

That mistake is preserved in `proto/wifican-first-attempt.proto` on purpose.

Rule: if the binary can tell you the schema, never guess the schema.

---

## Step 5 — Prove each primitive

For a shell RPC, the proof is boring and must be done:

```sh
id -u          → 0
id             → uid=0(root) ... context=u:r:shell:s0
```

Then test the edges, because the daemon is not a shell:

| Test | What it tells you |
|---|---|
| `id; echo SEP` | does `;` work? |
| `id \| tr a-z A-Z` | does a pipe work? |
| `echo hi > /tmp/x` | does redirection work? |
| `sh -c 'id; echo SEP'` | are quotes preserved? |

Here the answer was: the daemon splits on whitespace and `exec`s. No shell. The
escape hatch is `${IFS}`, which expands to a space inside the shell that `sh -c`
starts.

That is a general lesson: **test the parser, not just the happy path.**

---

## Step 6 — Get the code

The APKs are the specification. Decompile them.

`jadx` CLI was unavailable in this environment. The library entry point worked:

```
java -cp "jadx-all.jar;decompiler" Decomp <in.apk> <outdir> nores
```

Output for this unit:

| Directory | Contents |
|---|---|
| `dec_HVDP` | vehicle parser daemon, ~3,100 files |
| `dec_HHMIS` | HMI server, ~2,555 classes |
| `dec_HHMIC` | HMI client (Kanzi app), ~5,965 classes |

Start from a string you saw at runtime. If `logcat` printed
`challengeUnlockByPasscode`, grep the decompiled tree for it and follow the call
chain. That is faster and more reliable than reading packages alphabetically.

---

## Step 7 — Cross-check code against behaviour

The passcode work is the model for this:

- Code said: HMI sends `PASSCODE_AUTH` and waits for an ACK.
- Behaviour said: a correct code sometimes fails, slowly.
- Cross-check said: the vehicle is asleep, so no ACK arrives.

Neither source alone gave the answer. The code gave the mechanism; the behaviour
gave the condition.

**When they disagree, the owner of the vehicle wins.** Decompiled names are
often stale or aspirational. The person who rides it knows what it does.

---

## Step 8 — Write down the negative results

The most valuable notes in this research are the failures:

- `input tap` does not drive Kanzi — proven with an A/B test.
- `cansend` cannot fake the red button — tried, no effect.
- `CTA_UNLOCK` on the real bus — frame out, no ACK.
- No DTC read channel exists — searched, negative.
- The bit-to-name fault map is not in any APK — searched, negative.

A negative result stops the next person from repeating it. A guess does not.

---

## Practical setup

### Python

```powershell
py -m pip install grpcio
```

That is the only dependency for the client in this repo. `grpcio-tools` is only
needed if you want generated stubs from `proto/wifican.proto`.

### adb

Use a version that starts. 35.0.2 worked here; 37.0.1 did not. Test with:

```powershell
adb version
adb devices -l
```

### PowerShell traps

These cost real hours:

| Trap | What happens | Fix |
|---|---|---|
| `${IFS}` in a PowerShell string | PowerShell expands it to nothing | build the string in Python, or write a `.sh` file |
| quotes inside a command | PS 5.1 strips them before the daemon sees them | push a `.sh`, run `sh /path/x.sh` |
| `\|` in a double-quoted string | becomes a PowerShell pipe | use a `.sh` file |
| long base64 in an argument | command line limit | chunk it, 60,000 chars per call |

**Rule: any command with pipes, quotes, redirection, or flags-with-values goes
into a `.sh` file that you push and run.** Do not fight the quoting.

---

## Pulling data

The RPC returns a string. For binary data, base64 it on the device and decode on
the PC.

```sh
base64 /path/file
```

For big files, cut them up first, or use adb. Chunked pulls of a few hundred MB
are slow but they work. Anything over 1 GB belongs on a data drive, not in a
repo and not on the system disk.

`.gitignore` in this repo already excludes `*.tar`, `*.zip`, `*.apk`, `*.bin`,
`*.img`, `*.kzb` and logs.

---

## Keeping notes

A lab notebook that is worth anything has, for every claim:

1. **what** you did,
2. **when** (date and time),
3. **the exact command**,
4. **the exact output**,
5. **how sure you are**.

Mark confidence explicitly: *certain*, *high*, *inferred*, *unverified*. The
security doc in this repo uses three levels for a reason.

Two captures minutes apart, agreeing, is much stronger than one capture. Three is
better. A single reading can be a stale cache or a parsing bug.

---

## What to avoid

- **Do not flash.** No step here needs it.
- **Do not call `SwUpdate*`.** A failed ECU flash is not recoverable from the
  dash.
- **Do not press START on certificate generation.** It is a one-way action on a
  provisioning screen.
- **Do not write CAN frames you have not decoded.** The bus moves the vehicle.
- **Do not publish secrets.** If you find a key, rotate it and describe it
  without printing it.
- **Do not publish someone's identifiers.** VIN, serial, ICCID, IMSI, UUID, MAC,
  hotspot password, GPS — none of it belongs in a repo.
- **Do not restart adbd while scrcpy is live.** You will lose the encoder.

---

## A checklist for a new target

```
[ ] get a shell (any means) and record the build
[ ] netstat -tlnp; ss -tlnp
[ ] iptables -S; ip6tables -S
[ ] identify the owning binary and its init script
[ ] recover the protocol from the binary, do not guess
[ ] prove the primitive with id / a file read
[ ] test the parser edges (spaces, quotes, pipes)
[ ] decompile the apps and grep for runtime strings
[ ] cross-check code against behaviour
[ ] two captures, minutes apart
[ ] write the negative results down
[ ] strip secrets before sharing
```

If you can do all of that, you can repeat this work on almost any Android-based
vehicle display.
