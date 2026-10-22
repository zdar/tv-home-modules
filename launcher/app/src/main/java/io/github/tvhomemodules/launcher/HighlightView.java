package io.github.tvhomemodules.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** The single translucent "glass" focus highlight. It spans the whole page and
 *  only draws one rounded rect, so moving it is an invalidate, never a layout. */
public class HighlightView extends View {
    private final RectF from = new RectF(), to = new RectF(), cur = new RectF();
    private float fromR, toR, curR;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient sheen;
    private final Matrix sheenMatrix = new Matrix();
    private ValueAnimator anim;

    public HighlightView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        // Unit-height gradient, stretched to the rect with a matrix each frame
        sheen = new LinearGradient(0, 0, 0, 1,
                new int[]{0x4DFFFFFF, 0x17FFFFFF, 0x0AFFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        fill.setShader(sheen);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * d);
        rim.setColor(0x80FFFFFF);
        setWillNotDraw(false);
    }

    public RectF target() {
        return to;
    }

    public void moveTo(RectF target, float radius, boolean animate) {
        if (anim != null) anim.cancel();
        to.set(target);
        toR = radius;
        if (!animate || cur.isEmpty()) {
            cur.set(to);
            curR = toR;
            invalidate();
            return;
        }
        from.set(cur);
        fromR = curR;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(300);
        anim.setInterpolator(new OvershootInterpolator(1.3f));
        anim.addUpdateListener(va -> {
            float f = (float) va.getAnimatedValue();
            cur.set(from.left + (to.left - from.left) * f,
                    from.top + (to.top - from.top) * f,
                    from.right + (to.right - from.right) * f,
                    from.bottom + (to.bottom - from.bottom) * f);
            curR = fromR + (toR - fromR) * f;
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (cur.isEmpty()) return;
        sheenMatrix.setScale(1f, cur.height());
        sheenMatrix.postTranslate(0, cur.top);
        sheen.setLocalMatrix(sheenMatrix);
        c.drawRoundRect(cur, curR, curR, fill);
        c.drawRoundRect(cur, curR, curR, rim);
    }
}
