#!/usr/bin/env python3
"""Talk to an Ola S1 head unit over its root gRPC channel (TCP 50005).

No authentication. No adb. No pairing. Just IPv6.

Examples
--------
    python ola.py settings  --ipv6 fe80::1234:5678:9abc:def0
    python ola.py diag      --ipv6 fe80::1234:5678:9abc:def0
    python ola.py info      --ipv6 fe80::1234:5678:9abc:def0
    python ola.py shell     --ipv6 ... "ls -l /data/vendor/ola"
    python ola.py screenshot --ipv6 ... dash.png
    python ola.py push      --ipv6 ... local.txt /data/local/tmp/local.txt
    python ola.py pull      --ipv6 ... /data/local/tmp/x.txt x.txt

A link-local address needs a scope. Write it as `fe80::1%6` or pass `--scope 6`.

The RPC runs commands as root, but it does NOT use a shell. `ola.py shell`
wraps your command in `sh -c` and replaces spaces with `${IFS}` so pipes and
redirection work. Use `--raw` to send a command exactly as typed.
"""
from __future__ import annotations

import argparse
import base64
import os
import sys
import time

try:
    import grpc
except ImportError:
    sys.exit("Missing grpcio. Run:  python -m pip install -r requirements.txt")

PORT = 50005
MAX_MSG = 64 << 20

# Plain Android framework intents. No root writes, no vehicle state.
SETTINGS = {
    "settings": "android.settings.SETTINGS",
    "wifi": "android.settings.WIFI_SETTINGS",
    "bluetooth": "android.settings.BLUETOOTH_SETTINGS",
    "apps": "android.settings.APPLICATION_SETTINGS",
    "display": "android.settings.DISPLAY_SETTINGS",
    "sound": "android.settings.SOUND_SETTINGS",
    "battery": "android.settings.BATTERY_SAVER_SETTINGS",
    "storage": "android.settings.INTERNAL_STORAGE_SETTINGS",
    "about": "android.settings.DEVICE_INFO_SETTINGS",
    "notifications": "android.settings.NOTIFICATION_SETTINGS",
    "location": "android.settings.LOCATION_SOURCE_SETTINGS",
    "date": "android.settings.DATE_SETTINGS",
}

KEYS = {"back": 4, "home": 3, "recents": 187, "menu": 82, "power": 26}

HMI = "com.olaelectric.hmiclient/.MainActivity"
DIAG = "com.ola.mfgapp/.MainActivity"


# --------------------------------------------------------------------------
# protobuf, by hand. The server is weakly typed, so this is enough.
# --------------------------------------------------------------------------

def _vi(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | 0x80 if n else b)
        if not n:
            return bytes(out)


def _cmd_payload(text: str) -> bytes:
    raw = text.encode()
    return _vi((1 << 3) | 2) + _vi(len(raw)) + raw


def _read_varint(buf: bytes, i: int):
    value = shift = 0
    while True:
        b = buf[i]
        i += 1
        value |= (b & 0x7F) << shift
        if not b & 0x80:
            return value, i
        shift += 7


def _decode_response(buf: bytes):
    fields: dict[int, object] = {}
    i = 0
    while i < len(buf):
        key, i = _read_varint(buf, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            fields[field], i = _read_varint(buf, i)
        elif wire == 2:
            length, i = _read_varint(buf, i)
            fields[field] = buf[i:i + length].decode("utf-8", "replace")
            i += length
        else:
            break
    return fields.get(1, 0), fields.get(2, "")


# --------------------------------------------------------------------------
# connection
# --------------------------------------------------------------------------

def build_target(host: str, port: int, scope: int | None) -> str:
    h = host.strip()
    if h.lower().startswith("ipv6:"):
        h = h[5:].strip()
    h = h.strip("[]")
    if "]" in h:                       # "[addr]:port" was passed
        h = h.split("]")[0].lstrip("[")

    if h.count(":") >= 2:              # IPv6
        if h.lower().startswith("fe80") and "%" not in h:
            if scope is None:
                raise SystemExit(
                    "This is a link-local address. Add the interface number:\n"
                    "  --ipv6 fe80::1234:5678:9abc:def0%6\n"
                    "or  --scope 6\n"
                    "On Windows, find it with: Get-NetNeighbor -AddressFamily IPv6")
            h = f"{h}%{scope}"
        return f"ipv6:[{h}]:{port}"

    return f"{h}:{port}"               # IPv4 or hostname (usually blocked)


def connect(target: str, wait: float):
    channel = grpc.insecure_channel(
        target,
        options=[("grpc.max_receive_message_length", MAX_MSG),
                 ("grpc.max_send_message_length", MAX_MSG)])
    try:
        grpc.channel_ready_future(channel).result(timeout=wait)
    except grpc.FutureTimeoutError:
        channel.close()
        raise SystemExit(
            f"No answer from {target} after {wait:.0f}s.\n"
            "Check:\n"
            "  - the scooter is awake (red button / charger)\n"
            "  - you are on the same Wi-Fi or joined its hotspot\n"
            "  - the address is right and the scope is right (link-local)\n"
            "  - port 50005 is open:  python ola.py info --ipv6 ...")
    return channel


def call(channel, command: str, timeout: float):
    fn = channel.unary_unary(
        "/wifican.WifiCanRpc/ExecuteADBCommand",
        request_serializer=lambda b: b,
        response_deserializer=lambda b: b)
    raw = fn(_cmd_payload(command), timeout=timeout)
    return _decode_response(raw)


def run(channel, command: str, timeout: float = 30.0, quiet: bool = False):
    status, message = call(channel, command, timeout)
    text = (message or "").strip()
    if text and not quiet:
        print(text)
    return status, text


def sh_wrap(command: str) -> str:
    """Make an arbitrary command survive the daemon's whitespace split."""
    return "sh -c " + command.replace(" ", "${IFS}")


def ok(status: int) -> bool:
    return status in (0, 1)            # 1 = RESP_SUCCESS, 0 = RESP_NONE


# --------------------------------------------------------------------------
# actions
# --------------------------------------------------------------------------

def open_intent(channel, action: str, timeout: float = 30.0):
    status, _ = run(channel, f"am start -a {action}", timeout)
    time.sleep(1.5)
    top(channel)
    return status


def top(channel):
    status, out = run(
        channel,
        "sh -c dumpsys${IFS}activity${IFS}activities|"
        "grep${IFS}-E${IFS}ResumedActivity|head${IFS}-2",
        timeout=30, quiet=True)
    print("--- on screen now ---")
    print(out or "(no activity info)")
    return status


def push(channel, local: str, remote: str, timeout: float = 120.0):
    with open(local, "rb") as fh:
        data = fh.read()
    encoded = base64.b64encode(data).decode()
    chunk = 60_000
    first = True
    for start in range(0, len(encoded), chunk):
        part = encoded[start:start + chunk]
        redirect = ">" if first else ">>"
        cmd = f"sh -c echo${{IFS}}{part}|base64${{IFS}}-d{redirect}{remote}"
        status, message = run(channel, cmd, timeout, quiet=True)
        if not ok(status):
            raise SystemExit(f"push failed at byte {start}: {message}")
        first = False
    status, out = run(channel, sh_wrap(f"wc -c {remote}"), timeout, quiet=True)
    print(f"pushed {len(data)} bytes -> {remote}  ({out.strip()})")
    return 0


def pull(channel, remote: str, local: str, timeout: float = 120.0):
    status, encoded = run(channel, sh_wrap(f"base64 {remote}"), timeout,
                          quiet=True)
    if not ok(status):
        raise SystemExit(f"pull failed: {encoded}")
    data = base64.b64decode(encoded, validate=False)
    with open(local, "wb") as fh:
        fh.write(data)
    print(f"pulled {len(data)} bytes -> {local}")
    return 0


def screenshot(channel, path: str):
    tmp = "/data/local/tmp/_ola_shot.png"
    status, out = run(channel, f"screencap -p {tmp}", 60, quiet=True)
    if not ok(status):
        raise SystemExit(f"screencap failed: {out}")
    pull(channel, tmp, path, 120)
    run(channel, f"rm -f {tmp}", 30, quiet=True)
    return 0


def info(channel):
    checks = [
        ("uid", "id -u"),
        ("android", "getprop ro.build.version.release"),
        ("build", "getprop ro.build.display.id"),
        ("patch", "getprop ro.build.version.security_patch"),
        ("model", "getprop ro.product.model"),
        ("scooter mode", "getprop persist.ola.security.scootermode"),
        ("selinux", "getenforce"),
        ("uptime", "uptime"),
    ]
    for label, cmd in checks:
        status, out = run(channel, cmd, 20, quiet=True)
        print(f"{label:<14} {out.strip() or '-'}")
    return 0


def interactive(channel, raw: bool):
    print("Root shell. Type a command, or 'exit'.")
    print("Spaces are converted to ${IFS} unless --raw is set.")
    while True:
        try:
            line = input("ola> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if line in ("exit", "quit"):
            return 0
        if not line:
            continue
        cmd = line if raw else sh_wrap(line)
        try:
            run(channel, cmd, 60)
        except grpc.RpcError as exc:
            print(f"RPC error: {exc.code().name}: {exc.details()}")


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Root actions on an Ola S1 head unit over IPv6.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    parser.add_argument("--ipv6", required=False, default=None,
                        help="scooter address, e.g. fe80::1%%6 or 2001:db8::1")
    parser.add_argument("--scope", type=int, default=None,
                        help="interface number for a link-local address")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--timeout", type=float, default=30.0,
                        help="command timeout in seconds (default 30)")
    parser.add_argument("--wait", type=float, default=8.0,
                        help="connection timeout in seconds (default 8)")
    parser.add_argument("--raw", action="store_true",
                        help="send shell commands without the sh -c wrapper")

    # The same connection options are accepted before OR after the action.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--ipv6", default=argparse.SUPPRESS,
                        help="scooter address, e.g. fe80::1%%6 or 2001:db8::1")
    common.add_argument("--scope", type=int, default=argparse.SUPPRESS,
                        help="interface number for a link-local address")
    common.add_argument("--port", type=int, default=argparse.SUPPRESS)
    common.add_argument("--timeout", type=float, default=argparse.SUPPRESS,
                        help="command timeout in seconds")
    common.add_argument("--wait", type=float, default=argparse.SUPPRESS,
                        help="connection timeout in seconds")
    common.add_argument("--raw", action="store_true", default=argparse.SUPPRESS,
                        help="send shell commands without the sh -c wrapper")

    sub = parser.add_subparsers(dest="action", required=True)

    def add(name, **kw):
        return sub.add_parser(name, parents=[common], **kw)

    settings_p = add("settings", help="open Android Settings")
    settings_p.add_argument("--fresh", action="store_true",
                            help="force-stop Settings before opening it")
    for name in SETTINGS:
        if name != "settings":
            add(name, help=f"open {name} settings")
    add("diag", help="open the Ola diagnostic/service app")
    add("hmi", help="bring the HMI app to the front")
    add("hmi-restart", help="restart the HMI app (clears a stuck UI)")
    add("home", help="go back to the dash (HOME key)")
    add("back", help="back key")
    add("recents", help="recent apps")
    add("wake", help="wake the screen and keep it on")
    add("top", help="show the resumed activity")
    add("info", help="uid, Android build, scooter mode")

    key = add("key", help="send an Android key code")
    key.add_argument("code", type=int)

    tap = add("tap", help="tap a screen coordinate")
    tap.add_argument("x", type=int)
    tap.add_argument("y", type=int)

    swipe = add("swipe", help="swipe between two points")
    swipe.add_argument("x1", type=int)
    swipe.add_argument("y1", type=int)
    swipe.add_argument("x2", type=int)
    swipe.add_argument("y2", type=int)
    swipe.add_argument("ms", type=int, nargs="?", default=300)

    shell = add("shell", help="run a root shell command")
    shell.add_argument("command", nargs=argparse.REMAINDER)

    shot = add("screenshot", help="save a PNG of the screen")
    shot.add_argument("path")

    push_p = add("push", help="upload a file")
    push_p.add_argument("local")
    push_p.add_argument("remote")

    pull_p = add("pull", help="download a file")
    pull_p.add_argument("remote")
    pull_p.add_argument("local")

    args = parser.parse_args(argv)
    if not getattr(args, "ipv6", None):
        parser.error("--ipv6 is required (before or after the action)")

    target = build_target(args.ipv6, args.port, args.scope)
    channel = connect(target, args.wait)

    try:
        a = args.action

        if a in SETTINGS:
            fresh = getattr(args, "fresh", False)
            action = SETTINGS[a]
            cmd = f"am start -S -a {action}" if fresh else f"am start -a {action}"
            print(f"opening {a} ...")
            status, out = run(channel, cmd, 45 if fresh else args.timeout)
            time.sleep(1.5)
            top(channel)
            return 0 if ok(status) else 1

        if a == "diag":
            print("opening the diagnostic app ...")
            status, out = run(channel, f"am start -n {DIAG}", args.timeout)
            time.sleep(1.5)
            top(channel)
            return 0 if ok(status) else 1

        if a == "hmi":
            status, _ = run(channel, f"am start -n {HMI}", args.timeout)
            time.sleep(1.0)
            top(channel)
            return 0 if ok(status) else 1

        if a == "hmi-restart":
            print("force-stopping the HMI ...")
            run(channel, "am force-stop com.olaelectric.hmiclient", args.timeout)
            print("starting it again ...")
            run(channel, f"am start -n {HMI}", args.timeout)
            time.sleep(2.0)
            # force-stop drops the wake lock; wake the panel again.
            run(channel, "input keyevent 224", 20, quiet=True)
            run(channel, "input keyevent 82", 20, quiet=True)
            top(channel)
            return 0

        if a in KEYS:
            status, _ = run(channel, f"input keyevent {KEYS[a]}", args.timeout)
            time.sleep(0.8)
            top(channel)
            return 0 if ok(status) else 1

        if a == "key":
            status, _ = run(channel, f"input keyevent {args.code}", args.timeout)
            return 0 if ok(status) else 1

        if a == "tap":
            status, _ = run(channel, f"input tap {args.x} {args.y}", args.timeout)
            return 0 if ok(status) else 1

        if a == "swipe":
            cmd = (f"input swipe {args.x1} {args.y1} "
                   f"{args.x2} {args.y2} {args.ms}")
            status, _ = run(channel, cmd, args.timeout)
            return 0 if ok(status) else 1

        if a == "wake":
            run(channel, "input keyevent 224", 20, quiet=True)
            run(channel, "svc power stayon true", 20, quiet=True)
            print("screen awake, stay-on enabled")
            return 0

        if a == "top":
            return 0 if ok(top(channel)) else 1

        if a == "info":
            return info(channel)

        if a == "shell":
            if not args.command:
                return interactive(channel, args.raw)
            cmd = " ".join(args.command)
            status, _ = run(channel, cmd if args.raw else sh_wrap(cmd),
                            args.timeout)
            return 0 if ok(status) else 1

        if a == "screenshot":
            return screenshot(channel, args.path)

        if a == "push":
            return push(channel, args.local, args.remote)

        if a == "pull":
            return pull(channel, args.remote, args.local)

        parser.error(f"unknown action: {a}")

    except grpc.RpcError as exc:
        print(f"RPC error: {exc.code().name}: {exc.details()}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    finally:
        channel.close()


if __name__ == "__main__":
    sys.exit(main())
