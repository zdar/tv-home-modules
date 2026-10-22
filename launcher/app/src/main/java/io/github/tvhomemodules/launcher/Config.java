package io.github.tvhomemodules.launcher;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Settings from res/values/config.xml, overridden by
 *  /sdcard/Android/data/io.github.tvhomemodules.launcher/files/launcher.properties. */
final class Config {
    final List<String> dock;   // row 1, colour buttons
    final List<String> dock2;  // row 2
    final Set<String> hidden;
    final File wallpaper;      // null = black
    final String liveTvPackage;
    final String doorbellUrl;  // rtsp://user:password@host:554/... or empty
    final String doorbellFullUrl; // full-screen stream; empty = same as doorbellUrl
    final int doorbellHoldKey;   // keycode to hold for the full-screen doorbell, 0 = off
    final long doorbellHoldMs;
    // Doorbell events (Events.java)
    final long doorbellAutoCloseMs; // close the full-screen doorbell after this long without activity, 0 = never
    final long pipAutoCloseMs;
    // Art screen saver
    final long artMinMs, artMaxMs;  // a new painting every min..max
    final long artStandbyMs;        // standby after the screen saver has run this long, 0 = never
    final int artUntilYear;         // latest year of the paintings shown      // picture-in-picture closes after this long without doorbell events
    // Home Assistant lights panel (hold red)
    final String haUrl, haToken, haLabel, haTvArea, haMotionLabel;
    final List<String> haRooms;   // room order nearest the TV first
    final int haHoldKey;
    final int saverHoldKey;      // hold to start the screen saver now (185 = yellow, 0 = off)
    final boolean onvifEnabled;
    final int onvifPort;
    final int eventPort;         // Home Assistant backup listener
    final String eventToken;     // empty = HA listener off
    private final Properties props;
    final String signature;    // changes when anything that affects the app list changes

    private Config(Properties p, Context c, File dir) {
        dock = split(p.getProperty("dock", c.getString(R.string.dock_default)));
        dock2 = split(p.getProperty("dock2", c.getString(R.string.dock2_default)));
        hidden = new HashSet<>(split(p.getProperty("hidden", c.getString(R.string.hidden_default))));
        String wp = p.getProperty("wallpaper", c.getString(R.string.wallpaper_default)).trim();
        File w = null;
        if (!wp.isEmpty() && dir != null) {
            File f = wp.startsWith("/") ? new File(wp) : new File(dir, wp);
            if (f.isFile()) w = f;
        }
        wallpaper = w;
        liveTvPackage = p.getProperty("live_tv_package", c.getString(R.string.live_tv_package)).trim();
        doorbellUrl = p.getProperty("doorbell_url", "").trim();
        doorbellFullUrl = p.getProperty("doorbell_fullscreen_url", "").trim();
        doorbellHoldKey = intProp(p, "doorbell_hold_key", c.getResources().getInteger(R.integer.doorbell_hold_key));
        doorbellHoldMs = intProp(p, "doorbell_hold_ms", c.getResources().getInteger(R.integer.doorbell_hold_ms));
        doorbellAutoCloseMs = intProp(p, "doorbell_auto_close_s", 60) * 1000L;
        pipAutoCloseMs = Math.max(3, intProp(p, "pip_auto_close_s", 10)) * 1000L;
        artMinMs = Math.max(1, intProp(p, "art_change_min", 5)) * 60_000L;
        artMaxMs = Math.max(1, intProp(p, "art_change_max", 15)) * 60_000L;
        artStandbyMs = Math.max(0, intProp(p, "art_standby_min", 60)) * 60_000L;
        artUntilYear = intProp(p, "art_until_year", 1910);
        haUrl = p.getProperty("ha_url", "").trim();
        haToken = p.getProperty("ha_token", "").trim();
        haLabel = p.getProperty("ha_label", "TV").trim();
        haTvArea = p.getProperty("ha_tv_area", "").trim();
        haMotionLabel = p.getProperty("ha_motion_label", "Motion").trim();
        haRooms = split(p.getProperty("ha_rooms", ""));
        haHoldKey = intProp(p, "ha_hold_key", 183); // 183 = PROG_RED, 0 = off
        saverHoldKey = intProp(p, "screensaver_hold_key", 185); // 185 = PROG_YELLOW, 0 = off
        onvifEnabled = !"false".equalsIgnoreCase(p.getProperty("onvif", "true").trim());
        onvifPort = intProp(p, "onvif_port", 8000);
        eventPort = intProp(p, "event_port", 8765);
        eventToken = p.getProperty("event_token", "").trim();
        props = p;
        signature = dock + "|" + dock2 + "|" + hidden;
    }

    static Config load(Context c) {
        Properties p = new Properties();
        File dir = c.getExternalFilesDir(null);
        if (dir != null) {
            File f = new File(dir, "launcher.properties");
            if (f.isFile()) {
                try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                    p.load(r);
                } catch (Exception ignored) {
                }
            }
            writeExample(c, dir);
        }
        return new Config(p, c, dir);
    }

    /** What a doorbell event does: "doorbell" (full screen, like holding blue),
     *  "pip" (small live picture in the corner over any app) or "none".
     *  Defaults: ring -> doorbell, person -> pip; the rest are only received and logged. */
    String eventAction(String event) {
        String def = Events.RING.equals(event) ? "doorbell" : Events.PERSON.equals(event) ? "pip" : "none";
        return props.getProperty("on_" + event, def).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static int intProp(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Keeps a commented template next to where the real file goes. */
    private static void writeExample(Context c, File dir) {
        File ex = new File(dir, "launcher.properties.example");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(ex), StandardCharsets.UTF_8)) {
            w.write("# Copy to launcher.properties in this folder and edit. Read on every return to Home.\n");
            w.write("# Dock row 1 = red, green, yellow, blue buttons (package names: adb shell pm list packages)\n");
            w.write("dock=" + c.getString(R.string.dock_default) + "\n");
            w.write("# Dock row 2\n");
            w.write("dock2=" + c.getString(R.string.dock2_default) + "\n");
            w.write("# Image in this folder (jpg/png), or an absolute path; empty = black\n");
            w.write("wallpaper=wallpaper.jpg\n");
            w.write("# Packages to leave out of the grid\n");
            w.write("hidden=" + c.getString(R.string.hidden_default) + "\n");
            w.write("# Live doorbell tile on Home (RTSP sub-stream); empty = no tile. Set it with tools/set_doorbell.py\n");
            w.write("#doorbell_url=rtsp://USER:PASSWORD@DOORBELL_IP:554/h264Preview_01_sub\n");
            w.write("# Optional sharper stream for the full-screen view (default: same as doorbell_url)\n");
            w.write("#doorbell_fullscreen_url=rtsp://USER:PASSWORD@DOORBELL_IP:554/h264Preview_01_main\n");
            w.write("# Hold this key to open/close the doorbell full screen from any app (186 = blue, 0 = off)\n");
            w.write("doorbell_hold_key=" + c.getResources().getInteger(R.integer.doorbell_hold_key) + "\n");
            w.write("doorbell_hold_ms=" + c.getResources().getInteger(R.integer.doorbell_hold_ms) + "\n");
            w.write("# Doorbell events. Action per event: doorbell (full screen, like holding blue),\n");
            w.write("# pip (small live picture in the corner over any app) or none\n");
            w.write("on_ring=doorbell\non_person=pip\non_motion=none\non_pet=none\non_vehicle=none\n");
            w.write("# Picture-in-picture closes this many seconds after the last doorbell event\n");
            w.write("pip_auto_close_s=10\n");
            w.write("# Art screen saver: a new painting every 5-15 minutes, standby after 60 minutes (0 = never)\n");
            w.write("art_change_min=5\nart_change_max=15\nart_standby_min=60\n");
            w.write("# Home Assistant lights panel: hold red (183) from any app. Token: tools/set_ha.py\n");
            w.write("#ha_url=http://HA_IP:8123\n#ha_token=...\n");
            w.write("# Lights with this HA label (all lights if none has it), TV's room first, then its floor\n");
            w.write("ha_label=TV\nha_tv_area=Living Room\nha_hold_key=183\n");
            w.write("# Rooms (HA areas) in this order; others follow: same floor, then other floors (dimmed)\n");
            w.write("ha_rooms=Living Room,Kitchen,Bathroom,Garden,Hall\n");
            w.write("# Lights with this label are motion-controlled: shown smaller, last in their room\n");
            w.write("ha_motion_label=Motion\n");
            w.write("# Hold this key to start the screen saver now (185 = yellow, 0 = off)\n");
            w.write("screensaver_hold_key=185\n");
            w.write("# Paintings up to this year (1910 = old masters to Post-Impressionism)\n");
            w.write("art_until_year=1910\n");
            w.write("# Close the full-screen doorbell after this many seconds without doorbell activity\n");
            w.write("# (ring/motion/person) or remote presses; 0 = never. Woken by a ring -> back to standby.\n");
            w.write("doorbell_auto_close_s=60\n");
            w.write("# Source 1: ONVIF subscription to the doorbell (login taken from doorbell_url)\n");
            w.write("onvif=true\nonvif_port=8000\n");
            w.write("# Source 2: Home Assistant backup, POST http://<tv>:8765/event  (set by tools/setup_events.py)\n");
            w.write("event_port=8765\n#event_token=...\n");
        } catch (Exception ignored) {
        }
    }
}
