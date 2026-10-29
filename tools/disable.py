#!/usr/bin/env python3
"""Disable packages one at a time (one pm call per shell call) and log them.

  tools/disable.py com.tcl.bi com.google.android.feedback

Each package that `pm list packages -d` then reports as disabled is appended
to backup/disabled-by-us.txt, which restore.py reads.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

LOG = tv.ROOT / "backup" / "disabled-by-us.txt"

# Android's own core and the TV middleware: disabling any of these can leave the TV unable to
# boot (list from cleanyourtv's PROMPT.md and tools/disable.sh, cleanyourtv issues #884, #1578:
# a Haier set restarted in a loop with com.cltv.mal off).
CORE = {"android", "com.android.systemui", "com.android.shell", "com.android.phone",
        "com.android.settings", "com.android.tv.settings",
        "android.ext.services", "android.ext.shared",
        "com.cltv.mal", "com.mediatek.tis", "com.mediatek.tv.service", "com.mediatek.tv.agent"}
CORE_SUFFIXES = ("permissioncontroller", "packageinstaller", "extservices", "extshared",
                 "modulemetadata")
PROVIDERS_OK = {"calendar", "contacts", "userdictionary", "blockednumber"}


def is_core(p):
    if p in CORE or p.startswith("com.google.android.overlay.modules"):
        return True
    if p.startswith(("com.android.", "com.google.android.")) and p.rsplit(".", 1)[-1] in CORE_SUFFIXES:
        return True
    if p.startswith("com.android.providers."):
        return p.split(".")[3] not in PROVIDERS_OK
    return False


def disabled_set():
    out = tv.adb("shell", "pm list packages -d").stdout.decode(errors="replace")
    return {l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:")}


def main(pkgs):
    core = [p for p in pkgs if is_core(p)]
    if core:
        sys.exit("Refused, these are Android core packages: " + " ".join(core))
    tv.ensure_connected()
    logged = {l.strip() for l in LOG.read_text().splitlines()
              if l.strip() and not l.startswith("#")} if LOG.exists() else set()
    for p in pkgs:
        r = tv.adb("shell", f"pm disable-user --user 0 {p}")
        msg = (r.stdout + r.stderr).decode(errors="replace").strip()
        ok = p in disabled_set()
        print(f"{'OK  ' if ok else 'FAIL'} {p}: {msg}")
        if ok and p not in logged:
            with LOG.open("a") as f:
                f.write(p + "\n")
            logged.add(p)


if __name__ == "__main__":
    main(sys.argv[1:])
