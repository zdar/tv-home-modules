package io.github.tvhomemodules.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The Home Assistant lights panel: a glass panel on the right over any app
 *  (accessibility overlay, so the app underneath keeps playing). Keys come from
 *  KeyHelperService while it is open: Up/Down choose, OK toggles, Left/Right
 *  brightness -/+10 %, Back or red closes. No colour temperature on purpose. */
final class HaPanel implements HaClient.Listener {
    private static final int AMBER = 0xFFFFD27A, DIM = 0xFF77777C;
    private static final long AUTO_HIDE_MS = 30_000;

    private final Context ctx;
    private final HaClient client;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private final float d;
    private final Typeface regular, light;
    private FrameLayout root;
    private LinearLayout list;
    private ScrollView scroll;
    private TextView count, status;
    private final List<HaClient.Light> lights = new ArrayList<>();
    private final List<View> rows = new ArrayList<>();
    private String selectedId;
    private int sel;

    HaPanel(Context c, HaClient client) {
        ctx = c;
        this.client = client;
        wm = c.getSystemService(WindowManager.class);
        d = c.getResources().getDisplayMetrics().density;
        regular = c.getResources().getFont(R.font.outfit_regular);
        light = c.getResources().getFont(R.font.outfit_light);
    }

    boolean isShowing() {
        return root != null;
    }

    void toggle() {
        if (isShowing()) hide();
        else show();
    }

    void show() {
        if (root != null) return;
        root = new FrameLayout(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xF02A2A2E);
        glass.setStroke(dp(1), 0x24FFFFFF);
        glass.setCornerRadius(dp(16));
        box.setBackground(glass);
        box.setPadding(dp(14), dp(12), dp(14), dp(10));

        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(icon(true, 20), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView title = text(ctx.getString(R.string.ha_title), 20, 0xFFF2F2F2, regular);
        title.setPadding(dp(8), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        count = text("", 13, 0xFFA9A9AD, light);
        head.addView(count);
        box.addView(head);

        status = text("", 13, 0xFFFFB4A8, light);
        status.setPadding(0, dp(6), 0, 0);
        box.addView(status);

        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View rule = new View(ctx);
        rule.setBackgroundColor(0x1AFFFFFF);
        box.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        TextView hint = text(ctx.getString(R.string.ha_hint), 12, 0xFF9A9AA0, light);
        hint.setPadding(0, dp(7), 0, 0);
        box.addView(hint);

        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.round(screenW * 0.37f), ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.dimAmount = 0.35f; // the picture behind dims a little but keeps playing
        lp.horizontalMargin = 0.02f;
        lp.verticalMargin = 0.035f;
        lp.height = Math.round(ctx.getResources().getDisplayMetrics().heightPixels * 0.93f);
        wm.addView(root, lp);
        root.setAlpha(0f);
        root.setTranslationX(dp(24));
        root.animate().alpha(1f).translationX(0).setDuration(180).start();
        onLights(client.snapshot(), client.status());
        restartAutoHide();
    }

    void hide() {
        ui.removeCallbacksAndMessages(null);
        if (root == null) return;
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
        root = null;
        rows.clear();
    }

    private void restartAutoHide() {
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(this::hide, AUTO_HIDE_MS);
    }

    /** Keys while the panel is open. Returns true when consumed (all but Home). */
    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_HOME) {
            hide();
            return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        restartAutoHide();
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_PROG_RED:
                hide();
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                select(sel - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                select(sel + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) act(0);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                act(-10);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                act(+10);
                break;
        }
        return true;
    }

    private void act(int step) {
        if (sel < 0 || sel >= lights.size()) return;
        HaClient.Light l = lights.get(sel);
        if (!l.available) return;
        if (step == 0) {                                // optimistic: HA's event confirms it
            client.call("toggle", l.id, null);
            l.on = !l.on;
            if (l.on && l.pct == 0) l.pct = 100;
        } else if (l.dimmable) {
            client.call("turn_on", l.id, step);
            l.pct = Math.max(0, Math.min(100, (l.on ? l.pct : 0) + step));
            l.on = l.pct > 0;
        } else {
            client.call(step > 0 ? "turn_on" : "turn_off", l.id, null);
            l.on = step > 0;
        }
        bindRow(sel);
        updateCount();
    }

    // ------------------------------------------------------------------ HaClient.Listener

    @Override
    public void onLights(List<HaClient.Light> fresh, String st) {
        if (root == null) return;
        int msg = "no_token".equals(st) ? R.string.ha_no_token : "bad_token".equals(st) ? R.string.ha_bad_token
                : "offline".equals(st) ? R.string.ha_offline : fresh.isEmpty() && st.isEmpty() ? R.string.ha_connecting : 0;
        status.setText(msg == 0 ? "" : ctx.getString(msg));
        status.setVisibility(msg == 0 ? View.GONE : View.VISIBLE);
        boolean sameList = fresh.size() == lights.size();
        for (int i = 0; sameList && i < fresh.size(); i++) sameList = fresh.get(i).id.equals(lights.get(i).id);
        lights.clear();
        lights.addAll(fresh);
        if (sameList && !rows.isEmpty()) {           // just states changed: rebind in place
            for (int i = 0; i < lights.size(); i++) bindRow(i);
        } else {
            rebuild();
        }
        updateCount();
    }

    private void updateCount() {
        int on = 0;
        for (HaClient.Light l : lights) if (l.on) on++;
        count.setText(lights.isEmpty() ? "" : ctx.getString(R.string.ha_on_count, on));
    }

    private void rebuild() {
        list.removeAllViews();
        rows.clear();
        String lastArea = null, lastFloor = null;
        for (int i = 0; i < lights.size(); i++) {
            HaClient.Light l = lights.get(i);
            // Each other floor: its own divider and floor-name heading, its rooms below
            if (l.farFloor && !l.floor.equals(lastFloor)) {
                View rule = new View(ctx);
                rule.setBackgroundColor(0x1AFFFFFF);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                rp.setMargins(dp(6), dp(12), dp(6), dp(2));
                list.addView(rule, rp);
                TextView fh = text(l.floor.isEmpty() ? "—" : l.floor, 13, 0xFF8A8A90, regular);
                fh.setPadding(dp(8), dp(4), dp(8), 0);
                list.addView(fh);
                lastFloor = l.floor;
                lastArea = null;
            }
            if (!l.area.equals(lastArea)) {
                String heading = l.area.isEmpty() ? "—" : l.area;
                TextView h = text(l.tvRoom ? heading + " · TV" : heading, 12,
                        l.tvRoom ? AMBER : l.farFloor ? 0xFF6E6E74 : 0xFF9A9AA0, light);
                h.setPadding(dp(l.farFloor ? 14 : 8), dp(lastArea == null ? 6 : 10), dp(8), dp(2));
                list.addView(h);
                lastArea = l.area;
            }
            View row = buildRow(l);
            rows.add(row);
            list.addView(row);
            bindRow(i);
        }
        if (lights.isEmpty() && status.getVisibility() != View.VISIBLE) {
            status.setText(R.string.ha_no_lights);
            status.setVisibility(View.VISIBLE);
        }
        // Reopen on the last used light if it is still there, else the first one
        int keep = 0;
        for (int i = 0; i < lights.size(); i++) if (lights.get(i).id.equals(selectedId)) keep = i;
        select(keep);
    }

    private View buildRow(HaClient.Light l) {
        boolean quiet = l.motion || l.farFloor;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(quiet ? 4 : 7), dp(8), dp(quiet ? 4 : 7));
        ImageView ic = new ImageView(ctx);
        ic.setTag("icon");
        row.addView(ic, new LinearLayout.LayoutParams(dp(quiet ? 14 : 17), dp(quiet ? 14 : 17)));
        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(9), 0, 0, 0);
        LinearLayout line = new LinearLayout(ctx);
        TextView name = text(l.name, quiet ? 13 : 15, 0xFFF2F2F2, regular);
        name.setTag("name");
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = text("", quiet ? 12 : 13, DIM, light);
        state.setTag("state");
        line.addView(state);
        mid.addView(line);
        if (l.dimmable && !quiet) {
            FrameLayout bar = new FrameLayout(ctx);
            bar.setTag("bar");
            GradientDrawable track = new GradientDrawable();
            track.setColor(0x26FFFFFF);
            track.setCornerRadius(dp(2));
            bar.setBackground(track);
            View fill = new View(ctx);
            GradientDrawable fd = new GradientDrawable();
            fd.setColor(AMBER);
            fd.setCornerRadius(dp(2));
            fill.setBackground(fd);
            bar.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            bp.topMargin = dp(5);
            mid.addView(bar, bp);
        }
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (quiet) row.setAlpha(0.6f);
        return row;
    }

    private void bindRow(int i) {
        if (i < 0 || i >= rows.size()) return;
        HaClient.Light l = lights.get(i);
        View row = rows.get(i);
        ImageView ic = row.findViewWithTag("icon");
        ic.setImageResource(l.motion ? R.drawable.ic_walk : R.drawable.ic_bulb);
        ic.setImageTintList(ColorStateList.valueOf(l.on ? AMBER : DIM));
        TextView name = row.findViewWithTag("name");
        name.setTextColor(l.on ? 0xFFF2F2F2 : 0xFFC9C9CD);
        TextView state = row.findViewWithTag("state");
        String s = !l.available ? ctx.getString(R.string.ha_unavailable)
                : !l.on ? ctx.getString(R.string.ha_off)
                : l.dimmable ? l.pct + " %" : ctx.getString(R.string.ha_on);
        state.setText(s);
        state.setTextColor(l.on ? AMBER : DIM);
        View bar = row.findViewWithTag("bar");
        if (bar != null) {
            View fill = ((ViewGroup) bar).getChildAt(0);
            bar.post(() -> {
                ViewGroup.LayoutParams p = fill.getLayoutParams();
                p.width = Math.round(bar.getWidth() * (l.on ? l.pct : 0) / 100f);
                fill.setLayoutParams(p);
            });
        }
    }

    private void select(int i) {
        if (rows.isEmpty()) return;
        i = Math.max(0, Math.min(i, rows.size() - 1));
        if (sel >= 0 && sel < rows.size()) rows.get(sel).setBackground(new ColorDrawable(0));
        sel = i;
        selectedId = lights.get(i).id;
        View row = rows.get(i);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x2EFFFFFF);
        g.setStroke(dp(1.5f), 0x80FFFFFF);
        g.setCornerRadius(dp(10));
        row.setBackground(g);
        row.post(() -> scroll.smoothScrollTo(0, Math.max(0, row.getTop() - dp(60))));
    }

    private ImageView icon(boolean on, int sizeDp) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(R.drawable.ic_bulb);
        iv.setImageTintList(ColorStateList.valueOf(on ? AMBER : DIM));
        return iv;
    }

    private TextView text(String s, float sp, int colour, Typeface tf) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(tf);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * d);
    }
}
