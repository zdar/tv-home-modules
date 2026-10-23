#!/usr/bin/env python3
"""Set up the Home Assistant backup for doorbell events (ring / motion / person).

  python tools/setup_events.py            new secret token -> TV, print HA config
  python tools/setup_events.py --show     print the HA config for the current token

The TV listens on port 8765 (POST /event, header X-Token). The token is
generated here, written into the launcher's launcher.properties on the TV and
printed only in this terminal: put it into Home Assistant's secrets.yaml.
"""
import os
import secrets
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

REMOTE = "/sdcard/Android/data/io.github.tvhomemodules.launcher/files/launcher.properties"
TV_MAC = os.environ.get("TV_MAC", "<tv-mac-address>")  # for Wake-on-LAN: dumpsys connectivity / your router
PORT = 8765

HA_YAML = """
# ---- secrets.yaml --------------------------------------------------------
tv_event_token: "{token}"

# ---- configuration.yaml --------------------------------------------------
rest_command:
  tv_doorbell_event:
    url: "http://{host}:{port}/event"
    method: POST
    headers:
      X-Token: !secret tv_event_token
    content_type: "application/json"
    payload: '{{"type": "{{{{ type }}}}"}}'

# ---- automations.yaml ----------------------------------------------------
# Replace the entity ids with your doorbell's (Settings > Devices > the doorbell).
- alias: "TV: doorbell ring"
  triggers:
    - trigger: state
      entity_id: binary_sensor.doorbell_visitor
      to: "on"
  actions:
    # Wake the TV from standby first (only works if the TV supports Wake-on-LAN)
    - action: wake_on_lan.send_magic_packet
      data:
        mac: "{mac}"
    - delay: "00:00:08"
    - action: rest_command.tv_doorbell_event
      data:
        type: ring

# Motion / person / pet / vehicle: one automation, the event type follows the sensor
- alias: "TV: doorbell detections"
  triggers:
    - trigger: state
      entity_id: binary_sensor.doorbell_person
      to: "on"
      id: person
    - trigger: state
      entity_id: binary_sensor.doorbell_motion
      to: "on"
      id: motion
    - trigger: state
      entity_id: binary_sensor.doorbell_pet
      to: "on"
      id: pet
    - trigger: state
      entity_id: binary_sensor.doorbell_vehicle
      to: "on"
      id: vehicle
  mode: queued
  actions:
    - action: rest_command.tv_doorbell_event
      data:
        type: "{{{{ trigger.id }}}}"
# configuration.yaml also needs:   wake_on_lan:
"""


def read_props():
    return tv.adb("shell", f"cat {REMOTE} 2>/dev/null").stdout.decode("utf-8", errors="replace").splitlines()


def main(argv):
    tv.ensure_connected()
    lines = read_props()
    token = next((l.split("=", 1)[1].strip() for l in lines if l.startswith("event_token=")), "")
    if "--show" not in argv or not token:
        token = secrets.token_urlsafe(24)
        lines = [l for l in lines if not l.startswith("event_token=")] + [f"event_token={token}"]
        with tempfile.NamedTemporaryFile("w", delete=False, encoding="utf-8", suffix=".properties") as f:
            f.write("\n".join(lines) + "\n")
            tmp = f.name
        try:
            r = tv.adb("push", tmp, REMOTE)
            if r.returncode != 0:
                sys.exit(r.stderr.decode(errors="replace"))
        finally:
            os.remove(tmp)
        print("New token stored on the TV (the listener picks it up within a minute).")
    print(HA_YAML.format(token=token, host=tv.HOST, port=PORT, mac=TV_MAC))


if __name__ == "__main__":
    main(sys.argv[1:])
