package io.github.tvhomemodules.launcher;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

/** Live doorbell view in the corner of Home. It only exists while Home is in
 *  front: start() in onResume, release() in onPause, so the hardware decoder
 *  is free again for Live TV and apps. Muted, low-latency, RTP over TCP. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class Doorbell {
    private final Context ctx;
    private final View card;
    // TextureView (tile, PiP: rounded, crossfades) or SurfaceView (full screen: the
    // TV's video plane, native 4K instead of the 1080p UI layer)
    private final View video;
    private final TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private String url = "";
    private boolean visible = true;
    private int failures;
    private boolean playing; // first frame shown on the current player

    private final boolean fill;

    /** @param fill true = crop to fill the view (tile), false = fit inside (full screen) */
    Doorbell(Context ctx, View card, View video, TextView status, boolean fill) {
        this.fill = fill;
        this.ctx = ctx;
        this.card = card;
        this.video = video;
        this.status = status;
        card.setClipToOutline(true);
    }

    private String fallbackUrl = "";

    /** Like start(url), but tries {@code preferred} first and drops to
     *  {@code fallback} for good if the preferred stream fails. */
    void start(String preferred, String fallback) {
        fallbackUrl = fallback == null || fallback.equals(preferred) ? "" : fallback;
        start(preferred);
    }

    /** The doorbell's high-resolution stream for a configured sub-stream URL
     *  (Reolink: ..._sub -> ..._main); the URL itself if it has no _sub part. */
    static String mainStreamOf(String url) {
        return url == null ? "" : url.replace("Preview_01_sub", "Preview_01_main");
    }

    void start(String newUrl) {
        String u = newUrl == null ? "" : newUrl.trim();
        // Already showing this stream: keep it (reopening on every return to Home
        // piles up sessions on the doorbell, which only allows a few)
        if (u.equals(url) && player != null) return;
        url = u;
        if (url.isEmpty()) {
            release();
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        failures = 0;
        if (visible) play();
    }

    /** Make sure a visible doorbell is actually playing (after a screen saver, standby,
     *  network loss...). Does nothing if it already plays or nothing is configured. */
    void ensure() {
        if (!url.isEmpty() && visible && player == null) play(); // failures reset on the first frame
    }

    /** Page scrolled to the grid (tile off screen) or back. */
    void setOnScreen(boolean onScreen) {
        if (onScreen == visible) return;
        visible = onScreen;
        if (url.isEmpty()) return;
        if (onScreen) play();
        else release();
    }

    /** Keeps the camera's own aspect (the Reolink doorbell is 4:3): letterboxed
     *  when full screen, centre-cropped in the small tile. */
    private void fitVideo(float videoAspect) {
        if (video instanceof android.view.SurfaceView) { // size the surface itself, centred
            video.post(() -> {
                View parent = (View) video.getParent();
                float pw = parent.getWidth(), ph = parent.getHeight();
                if (pw == 0 || ph == 0) return;
                int w = (int) pw, h = (int) ph;
                if (videoAspect < pw / ph) w = Math.round(ph * videoAspect);
                else h = Math.round(pw / videoAspect);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(w, h);
                lp.gravity = android.view.Gravity.CENTER;
                video.setLayoutParams(lp);
            });
            return;
        }
        TextureView tv = (TextureView) video;
        video.post(() -> {
            float w = video.getWidth(), h = video.getHeight();
            if (w == 0 || h == 0) return;
            float viewAspect = w / h;
            float sx = 1f, sy = 1f;
            if (videoAspect < viewAspect) {
                if (fill) sy = viewAspect / videoAspect; else sx = videoAspect / viewAspect;
            } else {
                if (fill) sx = videoAspect / viewAspect; else sy = viewAspect / videoAspect;
            }
            android.graphics.Matrix m = new android.graphics.Matrix();
            // When cropping top/bottom (4:3 camera in a 16:9 tile), keep the BOTTOM edge:
            // the view straight down from the doorbell matters most (the camera's own
            // clock at the top is cut; full screen shows the whole picture)
            float pivotY = fill && sy > 1f ? h : h / 2f;
            m.setScale(sx, sy, w / 2f, pivotY);
            tv.setTransform(m);
        });
    }

    void release() {
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /** The underlying reason (e.g. "missing attribute fmtp" when the doorbell sends an
     *  incomplete stream description), with any login in a URL masked. */
    private static String causeOf(Throwable e) {
        Throwable c = e.getCause();
        while (c != null && c.getCause() != null && c.getCause() != c) c = c.getCause();
        if (c == null || c.getMessage() == null) return "";
        return " (" + c.getClass().getSimpleName() + ": "
                + c.getMessage().replaceAll("//[^@/\\s]+@", "//***@") + ")";
    }

    private void play() {
        release();
        playing = false;
        status.setText(R.string.doorbell);
        boolean texture = video instanceof TextureView;
        if (texture) video.setAlpha(0f); // a SurfaceView ignores alpha
        player = new ExoPlayer.Builder(ctx)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(500, 2000, 250, 250).build())
                .build();
        player.setVolume(0f);
        if (texture) player.setVideoTextureView((TextureView) video);
        else player.setVideoSurfaceView((android.view.SurfaceView) video);
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize vs) {
                android.util.Log.i("Doorbell", "video " + vs.width + "x" + vs.height);
                if (vs.width > 0 && vs.height > 0) fitVideo(vs.width * vs.pixelWidthHeightRatio / vs.height);
            }

            @Override
            public void onRenderedFirstFrame() {
                ui.removeCallbacks(stalled);
                failures = 0;
                playing = true;
                if (video instanceof TextureView) video.animate().alpha(1f).setDuration(250).start();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                // After the first frame: a stream that stops sending without an error leaves
                // the player buffering on a frozen picture. Same watchdog as before the first frame.
                if (!playing) return;
                if (state == Player.STATE_BUFFERING) {
                    ui.removeCallbacks(stalled);
                    ui.postDelayed(stalled, 12_000);
                } else if (state == Player.STATE_READY) {
                    ui.removeCallbacks(stalled);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException e) {
                android.util.Log.w("Doorbell", "stream error: " + e.getErrorCodeName() + causeOf(e));
                ui.removeCallbacks(stalled);
                if (!fallbackUrl.isEmpty()) { // e.g. main stream not decodable: use the sub-stream
                    android.util.Log.w("Doorbell", "preferred stream failed, falling back: " + e.getErrorCodeName());
                    url = fallbackUrl;
                    fallbackUrl = "";
                    ui.post(Doorbell.this::play);
                    return;
                }
                status.setText(R.string.doorbell_offline);
                if (video instanceof TextureView) video.setAlpha(0f);
                // Keep trying while visible (e.g. network still coming back after standby):
                // 5 s, 10 s, 15 s ... at most once a minute
                failures++;
                if (player != null) {
                    player.release();
                    player = null;
                }
                ui.postDelayed(Doorbell.this::ensure, Math.min(5000L * failures, 60_000L));
            }
        });
        player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url))));
        player.setPlayWhenReady(true);
        player.prepare();
        ui.postDelayed(stalled, 12_000); // connected but no picture: start over
    }

    /** No first frame in time (the doorbell accepted the session but sends nothing):
     *  close it cleanly and try again later. */
    private final Runnable stalled = this::onStalled;

    private void onStalled() {
        android.util.Log.w("Doorbell", (playing ? "picture frozen" : "no picture") + " for 12 s: reconnecting");
        playing = false;
        if (video instanceof TextureView) video.setAlpha(0f); // don't leave a frozen frame up
        status.setText(R.string.doorbell_offline);
        if (player != null) {
            player.release();
            player = null;
        }
        failures++;
        ui.postDelayed(this::ensure, Math.min(5000L * failures, 60_000L));
    }
}
