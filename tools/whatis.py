#!/usr/bin/env python3
"""Say what a package is without starting it: label, components and permissions
from `dumpsys package`.   tools/whatis.py com.tcl.cyberui [more ...]"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

tv.ensure_connected()
for p in sys.argv[1:]:
    out = tv.adb("shell", f"dumpsys package {p}").stdout.decode(errors="replace")
    comps = sorted(set(re.findall(re.escape(p) + r"/([\w.$]+)", out)))
    actions = sorted(set(re.findall(r'Action: "([\w.]+)"', out)))
    perms = sorted(set(re.findall(r"android\.permission\.(\w+): granted=true", out)))
    print(f"=== {p}")
    print("  components:", " ".join(c.replace(p, "") for c in comps[:16]))
    print("  actions:   ", " ".join(a for a in actions if not a.startswith("android.intent.action.MAIN"))[:400])
    print("  perms:     ", " ".join(perms)[:300])
