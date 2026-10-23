package io.github.tvhomemodules.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.TextView;
import android.widget.Toast;

/** Full-screen doorbell, opened from anywhere by the key helper or a ring. It runs
 *  in its own task, so closing it lands back in whatever app was in front.
 *  It closes itself after doorbell_auto_close_s without doorbell activity
 *  (ring / motion / person) or remote presses. */
public class DoorbellActivity extends Activity {
    private static DoorbellActivity current;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Doorbell bell;
    private int closeKey;
    private long autoCloseMs;
    private boolean autoClosed;

    static boolean isShowing() {
        return current != null;
    }

    static boolean closeIfShowing() {
        if (current == null) return false;
        current.finish();
        return true;
    }

    /** Something happened at the door: start the idle countdown again. */
    static void doorbellActivity() {
        if (current != null) current.restartIdle();
    }

    private final Runnable idle = () -> {
        if (Events.anyActive()) { // motion / person still in view: keep watching
            restartIdle();
            return;
        }
        autoClosed = true;
        finish();
    };

    private void restartIdle() {
        ui.removeCallbacks(idle);
        if (autoCloseMs > 0) ui.postDelayed(idle, autoCloseMs);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = this; // set early, so a second ring arriving meanwhile is not a second window
        // A ring may arrive with the screen off: come up over it
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        setContentView(R.layout.activity_doorbell);
        TextView status = findViewById(R.id.doorbell_status);
        status.setTypeface(getResources().getFont(R.font.outfit_regular));
        bell = new Doorbell(this, findViewById(R.id.doorbell), findViewById(R.id.doorbell_video), status, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        Config c = Config.load(this);
        if (c.doorbellUrl.isEmpty() && c.doorbellFullUrl.isEmpty()) {
            Toast.makeText(this, R.string.doorbell_offline, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        autoCloseMs = c.doorbellAutoCloseMs;
        restartIdle();
        // Full screen wants the high-resolution main stream; the tile's sub-stream is the fallback
        String main = c.doorbellFullUrl.isEmpty() ? Doorbell.mainStreamOf(c.doorbellUrl) : c.doorbellFullUrl;
        bell.start(main, c.doorbellUrl);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(idle);
        bell.release();
        // Leaving for any reason (another app, Home) closes it for good
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (current == this) current = null;
        KeyHelperService.doorbellClosed(autoClosed);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        restartIdle(); // someone is using the remote
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            // Only a press that started here: the release of the OK that opened
            // this view from Home must not close it again
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) closeKey = k;
            else if (e.getAction() == KeyEvent.ACTION_UP && closeKey == k) finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
