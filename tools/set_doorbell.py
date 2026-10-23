#!/usr/bin/env python3
"""Store the doorbell stream for the launcher's corner tile on the TV.

  python tools/set_doorbell.py                 asks for user + password
  python tools/set_doorbell.py --off           removes the tile

Writes doorbell_url=rtsp://USER:PASS@HOST:554/PATH into
/sdcard/Android/data/io.github.tvhomemodules.launcher/files/launcher.properties,
keeping every other line. The password is typed here, never stored on this PC.
Pick a Reolink account with view-only rights if you can.
"""
import getpass
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

REMOTE = "/sdcard/Android/data/io.github.tvhomemodules.launcher/files/launcher.properties"
HOST = os.environ.get("DOORBELL_HOST", "")
if not HOST:
    sys.exit("Set DOORBELL_HOST to the doorbell's IP address")
PATH = os.environ.get("DOORBELL_PATH", "h264Preview_01_sub")  # low-res sub-stream


def main(argv):
    tv.ensure_connected()
    current = tv.adb("shell", f"cat {REMOTE} 2>/dev/null").stdout.decode("utf-8", errors="replace")
    lines = [l for l in current.splitlines() if not l.startswith("doorbell_url=")]
    if "--off" not in argv:
        user = input("Reolink user [admin]: ").strip() or "admin"
        pw = getpass.getpass("Reolink password (not shown): ")
        lines.append(f"doorbell_url=rtsp://{quote(user, safe='')}:{quote(pw, safe='')}@{HOST}:554/{PATH}")
    with tempfile.NamedTemporaryFile("w", delete=False, encoding="utf-8", suffix=".properties") as f:
        f.write("\n".join(lines) + "\n")
        tmp = f.name
    try:
        tv.adb("shell", f"mkdir -p {REMOTE.rsplit('/', 1)[0]}")
        r = tv.adb("push", tmp, REMOTE)
        print("saved" if r.returncode == 0 else r.stderr.decode(errors="replace"))
    finally:
        os.remove(tmp)
    # Return to Home so the launcher re-reads its config
    tv.adb("shell", "input keyevent KEYCODE_HOME")


if __name__ == "__main__":
    main(sys.argv[1:])
