#!/usr/bin/env python3
"""Passive watch of the TV: idle -> screen saver -> standby. No keys, no screenshots.

  python tools/monitor.py [minutes]        default 120

Once a minute it records wakefulness, the running screen saver, the app in front and
new log lines from the launcher (art, doorbell, lights, key helper) into
shots/monitor.log. Ends when the TV stops answering (standby) or after the time limit.
"""
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

LOG = tv.ROOT / "shots" / "monitor.log"
TAGS = "ArtSource:V ArtDream:V KeyHelper:V DoorbellEvents:V HaClient:I DreamController:I DreamManagerService:I *:S"


def sh(cmd, timeout=20):
    try:
        r = subprocess.run([tv.ADB, "-s", tv.SERIAL, "shell", cmd], capture_output=True, timeout=timeout)
        return r.returncode, r.stdout.decode("utf-8", errors="replace")
    except subprocess.TimeoutExpired:
        return 1, ""


def note(msg):
    line = f"{datetime.now():%H:%M:%S}  {msg}"
    print(line, flush=True)
    with LOG.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def main(minutes):
    LOG.parent.mkdir(exist_ok=True)
    note(f"monitor start (max {minutes} min)")
    end = time.time() + minutes * 60
    last_state, misses = None, 0
    since = datetime.now().strftime("%m-%d %H:%M:%S.000")
    while time.time() < end:
        try:  # a sleeping TV makes connect hang: that just counts as "not answering"
            subprocess.run([tv.ADB, "connect", tv.SERIAL], capture_output=True, timeout=15)
        except subprocess.TimeoutExpired:
            pass
        rc, power = sh("dumpsys power | grep -m1 mWakefulness=")
        if rc != 0 or "mWakefulness" not in power:
            misses += 1
            note(f"TV not answering ({misses})")
            if misses >= 3:
                note("TV gone from the network for 3 checks: standby (or off) - monitor ends")
                return
            time.sleep(60)
            continue
        misses = 0
        wake = power.split("=", 1)[1].strip()
        _, dream = sh("dumpsys dreams | grep -m1 mCurrentDreamName")
        dream = dream.split("=", 1)[-1].strip().replace("ComponentInfo{", "").rstrip("}")
        _, front = sh("dumpsys window windows | grep -m1 mFocusedApp")
        m = re.search(r"u0 ([\w.]+/[\w.$]+)", front)
        state = f"wake={wake} dream={dream or '-'} front={m.group(1) if m else '?'}"
        if state != last_state:
            note(state)
            last_state = state
        _, logs = sh(f"logcat -d -v time -T '{since}' -s {TAGS}")
        since = datetime.now().strftime("%m-%d %H:%M:%S.000")
        for l in logs.splitlines():
            if l.startswith("-") or not l.strip():
                continue
            note("   log: " + re.sub(r"rtsp://[^@\s]*@", "rtsp://***@", l.strip()))
        time.sleep(60)
    note("time limit reached - monitor ends")


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 120)
