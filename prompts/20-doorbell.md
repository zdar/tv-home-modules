# Module 20 — Video doorbell

*Optional. Needs [Module 00](00-base.md). The Home Assistant backup in step 5 needs a Home
Assistant install, but not [Module 30](30-home-assistant.md).*

What you get:

- a live doorbell tile on Home;
- a full-screen 4K view;
- **ring → full screen** from any app or from standby;
- **person → a small picture-in-picture**.

It was built and tested with a **Reolink** video doorbell. Any camera with RTSP and ONVIF
PullPoint events should work; the stream paths below are Reolink's.

Paste the text below the line after Module 00, or on its own once the base is installed.

---

Set up the doorbell features that are built into this repo's launcher. My doorbell is a
`<MAKE / MODEL>` at `<DOORBELL IP>`. **Never type, store or print its login:**
`tools/set_doorbell.py` asks me for it in my own terminal and writes the RTSP URL only on the TV.

### What it does (already in `launcher/`; adjust only on my answers)

- **Home tile** (under the top-right pills, 336×189 dp): the live sub-stream (Reolink
  `/h264Preview_01_sub`, 640×480).
  - **Crop:** the 4:3 picture is cropped to 16:9. Ask me which part to keep: [**bottom**, to
    see straight down in front of the door] / centre / top. The crop is set by the `pivotY` line
    in `Doorbell.fitVideo`. With the bottom kept, the camera's own clock at the top is cut;
    full screen shows everything.
  - **Selectable:** OK opens full screen.
  - **Flashes** on detections: amber for a person; white for motion, a pet or a vehicle; not
    on a ring, which opens full screen anyway.
  - **Plays only while Home is visible.**
- **Full screen** (`DoorbellActivity`, its own task):
  - the main stream (`_sub` → `_main`, 2560×1920, falling back to the sub-stream) on a
    **SurfaceView**, i.e. the hardware video plane, which reaches 4K while the UI is 1080p;
    pillarboxed 4:3;
  - closes on OK or Back, or after `doorbell_auto_close_s` [60] s without doorbell activity or
    key presses (it stays open while motion or a person is still reported);
  - pauses media on open and resumes it on close.
- **Hold Blue** (`doorbell_hold_key=186`) toggles full screen from any app; a short Blue press
  passes through. Needs the key helper.
- **Events**, from two sources at once, deduplicated within 15 s:
  1. **ONVIF PullPoint**, from the TV straight to the doorbell (`onvif_port` [8000]):
     - the login is taken from the stored RTSP URL;
     - WS-Security digest, with the camera-clock offset applied;
     - `PropertyOperation="Initialized"` is ignored.
  2. **Home Assistant backup:** the TV listens on `event_port` [8765] for `POST /event` with
     header `X-Token`. Any other token gets 403.
- **Event types:** ring, person, motion, pet, vehicle. The action for each is `on_<type>` =
  `doorbell` (full screen), `pip` or `none` [ring → doorbell, person → pip, rest none].
- **Ring:**
  - wakes the screen and ends the screen saver;
  - if the ring woke the TV and nobody touches the remote, the auto-close puts it back into
    standby;
  - Home Assistant can send Wake-on-LAN first.
- **PiP:**
  - an accessibility overlay, bottom-right, 352×264 dp, showing the sub-stream;
  - takes no focus and pauses nothing;
  - not shown over Home or full screen;
  - closes `pip_auto_close_s` [10] s after the last event once nothing is active.

### Lessons (don't rediscover them)

- **RTSP sessions are scarce.** The Reolink allows only a few and keeps abandoned ones for
  minutes. An app kill or reinstall leaves one open, and new sessions then connect but get no
  video, while the vendor's desktop app, on another protocol, still works. So the launcher:
  - keeps a running stream when returning to Home instead of reopening it;
  - restarts it on window focus, after the screen saver, on wake and when the network returns;
  - runs a **stall watchdog**: no first frame within 12 s → release cleanly and retry after
    5 s, 10 s … up to 60 s, never resetting the backoff before a frame arrives.

  Avoid needless reinstalls while testing.
- Use RTSP over **TCP** (Media3 ExoPlayer RTSP, `setForceUseRtpTcp`).
- **SurfaceView vs TextureView:** a TextureView is composited in the 1080p UI and looks blocky
  full screen. Use a SurfaceView there; the tile can stay a TextureView, because it needs the
  crop transform.
- **Video planes and screenshots:** they come out black; check `mFocusedApp` instead.
- **PiP over playing video:** a second decoder next to a playing YouTube video can make YouTube
  stall on some TVs (cleanyourtv's Frigate Glance found this on a TCL). Test the PiP over a
  playing app and tell me if it stutters; `on_person=none` is the way out.
- Allow cleartext traffic (`usesCleartextTraffic`) for the LAN-only listener and the ONVIF
  calls.

### Steps

1. Check the doorbell answers on 554 (RTSP) and the ONVIF port. Ask me for the stream paths if
   it isn't a Reolink.
2. Ask me to run `DOORBELL_HOST=<ip> python tools/set_doorbell.py` (`--off` removes the tile).
3. Ask me the crop, the event actions and the timeouts. Write non-defaults to
   `launcher.properties`.
4. Open Settings → Accessibility and ask me to enable *Home: colour buttons* (if not already on).
5. **Optional HA backup:** ask me to run `TV_HOST=<ip> TV_MAC=<mac> python
   tools/setup_events.py`. It creates a token, stores it on the TV and prints the HA YAML
   (`secrets.yaml`, a `rest_command`, the automations, Wake-on-LAN) in my terminal only. I put my
   doorbell's entity ids in. `--show` prints it again.
6. Verify:
   - The tile shows video (a screenshot of a TextureView works).
   - The full screen opens: check `mFocusedApp` and the log line `video WxH`.
   - The log shows the ONVIF subscription.
   - The listener answers 403 to a wrong token.
   - Then ask me to **ring**, to walk past (person), and to **hold Blue**, on the real devices.
7. Add a *Doorbell* section to the README: the triggers table, setup, settings, and the "tile
   black after a reinstall → wait a few minutes" note.
