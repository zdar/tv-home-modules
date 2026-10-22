package io.github.tvhomemodules.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Remote-button helper and host of the doorbell events.
 *  - HOLD one button (blue by default) to open or close the full-screen doorbell
 *    from any app. A short press is passed on untouched, as is every other key;
 *    only the release of a long hold is kept. It reads no screen content.
 *  - Starts Events (ONVIF + Home Assistant); a ring opens the same view.
 *  (The Netflix button can't be used: TCL's window manager drops its
 *  KEY_UNKNOWN before accessibility services ever see it.) */
public class KeyHelperService extends AccessibilityService {
    private static final String TAG = "KeyHelper";
    private static final long JUST_WOKEN_MS = 90_000;
    private static KeyHelperService instance;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int holdKey, haHoldKey, saverHoldKey, heldKey;
    private long holdMs;
    private boolean holding;
    // Home Assistant lights panel (hold red)
    private HaClient haClient;
    private HaPanel haPanel;
    private boolean pausedMedia;
    // "Was the TV asleep until this ring?" -> put it back to sleep when the view times out
    private long lastKeyAt, lastScreenOnAt;
    private boolean openedFromStandby;

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            lastScreenOnAt = SystemClock.elapsedRealtime();
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        reload();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction("tcl.sys.intent.action.STR_RESUME_COMPLETED"); // TCL resume from suspend
        registerReceiver(screenOn, f);
        haClient = new HaClient(this, (lights, st) -> {
            if (haPanel != null) haPanel.onLights(lights, st);
        });
        haPanel = new HaPanel(this, haClient);
        haClient.start(); // one live WebSocket, so the panel opens with current states
        // Doorbell events (ONVIF + Home Assistant) live here: this service runs
        // for as long as it is switched on in Accessibility, across reboots.
        Events.start(this, (action, event) -> {
            if ("doorbell".equals(action)) {
                hidePip(); // one decoder for the full-screen view
                ClockDream.wakeAll(); // the screen saver would cover it
                openDoorbell(true);
            } else if ("pip".equals(action)) {
                showPip();
            }
        });
    }

    // ------------------------------------------------------------------ picture-in-picture

    private android.view.View pipView;
    private Doorbell pipBell;
    private long pipCloseMs;

    private final Runnable pipIdle = () -> {
        if (Events.anyActive()) { // person / motion still reported: keep it
            restartPipIdle();
            return;
        }
        hidePip();
    };

    private void restartPipIdle() {
        ui.removeCallbacks(pipIdle);
        ui.postDelayed(pipIdle, pipCloseMs);
    }

    /** Any doorbell event while the PiP is up keeps it up. */
    static void pipActivity() {
        KeyHelperService s = instance;
        if (s != null && s.pipView != null) s.restartPipIdle();
    }

    /** Small live doorbell picture (low-res stream) in the bottom-right corner over
     *  any app. An accessibility overlay: no focus taken, nothing paused. */
    private void showPip() {
        Config c = Config.load(this);
        PowerManager pm = getSystemService(PowerManager.class);
        // Not over Home (its own doorbell tile is on screen), not over the full-screen view, not in standby
        if (c.doorbellUrl.isEmpty() || DoorbellActivity.isShowing() || MainActivity.homeVisible
                || (pm != null && !pm.isInteractive())) return;
        pipCloseMs = c.pipAutoCloseMs;
        if (pipView != null) {
            restartPipIdle();
            return;
        }
        android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
        pipView = android.view.LayoutInflater.from(this).inflate(R.layout.view_pip, null);
        android.widget.TextView status = pipView.findViewById(R.id.pip_status);
        status.setTypeface(getResources().getFont(R.font.outfit_regular));
        float d = getResources().getDisplayMetrics().density;
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                Math.round(352 * d), Math.round(264 * d),
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lp.x = Math.round(40 * d);
        lp.y = Math.round(40 * d);
        wm.addView(pipView, lp);
        pipBell = new Doorbell(this, pipView.findViewById(R.id.pip), pipView.findViewById(R.id.pip_video), status, true);
        pipBell.start(c.doorbellUrl); // the light sub-stream
        pipView.setAlpha(0f);
        pipView.animate().alpha(1f).setDuration(200).start();
        restartPipIdle();
        Log.i(TAG, "PiP shown");
    }

    private void hidePip() {
        ui.removeCallbacks(pipIdle);
        if (pipView == null) return;
        if (pipBell != null) pipBell.release();
        pipBell = null;
        try {
            getSystemService(android.view.WindowManager.class).removeView(pipView);
        } catch (Exception ignored) {
        }
        pipView = null;
        Log.i(TAG, "PiP hidden");
    }

    @Override
    public void onDestroy() {
        if (haPanel != null) haPanel.hide();
        if (haClient != null) haClient.stop();
        hidePip();
        Events.stop();
        try {
            unregisterReceiver(screenOn);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private void reload() {
        Config c = Config.load(this);
        holdKey = c.doorbellHoldKey;
        haHoldKey = c.haHoldKey;
        saverHoldKey = c.saverHoldKey;
        holdMs = c.doorbellHoldMs;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    // A timer, not key repeats: accessibility services get no repeats (Android
    // makes those later, per window), and this TCL remote sends none either.
    private long lastHoldAt;

    // Held blue -> doorbell full screen, held red -> lights panel, held yellow -> screen saver
    private final Runnable holdReached = () -> {
        holding = true;
        long now = SystemClock.elapsedRealtime();
        if (now - lastHoldAt < 3000) return; // a second hold right after the first: ignore
        lastHoldAt = now;
        if (heldKey == holdKey) toggle();
        else if (heldKey == haHoldKey) {
            if (DoorbellActivity.isShowing()) return;
            haPanel.toggle();
        } else if (heldKey == saverHoldKey) {
            startScreenSaver();
        }
    };

    /** Start the selected screen saver right now (SystemUI's exported "Somnambulator"). */
    private void startScreenSaver() {
        if (DoorbellActivity.isShowing()) return;
        hidePip();
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new android.content.ComponentName("com.android.systemui",
                            "com.android.systemui.Somnambulator"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "screen saver: " + e.getMessage());
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        lastKeyAt = SystemClock.elapsedRealtime(); // someone is at the remote
        // While the lights panel is open it gets every key (except Home)
        if (haPanel != null && haPanel.isShowing()) return haPanel.onKey(e);
        int k = e.getKeyCode();
        boolean holdable = (holdKey != 0 && k == holdKey) || (haHoldKey != 0 && k == haHoldKey)
                || (saverHoldKey != 0 && k == saverHoldKey);
        if (!holdable) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                holding = false;
                heldKey = k;
                ui.removeCallbacks(holdReached);
                ui.postDelayed(holdReached, holdMs);
            }
            return holding; // the press itself always reaches the app
        }
        ui.removeCallbacks(holdReached);
        if (holding) { // release of a long hold: nobody else sees it
            holding = false;
            return true;
        }
        return false;
    }

    /** Holding the key: open, or close if it is already showing. */
    private void toggle() {
        reload();
        if (DoorbellActivity.closeIfShowing()) return; // resume happens in doorbellClosed()
        hidePip();
        openDoorbell(false);
    }

    /** The one "show the doorbell" action, shared by the held key and by a ring.
     *  Pauses whatever plays (resumed when it closes) and, for a ring, wakes the screen. */
    private void openDoorbell(boolean fromEvent) {
        if (DoorbellActivity.isShowing()) return;
        openedFromStandby = false;
        if (fromEvent) {
            PowerManager pm = getSystemService(PowerManager.class);
            long now = SystemClock.elapsedRealtime();
            boolean asleep = pm != null && !pm.isInteractive();
            // Woken just now (e.g. Wake-on-LAN from Home Assistant) and nobody has touched the remote since
            boolean justWoken = lastScreenOnAt > 0 && now - lastScreenOnAt < JUST_WOKEN_MS && lastKeyAt < lastScreenOnAt;
            openedFromStandby = asleep || justWoken;
            if (asleep) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, "atvlauncher:doorbell");
                wl.acquire(10_000);
            }
            Log.i(TAG, "ring: asleep=" + asleep + " justWoken=" + justWoken);
        }
        AudioManager am = getSystemService(AudioManager.class);
        pausedMedia = !openedFromStandby && am != null && am.isMusicActive();
        if (pausedMedia) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        startActivity(new Intent(this, DoorbellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** Called when the full-screen doorbell goes away (hold again, Back/OK, or timeout).
     *  Timed out after a ring woke the TV -> back to standby; otherwise resume playback. */
    static void doorbellClosed(boolean autoClosed) {
        KeyHelperService s = instance;
        if (s == null) return;
        if (autoClosed && s.openedFromStandby) {
            s.openedFromStandby = false;
            Log.i(TAG, "doorbell timed out after a wake-up ring: back to standby");
            s.ui.postDelayed(() -> s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN), 500);
            return;
        }
        s.openedFromStandby = false;
        if (!s.pausedMedia) return;
        s.pausedMedia = false;
        // Give the app underneath a moment to come back to the front first
        s.ui.postDelayed(() -> s.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY), 800);
    }

    /** Put the TV to standby (screen saver timeout). Needs this service switched on. */
    static void standby() {
        KeyHelperService s = instance;
        if (s != null) s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    private void mediaKey(int code) {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
