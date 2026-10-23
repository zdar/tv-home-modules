# Module 10 — Art screen saver

*Optional. Needs [Module 00](00-base.md) (the launcher APK contains the screen saver).*

A Frame-TV-style screen saver: public-domain and CC0 paintings from museum open-access
collections, a dim clock, standby after an hour. Hold **Yellow** to start it from any app.

Paste the text below the line after Module 00, or on its own once the base is installed.

---

Set up the art screen saver that is built into this repo's launcher (`ClockDream`,
*Home: art and clock*). Ask me about each preference below; the defaults are in brackets.

### What it does (already in `launcher/`; adjust only on my answers)

- **Sources:** public-domain and CC0 open-access APIs. No keys, no accounts.
  - **The Met:** European Paintings highlights plus the twelve main Impressionists and
    Post-Impressionists. Use `/public/collection/v1.1/search`; the old `/v1/search` is retired.
  - **Cleveland Museum of Art:** CC0 highlights.
  - **Art Institute of Chicago:** boosted public-domain works, when the API is up.
- **Period:** works up to `art_until_year` [1910]: old masters to Post-Impressionism, about
  365 works. The pool is built once per year setting and cached on the TV.
- **Picture:**
  - each painting fills the full screen height on black and is never cropped;
  - a new random one every `art_change_min`–`art_change_max` [5–15] minutes, no repeat of the
    last 80, with a 2 s crossfade;
  - a faint caption (artist, title, year, museum; 11 sp at 20 % opacity, fainter than the
    clock), always visible, stacked in the black side band at the painting's bottom edge and
    aligned towards it;
  - a tiny dim clock (30 %) at the top of the opposite band. Clock and caption swap sides with
    each painting against burn-in;
  - if the side band is under 150 dp, the painting is scaled down just enough to leave a
    40 dp strip under it, and the caption is one line there, right-aligned to the painting and just under it,
    with the clock at the left of the same strip. **Never put text over the painting:** on real paintings it was
    unreadable.

  Nothing else on screen.
- **Network:**
  - It downloads one image at a time, only while it runs, and keeps 25 cached, so it works
    offline.
  - It starts with a **cached** painting so something shows at once.
- **Standby:** after `art_standby_min` [60] minutes it puts the TV into standby
  (`GLOBAL_ACTION_LOCK_SCREEN` from the key helper); 0 = never.
- **Hold Yellow** (`screensaver_hold_key=185`) starts it from any app through SystemUI's
  exported `.Somnambulator`. Needs the key helper from Module 00.
  - Starting the dream while one runs toggles it off, so a second hold within 3 s is ignored.
  - The dream is **interactive** and handles keys itself. It ignores the key whose `downTime`
    is before the dream started (the held Yellow) and wakes on any later press. Otherwise
    releasing the hold ends it at once (a flash, a fade, back to Home).
- **Over the screen saver:** the doorbell module's ring and picture-in-picture still work.

### Steps

1. Ask me the period, change interval and standby time. Write any non-default values to
   `launcher.properties` (README → *Settings file*); rebuild only if the code has to change.
2. **Don't select the screen saver yourself.** Open Settings → Device Preferences →
   Screen saver and tell me to pick *Home: art and clock* and a start delay (5 min suits
   it; some TVs offer only fixed steps).
3. Verify:
   - Start it with `am start -n com.android.systemui/.Somnambulator` and take a screenshot: the
     dream is a normal window and screenshots fine.
   - Read the launcher's log line for each painting.
   - Then ask me to **hold Yellow** on the real remote, and to press a key to end it.
4. Long test: run `python tools/monitor.py` while I leave the TV idle. Expected: screen saver
   after the start delay, a new painting every few minutes, and standby after the set time (the
   TV stops answering on the network).
5. Add a *Screen saver* section to the README: how to select it, the hold key, and the four
   `art_*` settings.
