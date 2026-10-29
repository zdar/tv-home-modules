# Tested device, lessons and known issues

## Tested on

| | |
|---|---|
| TV | TCL, model `BeyondTV2` (Realtek, `armeabi-v7a`), Android TV 9 (API 28), 2 GB RAM |
| Display | 4K panel; the Android UI runs at **1920×1080 @ 320 dpi**, and only video planes reach 4K |
| Network | Ethernet, network ADB on 5555, `ro.adb.secure=1` |
| Sound | AV receiver on HDMI ARC/CEC (left untouched, still working after the debloat) |
| Doorbell | Reolink video doorbell: RTSP sub 640×480, main 2560×1920, ONVIF on port 8000 |
| Home Assistant | 2026.x, WebSocket API, floors and labels in use |

The other TCL Android 9 models (BeyondTV / BeyondTV2) share the firmware quirks below. The
debloat list used on the test TV is in
[`examples/tcl-beyondtv2-android9/debloat.txt`](../examples/tcl-beyondtv2-android9/debloat.txt).
See also cleanyourtv's
[device notes](https://github.com/Gathin23/cleanyourtv/tree/main/docs/devices) and its
[Latin-America BeyondTV2 list](https://github.com/Gathin23/cleanyourtv/tree/main/examples/tcl-beyondtv2-latin-america).

## TCL Android 9 quirks

- **Home takeover:** `cmd package set-home-activity` prints `Success` but the Home key only
  moves once `com.google.android.tvlauncher` is disabled.
- **Re-enabled at boot?** `com.tcl.notereminder` and `com.tcl.usercenter` were enabled again
  after the reboot on the test TV, and the reminder tile is hidden (`hidden=`) instead. Not yet
  re-tested with a 15 s wait before the reboot: cleanyourtv found that Android writes package
  state about ten seconds after a change, so a quick reboot can look exactly like this.
- **Live TV:** it's `com.tcl.tv`. It accepts `ACTION_VIEW` on
  `TvContract.buildChannelUriForPassthroughInput(<input id>)`, so set `live_tv_package=com.tcl.tv`.
  The inputs are `com.tcl.tvinput` (tuner) and `com.tcl.tvpassthrough` (HDMI, AV).
- **Remote keys:**
  - The colour keys are scancodes 398–401 → `PROG_RED … PROG_BLUE` (183–186), Input is 471 →
    `TV_INPUT`, and Settings is injected by TCL as keycode 4077.
  - **The Netflix button (scancode 762) is `KEY_UNKNOWN` and dropped by TCL's window manager
    before any app or accessibility service, so it can't be remapped.**
  - Held keys send one down and one up, **no repeats**, so holds are detected with a timer.
  - `EventHub` lines in logcat show raw scancodes without root (`tools/scan_key.py`);
    `getevent` output is unusable.
- **Screen saver:** `com.android.systemui/.Somnambulator` is exported. Starting it runs the
  selected screen saver, and starting it again while one runs toggles it off.
- **Standby:** `GLOBAL_ACTION_LOCK_SCREEN` from an accessibility service puts the TV into
  standby (suspend to RAM).
- **Clock in standby:** in standby a TextClock's minute timer stops, and Home can show a stale
  time on wake until it is refreshed. The launcher refreshes the clock on resume, on focus, on
  `DREAMING_STOPPED`, on `SCREEN_ON` and on TCL's `com.tcl.STR_RESUME_COMPLETED`.
- **Wi-Fi SSID:** Android 9 shows the Wi-Fi SSID only to apps holding location
  (`build_and_install.py` grants it).
- **Package lists:** `pm list packages -d` can print duplicate lines, so compare unique sets.
- **Sleep timer:** the screen-saver delay in Settings offers only fixed steps (5 min, 15 min,
  …).

## Building on Windows

- **Paths:** the Android build tools fail on non-ASCII characters in the project path, so
  `build_and_install.py` builds in `BUILD_DIR` (default `~/tvhome-build`). This also keeps
  about 1 GB of Gradle output out of synced folders.
- **Certificates:** behind HTTPS-inspecting antivirus software, Gradle needs
  `-Djavax.net.ssl.trustStoreType=Windows-ROOT` (the script sets it on Windows).
- **sdkmanager:** PowerShell can't pipe `y` into `sdkmanager --licenses`; use `yes |` in Git
  Bash.

## Known issues and open questions

- **Rendering cost on this GPU** (from cleanyourtv's BeyondTV2 V660 notes): `setClipToOutline`
  forces a layer per frame (83 % janky frames in their test), `clipPath` falls back to software,
  and `shadowRadius` on text is expensive. This launcher uses `setClipToOutline` on the dock tiles
  and the doorbell card. On the test TV it feels smooth, but it hasn't been measured with
  `dumpsys gfxinfo`. If Home stutters on your set, replace it with a pre-built rounded `Path`
  and a `BitmapShader`.
- **Key repeats with the key helper on:** on a TCL Google TV build a bound key-filtering
  accessibility service dropped key repeats (holding volume up stepped once). This hasn't been
  checked on the test TV, so hold volume up after enabling *Home: colour buttons*.
- **PiP over playing video:** a second video decoder next to a playing app made YouTube stall on
  another TCL (cleanyourtv's Frigate Glance). If the doorbell PiP makes playback stutter, set
  `on_person=none`.
- **Doorbell RTSP sessions:** every app kill or reinstall leaves a stale session on the
  Reolink for a few minutes. The tile reconnects by itself (watchdog plus backoff); be patient
  after a reinstall.
- **Wake-on-LAN:** the ring automation sends a magic packet first. Whether the set wakes from
  deep standby depends on the model and its *Wake on LAN / network standby* setting.
