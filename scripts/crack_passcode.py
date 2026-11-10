#!/usr/bin/env python3
"""Recover an OLA MoveOS dash passcode from its scrypt hash (owner self-recovery).

The dash stores each profile's passcode as a lambdaworks scrypt hash
    $s0$<paramsHex>$<salt_b64>$<dk_b64>
produced by SCryptUtil.scrypt(pin, N=1024, r=8, p=1) (see RESEARCH.md §11).
The lock PIN is 6 digits, so the full keyspace (000000-999999) brute-forces in
seconds-to-minutes. This script never contacts the vehicle; it only cracks a
hash you already hold.

Usage
-----
  # crack one or more hashes passed on the command line
  python crack_passcode.py '$s0$a0801$...salt...$...dk...'

  # or scan a pulled Room DB (+ -wal/-shm) for every $s0$ hash and crack each
  python crack_passcode.py --db profile-manager-database [more files...]

Pull the DB first with root over gRPC (adb shell is uid 2000 and cannot read it):
  py scripts/ola.py pull --ipv6 <addr> \
     //data/data/com.olaelectric.profilemanagerservice/databases/profile-manager-database pm.db
  py scripts/ola.py pull --ipv6 <addr> \
     //data/data/com.olaelectric.profilemanagerservice/databases/profile-manager-database-wal pm.db-wal
"""
from __future__ import annotations
import argparse, base64, hashlib, re, sys, time
from multiprocessing import Pool

MAXLEN = 6                      # dash passcode length (6 circles on the pad)
HASH_RE = re.compile(rb"\$s0\$[0-9a-fA-F]+\$[A-Za-z0-9+/]+=*\$[A-Za-z0-9+/]+=*")

# --- globals for pool workers (set per-hash via initializer; Windows-spawn safe) ---
_SALT = _DK = _N = _R = _P = _WIDTH = None


def _init(salt, dk, n, r, p, width):
    global _SALT, _DK, _N, _R, _P, _WIDTH
    _SALT, _DK, _N, _R, _P, _WIDTH = salt, dk, n, r, p, width


def _work(i):
    cand = str(i).zfill(_WIDTH)
    out = hashlib.scrypt(cand.encode(), salt=_SALT, n=_N, r=_R, p=_P,
                         dklen=len(_DK), maxmem=128 * 1024 * 1024)
    return cand if out == _DK else None


def parse(stored: str):
    _, _, params_hex, salt_b64, dk_b64 = stored.split("$")
    params = int(params_hex, 16)
    n = 1 << ((params >> 16) & 0xFFFF)
    r = (params >> 8) & 0xFF
    p = params & 0xFF
    return n, r, p, base64.b64decode(salt_b64), base64.b64decode(dk_b64)


def crack(stored: str, width: int = MAXLEN, workers: int = 4):
    n, r, p, salt, dk = parse(stored)
    print(f"[*] {stored[:28]}...  N={n} r={r} p={p}  trying {10**width} x {width}-digit", flush=True)
    t0 = time.time()
    with Pool(workers, initializer=_init, initargs=(salt, dk, n, r, p, width)) as pool:
        for res in pool.imap_unordered(_work, range(10 ** width), chunksize=1000):
            if res is not None:
                pool.terminate()
                print(f"[+] PIN = {res}   ({time.time()-t0:.1f}s)", flush=True)
                return res
    print(f"[-] no {width}-digit match ({time.time()-t0:.1f}s)", flush=True)
    return None


def hashes_from_files(paths):
    blob = b""
    for p in paths:
        try:
            blob += open(p, "rb").read()
        except OSError as e:
            print(f"[!] {p}: {e}", file=sys.stderr)
    return sorted({m.group(0).decode() for m in HASH_RE.finditer(blob)})


def main(argv=None):
    ap = argparse.ArgumentParser(description="Crack an OLA dash passcode scrypt hash.")
    ap.add_argument("hashes", nargs="*", help="$s0$... hash string(s)")
    ap.add_argument("--db", nargs="+", default=[], metavar="FILE",
                    help="scan file(s) (DB/-wal) for $s0$ hashes and crack each")
    ap.add_argument("--len", type=int, default=MAXLEN, help="PIN length (default 6)")
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args(argv)

    targets = list(args.hashes)
    if args.db:
        found = hashes_from_files(args.db)
        print(f"[*] {len(found)} hash(es) found in {', '.join(args.db)}", flush=True)
        targets += found
    if not targets:
        ap.error("give a $s0$ hash or --db <file>")

    for h in dict.fromkeys(targets):     # dedupe, keep order
        crack(h, width=args.len, workers=args.workers)


if __name__ == "__main__":
    main()
