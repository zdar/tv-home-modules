#!/usr/bin/env python3
"""One-button remote scan using the TV's own EventHub logging (no root needed).

  tools/scan_key.py clear          clear the log, then press ONE button
  tools/scan_key.py read [name]    show its scancode(s), keycode(s) and what opened

Results are appended to shots/remote-scan.txt.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

OUT = tv.ROOT / "shots" / "remote-scan.txt"
# Some buttons (Netflix = 4062 on this TCL) skip the keymap: a TCL service injects
# a private keycode, so injected keys (policyFlags 2b...) count too.
NOISE = set()


def main(argv):
    tv.ensure_connected()
    if not argv or argv[0] == "clear":
        tv.adb("shell", "logcat -c")
        print("cleared - press one button now")
        return
    name = " ".join(argv[1:]) or "?"
    log = tv.adb("shell", "logcat -d -v time").stdout.decode(errors="replace")
    scans = re.findall(r"EventHub.*?(/dev/input/event\d+) got: .*?type=1, code=(\d+), value=1", log)
    keys = [k + ("(injected)" if f.startswith("2b") else "")
            for k, f in re.findall(r"interceptKeyTq keycode=(\d+) .*?policyFlags=(\w+)", log) if k not in NOISE]
    front = tv.adb("shell", "dumpsys window windows | grep mFocusedApp").stdout.decode(errors="replace")
    m = re.search(r"u0 ([\w.]+/[\w.$]+)", front)
    line = (f"{name:<14} scancode={','.join(sorted({s for _, s in scans})) or '-'} "
            f"dev={','.join(sorted({d for d, _ in scans})) or '-'} "
            f"keycode={','.join(dict.fromkeys(keys)) or '-'} front={m.group(1) if m else '?'}")
    print(line)
    OUT.parent.mkdir(exist_ok=True)
    with OUT.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


if __name__ == "__main__":
    main(sys.argv[1:])
