# Module 30 — Home Assistant lights panel

*Optional. Needs [Module 00](00-base.md) and a Home Assistant install the TV can reach.*

Hold **Red** in any app to open a glass panel on the right with your lights, ordered by how
close they are to the TV: its room, then its floor, then the other floors. The app behind keeps
playing. Toggle a light and set its brightness from the remote.

Paste the text below the line after Module 00, or on its own once the base is installed.

---

Set up the Home Assistant lights panel that is built into this repo's launcher. My Home
Assistant is at `<HA URL, e.g. http://192.168.1.10:8123>`. Use an **IP address**: Android TV
often can't resolve `.local` names. **Never ask me for, type, store or print my HA token:**
`tools/ha_lights.py` and `tools/set_ha.py` ask me for it in my own terminal.

### First, a mockup with my real lights

1. Ask me to run `HA_URL=<url> python tools/ha_lights.py`. It asks for a token, reads the
   lights and writes only entity ids, names, floors, areas, state, brightness, colour modes and
   labels to `shots/ha_lights.txt`. Read that file.
2. Show me a mockup of the panel with my lights in the order below, and ask me:
   - which room the TV is in (`ha_tv_area`);
   - the order of the other rooms (`ha_rooms`);
   - which HA label marks the lights to show [`TV`; all lights if none has it];
   - which label marks motion-controlled lights [`Motion`].

   Redo the mockup until I'm happy. If a light I expect is missing, it usually has no area,
   floor or label in HA yet: tell me which, and let me fix it in HA, not in the launcher.

### What it does (already in `launcher/`; adjust only on my answers)

- **Connection:**
  - one persistent WebSocket to HA (OkHttp), authenticated with a long-lived token that
    `tools/set_ha.py` stores only on the TV;
  - on connect it reads `config/{entity,device,area,label,floor}_registry/list` and
    `get_states`;
  - it subscribes to `state_changed` and to the `*_registry_updated` events, so renamed lights,
    new labels and moved areas show up within seconds;
  - it reconnects with backoff and **never retries a rejected token**.
- **Which lights:** those with the label `ha_label`, or all lights if no light has it.
- **Order:**
  1. The TV's floor first, with the TV's room on top, then the `ha_rooms` order, then the rest
     of that floor.
  2. Every other floor under its own divider and name, dimmed, nearest floor first (by HA floor
     level).
  3. Without a TV room, the lowest floor first.

  The area comes from the entity, or else from its device. Lights with the motion label are
  smaller and last in their room. A room prefix is shortened ("Kitchen Ceiling" → "Ceiling").
- **Panel:**
  - a glass panel on the right, drawn as an accessibility overlay;
  - the app behind is dimmed and keeps playing.
- **Keys:**
  - ▲▼ choose;
  - **OK** `light.toggle`;
  - **◀ ▶** `light.turn_on` with `brightness_step_pct` ∓10 (on/off-only lights: off/on);
  - **Back** or Red closes.

  It auto-hides after 30 s and reopens on the last light used.
- **No colour-temperature control** by default, so HA automations (adaptive lighting) keep
  control. Add it only if I ask.
- **Status lines:** not set up, token rejected, HA unreachable, no lights.
- **Hold key:** Red (`ha_hold_key=183`, `0` = off). The red ring on Home shows the hold label.

### Steps

1. Ask me to create a long-lived access token in HA (profile → Security), ideally for a
   dedicated non-admin user, and to run `TV_HOST=<ip> HA_URL=<url> python tools/set_ha.py`
   (`--off` removes it).
2. Write `ha_url`, `ha_tv_area`, `ha_rooms`, `ha_label` and `ha_motion_label` to
   `launcher.properties`.
3. Open Settings → Accessibility and ask me to enable *Home: colour buttons* (if not already on).
4. Verify:
   - The log shows the connection and one line per light with its floor and room. Then check
     that a light I rename in HA updates without a restart.
   - Ask me to **hold Red** on the real remote and to toggle and dim a light.
5. Add a *Lights panel* section to the README: controls, which lights, ordering, setup and
   settings.
