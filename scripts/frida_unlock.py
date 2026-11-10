#!/usr/bin/env python3
"""Submit the owner's correct dash passcode programmatically via Frida, bypassing
the flaky Kanzi on-screen pad (owner self-recovery; see RESEARCH.md §11).

It attaches to com.olaelectric.profilemanagerservice and invokes the real AIDL
entry point on the live service instance:
    ProfileManagerServiceImpl.challengeUnlockByPasscode(<pin>, false)
On the correct PIN this returns isStatus=true and the app itself runs the
device-keyed unlock (setCurrentUser + sendUnlockByPasscode). No PIN is faked and
no crypto is forged — we just feed the known-correct PIN past a broken touch pad.
The physical vehicle unlock still needs the scooter awake/present.

Prereqds
--------
  * adb authorized to the dash (scripts/unlock-adb.ps1) — device id = <ip>:5555
  * frida-server 16.0.19 (NOT 17.x — that crashes this msm8953/Android-10 kernel
    with frida_sigchain_compat_validate_signal) running as root on the dash:
        adb -s <id> push frida-server-16.0.19-android-arm64 /data/local/tmp/frida-server16
        py scripts/ola.py shell --ipv6 <ip> "chmod 755 /data/local/tmp/frida-server16"
        py scripts/ola.py shell --ipv6 <ip> "setsid /data/local/tmp/frida-server16 >/data/local/tmp/fs16.out 2>/data/local/tmp/fs16.err &"
        adb -s <id> shell "pidof frida-server16"   # must print a pid

Run (py3.13 has no old-frida wheel; uv fetches a matching python):
    python -m uv run --python 3.11 --with "frida==16.0.19" \
        scripts/frida_unlock.py --device 192.168.0.238:5555 --pin 052213
"""
from __future__ import annotations
import argparse, sys, time
import frida

TARGET = "com.olaelectric.profilemanagerservice"
JS_TEMPLATE = r"""
Java.perform(function () {
  var done = false;
  try {
    Java.choose('com.olaelectric.profilemanagerservice.services.ProfileManagerServiceImpl', {
      onMatch: function (inst) {
        done = true;
        try {
          var res = inst.challengeUnlockByPasscode(%(pin)s, false);
          send('UNLOCK isStatus=' + res.isStatus() + ' remainingAttempts=' + res.getRemainingAttempts());
        } catch (e) { send('CALL_ERR=' + e); }
      },
      onComplete: function () { send('CHOOSE_DONE instance_found=' + done); }
    });
  } catch (e) { send('CHOOSE_ERR=' + e); }
});
"""


def main(argv=None):
    ap = argparse.ArgumentParser(description="Frida-submit the dash passcode past the touch pad.")
    ap.add_argument("--device", required=True, help="adb device id, e.g. 192.168.0.238:5555")
    ap.add_argument("--pin", required=True, help="the correct 6-digit passcode")
    ap.add_argument("--target", default=TARGET)
    ap.add_argument("--wait", type=float, default=4.0, help="seconds to keep the script alive")
    args = ap.parse_args(argv)

    for get in (lambda: frida.get_device(args.device, timeout=15),
                lambda: frida.get_usb_device(timeout=15)):
        try:
            dev = get(); break
        except Exception as e:
            print("device attempt failed:", e, file=sys.stderr)
    else:
        sys.exit("could not reach frida device (is frida-server 16.0.19 running as root?)")

    print("device:", dev)
    proc = dev.get_process(args.target)
    print("target pid:", proc.pid)
    session = dev.attach(proc.pid)
    script = session.create_script(JS_TEMPLATE % {"pin": "'%s'" % args.pin})
    script.on("message", lambda m, d: print("[frida]",
              m["payload"] if m.get("type") == "send" else m))
    script.load()
    time.sleep(args.wait)
    print("done")


if __name__ == "__main__":
    main()
