package io.github.tvhomemodules.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** A screen-sized frame whose children may be taller than it: they are measured
 *  with unbounded height (so the RecyclerView lays out every tile) and are
 *  scrolled by translationY instead of a ScrollView, which keeps D-pad
 *  movement fully under MainActivity's control. */
public class TallLayout extends FrameLayout {
    public TallLayout(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), unbounded);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.layout(0, 0, c.getMeasuredWidth(), c.getMeasuredHeight());
        }
    }
}
