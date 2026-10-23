#!/usr/bin/env python3
"""List Home Assistant lights (id, name, floor, area, state, brightness, labels) into
shots/ha_lights.txt, for designing the TV lights panel.

  python tools/ha_lights.py

Asks for a long-lived access token (HA > your profile > Security > Long-lived
access tokens > Create token). The token is used for this one request only and
is not saved anywhere. Only entity names/areas/states are written to the file.
"""
import getpass
import json
import os
import sys
import urllib.request
from pathlib import Path

HA = os.environ.get("HA_URL", "")
if not HA:
    sys.exit("Set HA_URL, e.g. HA_URL=http://192.168.1.10:8123 (an IP: Android TVs often can't resolve .local)")
OUT = Path(__file__).resolve().parent.parent / "shots" / "ha_lights.txt"

TEMPLATE = (
    "{% for s in states.light | sort(attribute='entity_id') %}"
    "{{ s.entity_id }}|{{ s.name }}|{{ floor_name(s.entity_id) or '' }}|{{ area_name(s.entity_id) or '' }}|{{ s.state }}|"
    "{{ state_attr(s.entity_id, 'brightness') or '' }}|"
    "{{ (state_attr(s.entity_id, 'supported_color_modes') or []) | join(',') }}|"
    "{{ labels(s.entity_id) | map('label_name') | join(',') }}\n"
    "{% endfor %}"
)


def main():
    token = getpass.getpass("HA long-lived access token (not shown, not saved): ").strip()
    req = urllib.request.Request(HA + "/api/template", data=json.dumps({"template": TEMPLATE}).encode(),
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        body = urllib.request.urlopen(req, timeout=15).read().decode("utf-8")
    except Exception as e:
        sys.exit(f"HA request failed: {e}")
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text("entity_id|name|floor|area|state|brightness(0-255)|color_modes|labels\n" + body, encoding="utf-8")
    print(f"{body.count(chr(10))} lights written to {OUT}")


if __name__ == "__main__":
    main()
