package io.github.tvhomemodules.launcher;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

final class AppEntry {
    final String pkg;
    final String label;
    final Drawable banner; // may be null: then the icon is drawn on a tinted card
    final Drawable icon;
    final Intent launch;
    final int glow;

    AppEntry(String pkg, String label, Drawable banner, Drawable icon, Intent launch) {
        this.pkg = pkg;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.launch = launch;
        this.glow = sampleColor(banner != null ? banner : icon);
    }

    /** The most vivid reasonably bright colour of a tiny copy of the artwork,
     *  falling back to the average. Runs once per app on the loader thread. */
    static int sampleColor(Drawable d) {
        if (d == null) return 0xFF5A5A5E;
        final int w = 24, h = 14;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, w, h);
        copy.draw(new Canvas(b));
        float[] hsv = new float[3];
        float bestScore = 0f;
        float[] best = null;
        long r = 0, g = 0, bl = 0, n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * hsv[2];
                if (hsv[2] > 0.3f && hsv[1] > 0.25f && score > bestScore) {
                    bestScore = score;
                    best = hsv.clone();
                }
            }
        }
        b.recycle();
        if (best != null) {
            best[2] = Math.max(best[2], 0.8f);
            return Color.HSVToColor(best);
        }
        if (n == 0) return 0xFF5A5A5E;
        int avg = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        Color.colorToHSV(avg, hsv);
        hsv[2] = Math.max(hsv[2], 0.6f);
        return Color.HSVToColor(hsv);
    }
}
