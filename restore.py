#!/usr/bin/env python3
"""Undo the debloat: re-enable every package listed in backup/disabled-by-us.txt.

  python restore.py                 re-enable everything we disabled
  python restore.py com.tcl.bi      re-enable just these packages
  python restore.py --launcher      also put the stock launcher back as Home
                                    and uninstall the custom launcher
  python restore.py --dns           also switch Private DNS back to automatic

Packages the firmware had already disabled before we started
(backup/*/packages-disabled.txt) are left as they were.
TV_HOST / TV_PORT select the TV (see tools/tv.py).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "tools"))
import tv  # noqa: E402

LOG = tv.ROOT / "backup" / "disabled-by-us.txt"
STOCK_HOME = "com.google.android.tvlauncher/.MainActivity"
OUR_LAUNCHER = "io.github.tvhomemodules.launcher"


def sh(cmd):
    r = tv.adb("shell", cmd)
    out = (r.stdout + r.stderr).decode(errors="replace").strip()
    print(f"$ {cmd}\n  {out}")


def main(argv):
    tv.ensure_connected()
    flags = {a for a in argv if a.startswith("--")}
    pkgs = [a for a in argv if not a.startswith("--")]
    if not pkgs:
        pkgs = [l.strip() for l in LOG.read_text().splitlines()
                if l.strip() and not l.startswith("#")] if LOG.exists() else []
    for p in pkgs:          # one pm call per shell call
        sh(f"pm enable --user 0 {p}")
    if "--launcher" in flags:
        sh("pm enable --user 0 com.google.android.tvlauncher")
        sh(f"cmd package set-home-activity {STOCK_HOME}")
        sh(f"pm uninstall {OUR_LAUNCHER}")
    if "--dns" in flags:
        sh("settings put global private_dns_mode opportunistic")
    print("Done. Reboot the TV (adb reboot) to be sure everything restarts cleanly.")


if __name__ == "__main__":
    main(sys.argv[1:])
