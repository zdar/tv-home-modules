#!/usr/bin/env python3
"""Small ADB helper for the TV.

  tools/tv.py 'getprop ro.build.version.sdk'      run one shell command
  tools/tv.py --screenshot shots/01-after.png     capture on the TV and pull the PNG
  tools/tv.py --key DPAD_DOWN [DPAD_DOWN ...]      inject remote key presses
  tools/tv.py --adb install -r app.apk             any raw adb command

TV_HOST (required) / TV_PORT (default 5555) pick the TV. ADB is the adb binary:
the ADB environment variable, else platform-tools/ next to this repo, else adb on PATH.
"""
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOST = os.environ.get("TV_HOST", "")
PORT = os.environ.get("TV_PORT", "5555")
SERIAL = f"{HOST}:{PORT}"
_local = ROOT / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
ADB = os.environ.get("ADB") or (str(_local) if _local.exists() else "adb")


def adb(*args, check=False, capture=True, timeout=120):
    r = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=capture, timeout=timeout)
    if check and r.returncode != 0:
        sys.stderr.write(r.stderr.decode(errors="replace"))
        sys.exit(r.returncode)
    return r


def ensure_connected():
    if not HOST:
        sys.exit("Set TV_HOST to the TV's IP address (e.g. TV_HOST=192.168.1.50)")
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    if f"{SERIAL}\tdevice" not in out:
        subprocess.run([ADB, "connect", SERIAL], capture_output=True)


def screenshot(dest):
    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    adb("shell", "screencap -p /sdcard/_shot.png")
    adb("pull", "/sdcard/_shot.png", str(dest))
    adb("shell", "rm -f /sdcard/_shot.png")
    size = dest.stat().st_size if dest.exists() else 0
    if size == 0:
        print(f"{dest}: empty screenshot (0 bytes). Live TV / HDMI are protected and "
              "capture as nothing; check `dumpsys activity activities | grep topResumedActivity`.")
        sys.exit(1)
    print(f"{dest} ({size} bytes)")


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    ensure_connected()
    if argv[0] == "--screenshot":
        screenshot(argv[1] if len(argv) > 1 else "shots/shot.png")
    elif argv[0] == "--key":
        for k in argv[1:]:
            if k.startswith("sleep="):
                time.sleep(float(k[6:]))
                continue
            adb("shell", "input keyevent " + (k if k.isdigit() or k.startswith("KEYCODE_") else "KEYCODE_" + k))
            time.sleep(0.35)
    elif argv[0] == "--adb":
        r = adb(*argv[1:], capture=False, timeout=600)
        return r.returncode
    else:
        r = adb("shell", " ".join(argv))
        sys.stdout.write(r.stdout.decode("utf-8", errors="replace"))
        sys.stderr.write(r.stderr.decode("utf-8", errors="replace"))
        return r.returncode
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]) or 0)
