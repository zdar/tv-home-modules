package io.github.tvhomemodules.launcher;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Screen saver in the spirit of a "frame" TV: old-master paintings from museum
 *  open-access collections (ArtSource), a new one every 5-15 minutes with a slow
 *  crossfade, a caption that fades away, and a small clock that changes corner
 *  with each painting. After an hour the TV goes to standby.
 *  Doorbell pop-ups keep working over it; a ring ends it (wakeAll). */
public class ClockDream extends DreamService {
    private static final String TAG = "ArtDream";
    private static ClockDream current;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ArtSource source;
    private ImageView[] views;
    private int front;
    private View caption;
    private TextView title, meta;
    private TextClock clock;
    private boolean clockRight = true;
    private long minMs, maxMs;

    static void wakeAll() {
        if (current != null) current.wakeUp();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Interactive, so keys come to dispatchKeyEvent: the press that started the
        // screen saver (yellow hold) is ignored, any later press ends it
        setInteractive(true);
        setFullscreen(true);
        setContentView(R.layout.dream_clock);
        views = new ImageView[]{findViewById(R.id.art_a), findViewById(R.id.art_b)};
        caption = findViewById(R.id.art_caption);
        title = findViewById(R.id.art_title);
        meta = findViewById(R.id.art_meta);
        title.setTypeface(getResources().getFont(R.font.outfit_regular));
        meta.setTypeface(getResources().getFont(R.font.outfit_extralight));
        clock = findViewById(R.id.dream_clock);
        clock.setTypeface(getResources().getFont(R.font.outfit_thin));
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        DisplayMetrics dm = getResources().getDisplayMetrics();
        source = new ArtSource(this, dm.widthPixels, dm.heightPixels);
        Config c = Config.load(this);
        minMs = c.artMinMs;
        maxMs = Math.max(c.artMaxMs, minMs);
    }

    private long startedAt;

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        // Still the press that started us (released after we came up): ignore it
        if (e.getDownTime() < startedAt) return true;
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) wakeUp(); // any new press: back to what was on
        return true;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        startedAt = android.os.SystemClock.uptimeMillis();
        current = this;
        // Something on screen at once: a cached painting, then fetch the next one
        worker.execute(() -> {
            ArtSource.Art a = source.cached();
            ui.post(() -> {
                if (current != this) {
                    if (a != null) a.bitmap.recycle();
                    return;
                }
                if (a != null) {
                    show(a);
                    ui.postDelayed(this::nextPainting, minMs + (long) (rnd.nextDouble() * (maxMs - minMs)));
                } else {
                    nextPainting(); // empty cache (first run): download straight away
                }
            });
        });
        long standby = Config.load(this).artStandbyMs;
        if (standby > 0) ui.postDelayed(() -> {
            Log.i(TAG, "screen saver ran " + standby / 60000 + " min: standby");
            KeyHelperService.standby();
        }, standby);
    }

    @Override
    public void onDreamingStopped() {
        ui.removeCallbacksAndMessages(null);
        if (current == this) current = null;
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        worker.shutdownNow();
        for (ImageView v : views) release(v);
        super.onDetachedFromWindow();
    }

    private void nextPainting() {
        worker.execute(() -> {
            ArtSource.Art a = source.next();
            ui.post(() -> {
                if (current != this) {
                    if (a != null) a.bitmap.recycle();
                    return;
                }
                if (a != null) show(a);
                long wait = a == null ? 60_000 : minMs + (long) (rnd.nextDouble() * (maxMs - minMs));
                ui.postDelayed(this::nextPainting, wait);
            });
        });
    }

    private void show(ArtSource.Art a) {
        Log.i(TAG, "painting: " + a.title + " — " + a.artist + " (" + a.museum + ")");
        ImageView in = views[1 - front], out = views[front];
        release(in);
        in.setImageBitmap(a.bitmap);
        in.animate().alpha(1f).setDuration(2000).start();
        out.animate().alpha(0f).setDuration(2000).withEndAction(() -> release(out)).start();
        front = 1 - front;
        // One faint line, title then artist · year, gone after 10 s
        title.setText(a.title);
        StringBuilder m = new StringBuilder();
        for (String s : new String[]{a.artist, a.date}) {
            if (s != null && !s.isEmpty()) m.append(m.length() > 0 ? " · " : "").append(s);
        }
        meta.setText(m);
        caption.animate().cancel();
        caption.animate().alpha(0.5f).setDuration(1500).setStartDelay(2000).withEndAction(() ->
                caption.animate().alpha(0f).setDuration(2500).setStartDelay(10000).start()).start();
        // Clock changes corner with each painting so nothing burns in
        clockRight = !clockRight;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) clock.getLayoutParams();
        lp.gravity = Gravity.TOP | (clockRight ? Gravity.END : Gravity.START);
        clock.setLayoutParams(lp);
    }

    private static void release(ImageView v) {
        Drawable d = v.getDrawable();
        v.setImageDrawable(null);
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }
}
