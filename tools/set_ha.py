#!/usr/bin/env python3
"""Store the Home Assistant connection for the TV's lights panel (hold red).

  python tools/set_ha.py            asks for the long-lived access token
  python tools/set_ha.py --off      removes the token (panel shows "not set up")

Create the token in Home Assistant: your profile > Security > Long-lived access
tokens > Create token. It is typed here (not shown), written only into
/sdcard/Android/data/io.github.tvhomemodules.launcher/files/launcher.properties on the TV,
and never stored on this PC. HA_URL (required) is your Home Assistant, e.g. http://192.168.1.10:8123.
"""
import getpass
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

REMOTE = "/sdcard/Android/data/io.github.tvhomemodules.launcher/files/launcher.properties"
HA_URL = os.environ.get("HA_URL", "")


def main(argv):
    if not HA_URL and "--off" not in argv:
        sys.exit("Set HA_URL, e.g. HA_URL=http://192.168.1.10:8123 (an IP: Android TVs often can't resolve .local)")
    tv.ensure_connected()
    current = tv.adb("shell", f"cat {REMOTE} 2>/dev/null").stdout.decode("utf-8", errors="replace")
    lines = [l for l in current.splitlines() if not l.startswith(("ha_token=", "ha_url="))]
    if "--off" not in argv:
        token = getpass.getpass("Home Assistant long-lived access token (not shown): ").strip()
        if not token:
            sys.exit("no token entered, nothing changed")
        lines += [f"ha_url={HA_URL}", f"ha_token={token}"]
    with tempfile.NamedTemporaryFile("w", delete=False, encoding="utf-8", suffix=".properties") as f:
        f.write("\n".join(lines) + "\n")
        tmp = f.name
    try:
        tv.adb("shell", f"mkdir -p {REMOTE.rsplit('/', 1)[0]}")
        r = tv.adb("push", tmp, REMOTE)
        print("saved - the TV connects within a minute" if r.returncode == 0 else r.stderr.decode(errors="replace"))
    finally:
        os.remove(tmp)


if __name__ == "__main__":
    main(sys.argv[1:])
