package io.github.tvhomemodules.launcher;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.TextUtils;
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
 *  crossfade, a faint caption in the black band beside it, and a small clock opposite
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
    private TextView caption;
    private TextClock clock;
    private boolean capLeft; // caption side while it fits in a band
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
        caption.setTypeface(getResources().getFont(R.font.outfit_light));
        clock = findViewById(R.id.dream_clock);
        clock.setTypeface(getResources().getFont(R.font.outfit_thin));
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        DisplayMetrics dm = getResources().getDisplayMetrics();
        source = new ArtSource(this, dm.widthPixels, dm.heightPixels);
        Config c = Config.load(this);
        minMs = c.artMinMs;
        maxMs = Math.max(c.artMaxMs, minMs);
        haHoldKey = c.haHoldKey;
    }

    private long startedAt, haDownTime = -1;
    private int haHoldKey;

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        // Still the press that started us (released after we came up): ignore it
        if (e.getDownTime() < startedAt) return true;
        // The lights-panel key acts on release: a hold opens the panel over the painting
        // (the key helper keeps that release and every key while the panel is up),
        // so only the release of a short press that we saw go down ends the screen saver
        if (haHoldKey != 0 && e.getKeyCode() == haHoldKey) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) haDownTime = e.getDownTime();
            else if (e.getDownTime() == haDownTime) wakeUp();
            return true;
        }
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
        // Where the painting lands. With room beside it the caption goes in the side band;
        // without, the painting is scaled down just enough to leave a black strip under it
        // for one caption line (text over a painting is unreadable).
        View root = (View) caption.getParent();
        int sw = root.getWidth(), sh = root.getHeight();
        int w = a.bitmap.getWidth(), h = a.bitmap.getHeight();
        float d = getResources().getDisplayMetrics().density;
        boolean inBand = true;
        int pw = 0, ph = 0, top = 0, pad = 0;
        if (sw > 0 && sh > 0 && w > 0 && h > 0) {
            float s = Math.min((float) sw / w, (float) sh / h); // fitCenter
            pw = Math.round(w * s);
            ph = Math.round(h * s);
            inBand = (sw - pw) / 2f >= MIN_BAND_DP * d;
            int strip = Math.round(STRIP_DP * d);
            if (!inBand && (sh - ph) / 2 < strip) {
                pad = strip;
                s = Math.min((float) sw / w, (float) (sh - pad) / h);
                pw = Math.round(w * s);
                ph = Math.round(h * s);
            }
            top = (sh - pad - ph) / 2;
        }
        in.setPadding(0, 0, 0, pad);
        in.setImageBitmap(a.bitmap);
        in.animate().alpha(1f).setDuration(2000).start();
        out.animate().alpha(0f).setDuration(2000).withEndAction(() -> release(out)).start();
        front = 1 - front;
        if (pw == 0) return;
        // The clock always sits opposite the caption. Caption in a band: both swap sides
        // with each painting so nothing burns in. Caption under the painting: it is
        // always bottom right, so the clock is top left.
        if (inBand) capLeft = !capLeft;
        boolean left = inBand && capLeft;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) clock.getLayoutParams();
        lp.gravity = Gravity.TOP | (left ? Gravity.END : Gravity.START);
        clock.setLayoutParams(lp);
        int fpw = pw, fph = ph, ftop = top;
        boolean fBand = inBand;
        caption.animate().cancel();
        caption.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            placeCaption(a, sw, sh, fpw, fph, ftop, fBand, left);
            caption.animate().alpha(CAPTION_ALPHA).setDuration(1500).setStartDelay(900).start();
        }).start();
    }

    private static final float CAPTION_ALPHA = 0.2f; // the clock is 0.3
    private static final int MIN_BAND_DP = 110, STRIP_DP = 40, GAP_DP = 16, EDGE_DP = 14;

    /** In the black band beside the painting (stacked, next to its bottom edge, aligned
     *  towards it), or one line in the strip under the painting, aligned to its right edge. */
    private void placeCaption(ArtSource.Art a, int sw, int sh, int pw, int ph, int top, boolean inBand, boolean left) {
        float d = getResources().getDisplayMetrics().density;
        int band = (sw - pw) / 2, below = sh - top - ph;
        int gap = Math.round(GAP_DP * d), edge = Math.round(EDGE_DP * d);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) caption.getLayoutParams();
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.bottomMargin = inBand ? below + edge : Math.max(0, (below - caption.getLineHeight()) / 2);
        if (inBand) {
            lp.width = band - gap - edge;
            lp.leftMargin = left ? edge : band + pw + gap;
            caption.setGravity(left ? Gravity.END : Gravity.START);
            caption.setSingleLine(false);
            caption.setMaxLines(6);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setText(join("\n", a.artist, a.title, a.date, a.museum));
        } else {
            lp.width = band + pw - edge; // ends at the painting's right edge, may start left of it
            lp.leftMargin = edge;
            caption.setGravity(Gravity.END);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.START);
            caption.setText(join("  ·  ", a.artist, a.title, a.date, a.museum));
        }
        caption.setLayoutParams(lp);
    }

    private static String join(String sep, String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) b.append(b.length() > 0 ? sep : "").append(p);
        }
        return b.toString();
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
