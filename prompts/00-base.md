# Module 00 — Base: debloat + Apple-TV-style Home

*Required. The optional modules ([screen saver](10-screensaver.md), [doorbell](20-doorbell.md),
[Home Assistant](30-home-assistant.md)) build on it.*

This module follows the structure and the hard-won safety rules of
[cleanyourtv's PROMPT.md](https://github.com/Gathin23/cleanyourtv/blob/main/PROMPT.md) by Gathin
(MIT). The Home screen differs: a two-row dock with colour-button rings, slots you edit on the TV,
and a key-helper accessibility service that the optional modules use.

## How to use

1. On the TV: Settings → Device Preferences → About → press OK on **Build** 7 times, go back →
   Developer options → **USB debugging** on (and Network/ADB debugging, if your TV has it). Keep
   the TV on.
2. Clone this repo on a computer on the same network as the TV and open Claude Code (or another
   coding assistant with a shell) in the repo folder.
3. Paste everything below the line, fill in the `<…>` fields, and add the text of any optional
   module you want **under it in the same message**. You can also run an optional module later
   on its own.

Expect three or four checklist questions: which apps you use, what goes in the dock, whether to
keep the voice assistant, Chromecast and firmware updates. Everything is reversible.

---

I want to debloat my Android TV and replace its launcher with a clean Apple TV 4K style Home
screen. My TV model is: `<MODEL>`. Its IP address is: `<TV IP, or "find it">`.

This repo already holds the launcher source (`launcher/`), the helper scripts (`tools/`),
`restore.py` and `build_and_install.py`: reuse them rather than writing new ones, and change
the launcher only where my answers need it. Work on the real device, step by step, and verify
everything with screenshots pulled from the TV. Ask me before anything that depends on my
preferences. Never type, store or print a password or token of mine: the scripts that need
one ask me for it in my own terminal.

### Connect

- Use `tools/tv.py` for all ADB work (`TV_HOST=<ip>`; it uses `ADB`, `platform-tools/` in the
  repo, or `adb` on PATH). Download Google's platform-tools only after asking me.
- Connect on port 5555, and tell me when the *Allow USB debugging?* prompt will appear on the TV
  so I can accept it.
- If 5555 stays closed, the TV may offer only **Wireless debugging** (Google TV sets often do).
  Ask me for the pairing code and port, then run `adb pair`, `adb connect` and `adb tcpip 5555`.
  Android switches Wireless debugging off whenever Wi-Fi drops.
- Some firmware runs network ADB on another fixed port (a projector used 5877). If 5555 refuses
  and nothing advertises Wireless debugging, scan the TV's own TCP ports and try each open one
  with `TV_PORT`.
- If the TV turns out not to be Android TV / Google TV (no ADB port at all, or it identifies as
  Tizen, webOS, Roku OS, VIDAA or SmartCast), stop and point me to
  <https://cleanyour.tv/not-android>, which turns off the tracking with the remote. Nothing here
  applies to it.
- If `adb install` hangs on Android 14, the TV is waiting for an on-screen confirmation. Push
  the APK and run `pm install -r` instead.
- On macOS, a stock adb that reports "No route to host" while the port is open is being blocked
  by Local Network privacy. Use the Python `adb-shell` package instead.

### Back up first

- Before changing anything, save to `backup/<timestamp>/`:
  - package lists (all, system, third-party, disabled), getprop and the global, secure and
    system settings;
  - the HOME handlers (`cmd package query-activities -a android.intent.action.MAIN -c
    android.intent.category.HOME`);
  - `wm size`, `wm density` and `settings get secure display_density_forced`.
- Ask me what is plugged into the USB ports, and separately whether the network comes through a
  **USB Ethernet adapter**: some boxes cut USB power while USB debugging is on. If it does, check
  `dumpsys connectivity | grep -o 'Transports: [A-Z]*'` before anything else (`WIFI` on a TV I
  call wired means it fell back, and ADB runs over Wi-Fi), and plan to finish in one sitting.
  `dumpsys usb | grep host_connected` reading `false` is no proof the adapter is gone: some sets
  read false with it still up as `eth0`.
- Note what the TV's sound goes through (built-in speakers, a soundbar or receiver over
  ARC/eARC). Never touch HDMI-CEC or audio packages or settings. After the debloat, confirm ARC
  still works with `dumpsys hdmi_control` (`mArcEstablished`, `mSystemAudioActivated`).

### Debloat

- List the installed packages and sort them into:
  - manufacturer telemetry and analytics;
  - content and ad services (recommendation rows, app stores I don't use, "channels" apps);
  - background extras (cleaners, cast or share duplicates, print spooler);
  - preloaded apps;
  - streaming apps.
- Disable with `tools/disable.py <pkg>…`: one `pm disable-user --user 0` per shell call, each
  logged in `backup/disabled-by-us.txt`, which `restore.py` reads. **Never uninstall system
  packages.**
- **Never disable what the TV needs:**
  - the TV/tuner app and input services (HDMI, AV, tuner), the input/source menu;
  - remote-control, key-mapping and Bluetooth-pairing services, the on-screen keyboard;
  - Google Play Store and Play Services.
- **Never disable Android's core:**
  - the permission controller, package installer, ext services, ext shared and module metadata
    (`com.android.*` or `com.google.android.*`; ext services and ext shared also as the bare
    `android.ext.services` and `android.ext.shared`);
  - `com.google.android.overlay.modules*`;
  - `com.android.providers.*`, except calendar, contacts, user dictionary and blocked numbers;
  - `com.android.systemui`, `com.android.shell`, `com.android.phone`, `com.android.settings`,
    `com.android.tv.settings` and `android`;
  - the TV middleware: `com.cltv.mal`, `com.mediatek.tis`, `com.mediatek.tv.service`,
    `com.mediatek.tv.agent`.

  `tools/disable.py` refuses these. A Mi TV Stick never booted again after losing the first
  six ([cleanyourtv #884](https://github.com/Gathin23/cleanyourtv/issues/884)), and a Haier
  set restarted in a loop with `com.cltv.mal` off
  ([cleanyourtv #1578](https://github.com/Gathin23/cleanyourtv/issues/1578)).
- When a manufacturer package name doesn't say what it does, run `tools/whatis.py <pkg>`
  (dumpsys, never starts it), and ask me if it's still unclear.
- **Never launch a factory or service package** (`*factory*`, `*fac.menu*`, `*.at.*`, a
  factorymenu), not even to see what it is. On some boards that flips a factory-mode flag that
  breaks the Input key and CEC.
- Before touching streaming apps, the voice assistant, Chromecast, Miracast or the firmware
  update service, ask me in one checklist which ones I use. Disable only the ones I don't tick.
- Disable in small batches and take a screenshot after each one. A batch can take something
  load-bearing with it, and only small batches show which package it was.
- If `pm disable-user` is refused for **every** package (some firmware, such as Fire OS, does
  this), stop and tell me. Check with `pm list packages -d`, not the exit code.
- After the last batch, wait about 15 seconds (or run `sync`) before rebooting: Android writes
  package state to disk about ten seconds after a change, so a reboot sent straight after the
  batch boots with the old state, and what came back reads as the firmware re-enabling packages
  it never re-enabled. Then reboot once and compare `pm list packages -d` (unique lines) with
  the log. If the firmware really re-enables some packages at every boot, note them in the
  README instead of fighting them. If one of them is an app tile, hide it in the launcher
  (`hidden=`).
- Offer ad-blocking Private DNS (Android 9+) as an optional extra. First ask whether my router
  already filters or routes by domain through its own DNS (Pi-hole, AdGuard Home, a VPN with
  domain lists): Private DNS goes around it, and blocking on the router is better there.
  `settings put global private_dns_specifier dns.adguard-dns.com`, then
  `settings put global private_dns_mode hostname`.
  - It doesn't block YouTube ads.
  - Check that an app still plays afterwards.
  - The undo is `restore.py --dns`.

### Launcher

- **Before building**, check:
  - `getprop ro.build.version.sdk` is at least the launcher's `minSdk` (26 = Android 8.0);
  - sideloading works;
  - the TV accepts a third-party HOME app. Some China-market sets refuse every sideload
    (`INSTALL_FAILED_VERIFICATION_FAILURE`) or any unsigned HOME app.

  If any check fails, do only the debloat and tell me plainly that the launcher can't be
  installed.
- **Build and install:** `python build_and_install.py`. It needs JDK 17+, the Android SDK and
  Gradle 8.9 (`SDK_DIR`, `JAVA_HOME`, `GRADLE`). It builds in `BUILD_DIR`, outside the repo,
  because the Android tools break on non-ASCII paths. Ask me before downloading the SDK or JDK.
- **The Home design** (Apple TV 4K style; already in `launcher/`):
  - **Look:** pitch-black background, no blur.
  - **Top left:** greeting, large thin clock and date in Outfit (OFL). The clock, date and
    greeting are refreshed whenever Home is shown again: on resume, on window focus, at the end
    of the screen saver, on screen on, and on time and zone changes. A TextClock's own ticker
    doesn't run in standby.
  - **Top right:** frosted pills:
    - **Inputs**, a glass dropdown listing Live TV, HDMI and AV through the TV input framework;
    - the network (SSID or *Ethernet*);
    - Settings.

    The ends of the pill row don't fall through.
  - **Above the dock:** the focused dock app's name in large light type, with an ambient glow in
    a colour sampled from its banner.
  - **Dock row 1:** four full-size tiles with thick **red / green / yellow / blue rings outside
    the card**. On Home, a short press of a colour button opens that slot's app, acting on
    release. Outside Home the colour keys keep their normal job (HbbTV, teletext). A small pill
    in the ring's colour on its bottom edge names that button's **hold** action, once an optional
    module defines one.
  - **Dock row 2:** eight half-height slots (two per tile column). Empty slots show **"+"**.
  - **No band behind the dock.**
  - **Dock editing on the TV:**
    - OK on "+" opens the app grid in pick mode (Back cancels).
    - **Holding OK** on a filled slot opens *Replace app / Remove from dock / Cancel*, with
      Cancel focused.
    - Edits persist on the TV.
  - **Below the dock:** a 4-column grid of all the other apps, alphabetical.
  - **Highlight and focus:**
    - one translucent glass highlight springs between targets (no per-tile borders);
    - the focused tile scales up slightly and shows its name;
    - the last focus is remembered.
  - **Navigation:** all D-pad handling is in code, so Up/Down move exactly one row: pills ↓
    (optional doorbell tile) ↓ row 1 ↓ row 2 ↓ grid. Back returns to the dock; Home on Home
    returns to row 1.
  - **Keep it light:** no blur, no network polling on Home.
- **Ask me:**
  - which four apps go on the colour buttons, in red-green-yellow-blue order;
  - which apps go in row 2 (empty slots are fine);
  - whether I want a wallpaper.

  Put the answers in `launcher/app/src/main/res/values/config.xml` (`dock_default`,
  `dock2_default`, `hidden_default`, `live_tv_package`) or in `launcher.properties` on the TV
  (see the README).
- **Android 11+:** the manifest must keep its `<queries>` for `LEANBACK_LAUNCHER`, or the grid
  comes up empty.
- **Home takeover:**
  1. `python build_and_install.py --set-home` (`cmd package set-home-activity`).
  2. Disable the stock launcher last, once ours works.
  3. Confirm with `dumpsys window windows | grep mFocusedApp` (or `dumpsys activity activities |
     grep mFocusedApp`) after pressing Home. `set-home-activity` can print `Success` and change
     nothing until the stock launcher is disabled.

  On Google TV:
  - The setup wizard (`com.google.android.tungsten.setupwraith`) also claims HOME.
  - Before disabling it, run `dumpsys wifi | grep -o 'cname=[^ ]*'`: a Wi-Fi network saved
    during setup belongs to it and is deleted with it.
  - The stock launcher also answers the app, Input and Settings buttons. Ask me to press each
    one afterwards.
- **CVTE boards** (any `com.cvte.*` package): leave the stock launcher enabled. Make ours Home
  with `set-home-activity`, reboot once, and confirm Home and ADB come back. Don't enable the key
  helper in the same pass.

### The key helper (used by the optional modules)

The launcher includes an accessibility service, *Home: colour buttons* (`KeyHelperService`).
The optional modules use it to:

- catch **held** colour buttons from any app;
- draw overlays (doorbell picture-in-picture, the lights panel).

How it works:

- It is timer-based: held keys send no repeats to an accessibility service on many TVs.
- Short presses pass through untouched.
- Android waits only ~0.5 s for the service's answer, so it can't reliably hide a key from a
  window it just opened.

If I take no optional module, leave it off. Otherwise:

- **Don't switch it on yourself.** Open Settings → Accessibility and tell me what to enable.
- Afterwards, ask me to **hold volume up** and check that the volume still ramps. A
  key-filtering service drops key repeats on some TCL builds (cleanyourtv
  `docs/devices/tcl-55p71b-g09-key-repeat.md`). If it does here, tell me which I'm trading.
- A button the TV logs as `KEY_UNKNOWN` can't be used at all (e.g. the Netflix button on TCL
  Android 9: scancode 762, dropped before any app). Find keys with `tools/scan_key.py` (TCL logs
  scancodes through `EventHub` in logcat); `getevent` output is often unusable.

### Verify

- After each change, inject keys and look at a screenshot:
  `python tools/tv.py --key DPAD_RIGHT DPAD_DOWN`, then
  `python tools/tv.py --screenshot shots/01.png`. Fix what you see before moving on.
- Live TV, HDMI, some streaming apps and any video plane (SurfaceView) come out black or empty.
  That's the TV, not a broken debloat: check `mFocusedApp` instead.
- If the screen looks enlarged or clipped, read `wm size` and `wm density` before touching the
  launcher grid.
- Anything that involves **held** keys has to be tested by me with the **real remote**. Injected
  keys aren't proof.
- For long unattended tests, run `python tools/monitor.py` (passive: no keys, no screenshots).
  It logs whether the TV is awake, dreaming or answering, the front app and the launcher's log
  lines.

### Finish

- Update `README.md` with what was disabled and kept on this TV, how to re-enable one app
  (`restore.py <pkg>`), how to undo everything (`restore.py --launcher`) and how to change the
  dock or wallpaper.
- **Close network ADB:**
  1. If the TV has Wireless debugging, note its port from `adb mdns services`
     (`_adb-tls-connect._tcp`) first: it has a port of its own and takes a new one each time it
     starts.
  2. Switch off Wireless debugging and Network/ADB debugging (whichever the TV has), and USB
     debugging too unless I ask to keep it.
  3. Revoke the USB debugging authorisations.
  4. Check the Wireless debugging port with `python tools/adb-closed.py <tv ip> <port>` straight
     away, before the reboot.
  5. Reboot, confirm with `python tools/adb-closed.py <tv ip>` that 5555 **refuses** the
     connection, and confirm Wireless debugging is still off. If 5555 still answers while USB
     debugging stays on, that toggle keeps it open on this TV: switch it off too and check
     again.

  Only the tool saying "refused" counts, not how the toggles look or a connection that dropped
  (on macOS, `nc`'s "No route to host" proves nothing). Port 5555 is plaintext, and with
  `ro.adb.secure=0` (or unset) any device on the LAN can get a shell. If the network comes
  through USB Ethernet, unplug and replug the adapter once debugging is off, and have me confirm
  the TV is on Ethernet, not Wi-Fi.
