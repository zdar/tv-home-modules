package io.github.tvhomemodules.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int TOP = 0, DOCK = 1, GRID = 2, INPUTS = 3, BELL = 4;
    private static final int COLS = 4;          // grid and dock row 1
    private static final int SLOTS = 8;         // dock row 2 (half-size tiles)
    private static final float TILE_SCALE = 1.08f, PILL_SCALE = 1.06f, BELL_SCALE = 1.04f;
    private static final long HOLD_MS = 600;
    /** Home is in front (its doorbell tile is visible): no PiP needed. */
    static volatile boolean homeVisible;
    private static MainActivity instance;

    // ------------------------------------------------------------------ doorbell tile flash

    private View bellFlash;
    private GradientDrawable flashRing;
    private int flashesLeft;

    /** A doorbell detection: pulse the tile's border (person = amber, motion / pet /
     *  vehicle = white). A ring opens the full-screen view instead, so no flash. */
    static void doorbellEvent(String event) {
        MainActivity a = instance;
        if (a == null || !a.resumed || !a.bellVisible() || Events.RING.equals(event)) return;
        int colour = Events.PERSON.equals(event) ? 0xFFFFB300 : 0xFFFFFFFF;
        a.flashRing.setStroke(a.dp(4), colour);
        boolean idle = a.flashesLeft <= 0;
        a.flashesLeft = 3;
        if (idle) a.pulse();
    }

    private void pulse() {
        if (!resumed) {
            flashesLeft = 0;
            bellFlash.setAlpha(0f);
            return;
        }
        if (flashesLeft <= 0 && !Events.anyActive()) { // keep pulsing while motion / a person is reported
            bellFlash.animate().alpha(0f).setDuration(200).start();
            return;
        }
        flashesLeft--;
        bellFlash.animate().alpha(1f).setDuration(220).withEndAction(() ->
                bellFlash.animate().alpha(0.15f).setDuration(380).withEndAction(this::pulse).start()).start();
    }

    // Focus model. Android focus is not used at all: every D-pad key goes
    // through move(), so Up/Down always move exactly one row.
    // In the dock, dockIdx = row * SLOTS + column.
    private int zone = DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0, inputIdx = 0;
    // Choosing an app for a dock slot (pickSlot >= 0): row and column of that slot
    private int pickRow = 0, pickSlot = -1;
    // Hold-OK menu on a dock tile: replace / remove / cancel
    private static final int MENU = 5;
    private LinearLayout slotMenu;
    private int menuIdx;

    private View root, page, content, header, glow, scrim, bellCard;
    private ImageView wallpaper;
    private HighlightView highlight;
    private RecyclerView grid;
    private GridLayoutManager gridLayout;
    private LinearLayout inputsPanel;
    private final LinearLayout[] dockRowViews = new LinearLayout[2];
    private TextView title, greeting, netLabel;
    private ImageView netIcon;
    private final View[] pills = new View[3];

    // Dock row 1 = COLS colour-button slots, row 2 = SLOTS slots; null = empty "+".
    // config "dock"/"dock2" are only the defaults until the user edits a slot on the TV.
    private final AppEntry[] row1 = new AppEntry[COLS];
    private final AppEntry[] row2 = new AppEntry[SLOTS];
    private static final int[] RIM_COLOURS = {0xFFE53935, 0xFF43A047, 0xFFFDD835, 0xFF1E88E5};
    private static final int[] COLOUR_KEYS = {KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN,
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE};
    private final List<AppEntry> gridApps = new ArrayList<>();
    private final List<InputItem> inputs = new ArrayList<>();
    private final GridAdapter adapter = new GridAdapter();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Config config;
    private String loadedSignature = "";
    private String wallpaperKey = "";
    private boolean resumed, appsDirty = true;
    private int glowColor = 0xFF2C2C2E;
    private GradientDrawable glowDrawable;
    private ValueAnimator glowAnim;
    private Typeface thin, light, regular;
    private SharedPreferences prefs;
    private ConnectivityManager cm;
    private Doorbell doorbell;

    private static final class InputItem {
        final String id, label;
        final boolean tuner;

        InputItem(String id, String label, boolean tuner) {
            this.id = id;
            this.label = label;
            this.tuner = tuner;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("focus", MODE_PRIVATE);
        thin = getResources().getFont(R.font.outfit_thin);
        light = getResources().getFont(R.font.outfit_light);
        regular = getResources().getFont(R.font.outfit_regular);

        root = findViewById(R.id.root);
        page = findViewById(R.id.page);
        content = findViewById(R.id.content);
        header = findViewById(R.id.header);
        glow = findViewById(R.id.glow);
        scrim = findViewById(R.id.scrim);
        wallpaper = findViewById(R.id.wallpaper);
        highlight = findViewById(R.id.highlight);
        grid = findViewById(R.id.grid);
        dockRowViews[0] = findViewById(R.id.dock_row1);
        dockRowViews[1] = findViewById(R.id.dock_row2);
        inputsPanel = findViewById(R.id.inputs_panel);
        slotMenu = findViewById(R.id.slot_menu);
        title = findViewById(R.id.title);
        greeting = findViewById(R.id.greeting);
        netLabel = findViewById(R.id.net_label);
        netIcon = findViewById(R.id.net_icon);
        pills[0] = findViewById(R.id.pill_inputs);
        pills[1] = findViewById(R.id.pill_net);
        pills[2] = findViewById(R.id.pill_settings);

        title.setTypeface(light);
        greeting.setTypeface(regular);
        TextClock clock = findViewById(R.id.clock);
        clock.setTypeface(thin);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        TextClock date = findViewById(R.id.date);
        date.setTypeface(light);
        String datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        date.setFormat12Hour(datePattern);
        date.setFormat24Hour(datePattern);

        // Header is exactly one screen, so the grid starts just below the fold
        header.getLayoutParams().height = getResources().getDisplayMetrics().heightPixels;

        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(dp(160));
        setGlow(glowColor);
        glow.setBackground(glowDrawable);

        gridLayout = new GridLayoutManager(this, COLS);
        grid.setLayoutManager(gridLayout);
        grid.setAdapter(adapter);
        grid.setItemAnimator(null);
        grid.setNestedScrollingEnabled(false);
        grid.setFocusable(false);

        zone = prefs.getInt("zone", DOCK);
        topIdx = prefs.getInt("top", 0);
        dockIdx = prefs.getInt("dock2pos", 0);
        gridIdx = prefs.getInt("grid", 0);
        if (zone == INPUTS) zone = TOP;

        // Re-snap the highlight if a layout moved its target (clock width, new apps...)
        page.getViewTreeObserver().addOnGlobalLayoutListener(() -> placeHighlight(false, true));

        instance = this;
        bellCard = findViewById(R.id.doorbell);
        bellFlash = findViewById(R.id.doorbell_flash);
        flashRing = new GradientDrawable();
        flashRing.setCornerRadius(dp(10));
        flashRing.setStroke(dp(4), 0xFFFFFFFF);
        bellFlash.setBackground(flashRing);
        doorbell = new Doorbell(this, bellCard,
                findViewById(R.id.doorbell_video), findViewById(R.id.doorbell_status), true);
        ((TextView) findViewById(R.id.doorbell_status)).setTypeface(regular);

        cm = getSystemService(ConnectivityManager.class);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        // After standby the clock must not wait for its own minute timer (that timer
        // doesn't run while the TV sleeps): refresh on wake and on any time change
        IntentFilter tf = new IntentFilter(Intent.ACTION_SCREEN_ON);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(Intent.ACTION_DATE_CHANGED);
        tf.addAction(Intent.ACTION_DREAMING_STOPPED); // Home stays resumed under the screen saver
        tf.addAction("tcl.sys.intent.action.STR_RESUME_COMPLETED"); // TCL resume from standby
        registerReceiver(timeReceiver, tf);
    }

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refreshClock();
            if (resumed) ui.postDelayed(doorbell::ensure, 3_000); // after wake / screen saver
            // NTP corrects the time a few seconds after the network is back: refresh again then
            ui.postDelayed(MainActivity.this::refreshClock, 5_000);
            ui.postDelayed(MainActivity.this::refreshClock, 30_000);
        }
    };

    /** Home is on screen again (screen saver ended, an overlay closed, an app went away). */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            refreshClock();
            if (resumed) doorbell.ensure(); // the tile may have been stopped meanwhile
        }
    }

    /** Redraws clock, date and greeting now (re-setting the format makes TextClock re-read the time). */
    private void refreshClock() {
        TextClock clock = findViewById(R.id.clock);
        clock.setFormat24Hour(null);
        clock.setFormat24Hour("H:mm");
        clock.setFormat12Hour(null);
        clock.setFormat12Hour("h:mm");
        TextClock date = findViewById(R.id.date);
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        date.setFormat24Hour(null);
        date.setFormat24Hour(pattern);
        date.setFormat12Hour(null);
        date.setFormat12Hour(pattern);
        updateGreeting();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        homeVisible = true;
        config = Config.load(this);
        applyWallpaper();
        refreshClock();
        registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
        cm.registerDefaultNetworkCallback(netCallback);
        updateNetwork();
        doorbell.setOnScreen(zone != GRID);
        doorbell.start(config.doorbellUrl);
        if (appsDirty || !(config.signature + slotsString()).equals(loadedSignature)) loadApps();
        else refocus(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        homeVisible = false;
        doorbell.release(); // hand the video decoder back before the next app starts
        try {
            unregisterReceiver(tickReceiver);
            cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
        if (pickSlot >= 0) cancelPick();
        if (zone == MENU) closeMenu();
        saveFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (instance == this) instance = null;
        unregisterReceiver(packageReceiver);
        unregisterReceiver(timeReceiver);
        loader.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to dock row 1. Coming back from
        // an app (not resumed yet) keeps the remembered tile.
        if (resumed) {
            closeInputs();
            if (pickSlot >= 0) cancelPick();
            if (zone == MENU) closeMenu();
            focus(DOCK, dockClamp(0, dockIdx / SLOTS == 0 ? dockIdx % SLOTS : 0), true);
        }
    }

    private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateGreeting();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (resumed) loadApps();
        }
    };

    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities nc) {
            ui.post(MainActivity.this::updateNetwork);
        }

        @Override
        public void onAvailable(@NonNull Network n) { // network back (e.g. after standby): reconnect the tile
            ui.post(() -> {
                if (resumed) doorbell.ensure();
            });
        }

        @Override
        public void onLost(@NonNull Network n) {
            ui.post(MainActivity.this::updateNetwork);
        }
    };

    // ------------------------------------------------------------------ apps & dock

    /** A dock row's slots as package names ("" = empty). Edited on the TV and kept
     *  in prefs; until the first edit of that row they come from config
     *  "dock" (row 0) / "dock2" (row 1). */
    private String[] slots(int row) {
        String saved = prefs.getString(slotsKey(row), null);
        int n = row == 0 ? COLS : SLOTS;
        String[] out = new String[n];
        Arrays.fill(out, "");
        List<String> src = saved != null ? Arrays.asList(saved.split(",", -1))
                : row == 0 ? config.dock : config.dock2;
        for (int i = 0; i < n && i < src.size(); i++) out[i] = src.get(i).trim();
        return out;
    }

    private static String slotsKey(int row) {
        return row == 0 ? "slots1" : "slots";
    }

    private String slotsString() {
        return prefs.getString("slots1", "") + "|" + prefs.getString("slots", "");
    }

    private void saveSlot(int row, int i, String pkg) {
        String[] s = slots(row);
        s[i] = pkg;
        prefs.edit().putString(slotsKey(row), TextUtils.join(",", s)).apply();
    }

    private void loadApps() {
        appsDirty = false;
        final Config cfg = config;
        final String[] slots1 = slots(0), slots = slots(1);
        final String sig = cfg.signature + slotsString();
        loader.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            Map<String, AppEntry> byPkg = new HashMap<>();
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || byPkg.containsKey(pkg)) continue;
                Drawable banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                Intent launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                byPkg.put(pkg, new AppEntry(pkg, String.valueOf(ri.loadLabel(pm)).trim(),
                        banner, ri.loadIcon(pm), launch));
            }
            AppEntry[] r1 = new AppEntry[COLS];
            for (int i = 0; i < COLS; i++) r1[i] = slots1[i].isEmpty() ? null : byPkg.remove(slots1[i]);
            AppEntry[] r2 = new AppEntry[SLOTS];
            for (int i = 0; i < SLOTS; i++) r2[i] = slots[i].isEmpty() ? null : byPkg.remove(slots[i]);
            List<AppEntry> rest = new ArrayList<>();
            for (AppEntry a : byPkg.values()) if (!cfg.hidden.contains(a.pkg)) rest.add(a);
            Collator col = Collator.getInstance(Locale.getDefault());
            rest.sort((x, y) -> col.compare(x.label, y.label));
            ui.post(() -> {
                loadedSignature = sig;
                System.arraycopy(r1, 0, row1, 0, COLS);
                System.arraycopy(r2, 0, row2, 0, SLOTS);
                gridApps.clear();
                gridApps.addAll(rest);
                buildDock();
                adapter.notifyDataSetChanged();
                dockIdx = dockClamp(dockIdx / SLOTS, dockIdx % SLOTS);
                gridIdx = clamp(gridIdx, gridApps.size());
                if (zone == GRID && gridApps.isEmpty()) zone = DOCK;
                page.post(() -> refocus(false));
            });
        });
    }

    private int rowLen(int r) {
        return r == 0 ? COLS : SLOTS;
    }

    private int dockClamp(int r, int c) {
        r = Math.max(0, Math.min(r, 1));
        return r * SLOTS + Math.max(0, Math.min(c, rowLen(r) - 1));
    }

    /** The app at a dock position; null for an empty "+" slot. */
    private AppEntry dockAt(int idx) {
        int r = idx / SLOTS, c = idx % SLOTS;
        if (r == 0) return c < COLS ? row1[c] : null;
        return c < SLOTS ? row2[c] : null;
    }

    private void buildDock() {
        LayoutInflater inf = LayoutInflater.from(this);
        LinearLayout r1 = dockRowViews[0], r2 = dockRowViews[1];
        r1.removeAllViews();
        for (int c = 0; c < COLS; c++) {
            View v = inf.inflate(R.layout.item_tile, r1, false);
            v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            v.findViewById(R.id.label).setVisibility(View.GONE);
            if (row1[c] != null) bindTile(v, row1[c]);
            else bindEmpty(v);
            {
                // Thick ring OUTSIDE the card in the colour of its remote button
                GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(dp(18));
                ring.setStroke(dp(6), RIM_COLOURS[c]);
                View rv = v.findViewById(R.id.rim);
                rv.setBackground(ring);
                // Explicit size + negative offset: the ring sits 10 dp outside the card
                android.widget.FrameLayout.LayoutParams lp =
                        new android.widget.FrameLayout.LayoutParams(dp(188 + 20), dp(106 + 20));
                lp.leftMargin = -dp(10);
                lp.topMargin = -dp(10);
                rv.setLayoutParams(lp);
                rv.setVisibility(View.VISIBLE);
                // What holding this colour button does (if anything), on the ring's bottom edge
                String hold = holdLabel(COLOUR_KEYS[c]);
                if (hold != null) {
                    TextView tag = new TextView(this);
                    tag.setText(getString(R.string.hold_prefix, hold));
                    tag.setTypeface(regular);
                    tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                    tag.setTextColor(c == 2 ? 0xFF3A2E00 : 0xFFFFFFFF); // dark text on yellow
                    tag.setPadding(dp(7), dp(1), dp(7), dp(2));
                    GradientDrawable pill = new GradientDrawable();
                    pill.setColor(RIM_COLOURS[c]);
                    pill.setCornerRadius(dp(8));
                    tag.setBackground(pill);
                    android.widget.FrameLayout.LayoutParams tp = new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
                    tag.setLayoutParams(tp);
                    tag.setTranslationY(dp(15)); // sits across the ring's bottom edge
                    ((ViewGroup) v.findViewById(R.id.holder)).addView(tag);
                }
            }
            r1.addView(v);
        }
        r2.removeAllViews();
        for (int c = 0; c < SLOTS; c++) {
            View v = inf.inflate(R.layout.item_tile, r2, false);
            v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            v.findViewById(R.id.label).setVisibility(View.GONE);
            // Half-size: 94 x 53 dp
            View tile = v.findViewById(R.id.tile), holder = v.findViewById(R.id.holder);
            tile.getLayoutParams().width = dp(94);
            holder.getLayoutParams().width = dp(94);
            holder.getLayoutParams().height = dp(53);
            ImageView icon = v.findViewById(R.id.icon);
            icon.getLayoutParams().width = dp(26);
            icon.getLayoutParams().height = dp(26);
            v.findViewById(R.id.fallback_name).setVisibility(View.GONE);
            if (row2[c] != null) bindTile(v, row2[c]);
            else bindEmpty(v);
            r2.addView(v);
        }
    }

    /** Short name of the long-press action of a colour key, or null if it has none. */
    private String holdLabel(int keyCode) {
        if (config == null) return null;
        if (config.doorbellHoldKey == keyCode) return getString(R.string.hold_doorbell);
        if (config.haHoldKey == keyCode) return getString(R.string.hold_lights);
        if (config.saverHoldKey == keyCode) return getString(R.string.hold_saver);
        return null;
    }

    private void bindEmpty(View v) {
        View card = v.findViewById(R.id.card);
        card.setBackgroundResource(R.drawable.slot_bg);
        v.findViewById(R.id.banner).setVisibility(View.GONE);
        v.findViewById(R.id.fallback).setVisibility(View.GONE);
        v.findViewById(R.id.plus).setVisibility(View.VISIBLE);
    }

    private void bindTile(View v, AppEntry a) {
        View card = v.findViewById(R.id.card);
        card.setClipToOutline(true);
        ImageView banner = v.findViewById(R.id.banner);
        View fallback = v.findViewById(R.id.fallback);
        v.findViewById(R.id.plus).setVisibility(View.GONE);
        if (a.banner != null) {
            banner.setImageDrawable(a.banner);
            banner.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
            card.setBackgroundResource(R.drawable.tile_bg);
        } else {
            banner.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
            ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(a.icon);
            ((TextView) v.findViewById(R.id.fallback_name)).setText(a.label);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(blend(a.glow, 0xFF1C1C1E, 0.78f));
            card.setBackground(bg);
        }
        ((TextView) v.findViewById(R.id.label)).setText(a.label);
        View holder = v.findViewById(R.id.holder);
        holder.setScaleX(1f);
        holder.setScaleY(1f);
    }

    private final class GridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false);
            v.setPadding(0, 0, 0, dp(14));
            return new RecyclerView.ViewHolder(v) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            bindTile(h.itemView, gridApps.get(pos));
            boolean f = zone == GRID && pos == gridIdx;
            View holder = h.itemView.findViewById(R.id.holder);
            holder.setScaleX(f ? TILE_SCALE : 1f);
            holder.setScaleY(f ? TILE_SCALE : 1f);
            h.itemView.findViewById(R.id.label).setAlpha(f ? 1f : 0f);
        }

        @Override
        public int getItemCount() {
            return gridApps.size();
        }
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        // Colour buttons open dock row 1 - here on Home only, so they keep their
        // normal meaning (HbbTV, teletext) everywhere else. On release and only
        // for a short press: holding blue is the doorbell shortcut.
        for (int i = 0; i < COLOUR_KEYS.length; i++) {
            if (k == COLOUR_KEYS[i]) {
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()
                        && e.getEventTime() - e.getDownTime() < HOLD_MS && row1[i] != null && zone != MENU) {
                    closeInputs();
                    if (pickSlot >= 0) cancelPick();
                    focus(DOCK, i, false);
                    launch(row1[i]);
                }
                return true;
            }
        }
        boolean ours;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
                ours = true;
                break;
            default:
                ours = false;
        }
        if (!ours) return super.dispatchKeyEvent(e);
        boolean select = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        // Dock: OK acts on release; holding it opens the replace/remove menu.
        // A timer, not key repeats: this TCL remote sends none for a held key.
        if (select && zone == DOCK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                slotHoldDone = false;
                slotPressed = true;
                ui.removeCallbacks(slotHold);
                ui.postDelayed(slotHold, HOLD_MS);
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                ui.removeCallbacks(slotHold);
                if (slotPressed && !slotHoldDone) activate();
                slotPressed = false;
            }
            return true;
        }
        if (zone == MENU) {
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                int n = menuIdx + (k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                if (n >= 0 && n < slotMenu.getChildCount() - 1) selectMenu(n);
            } else if (k == KeyEvent.KEYCODE_BACK) {
                closeMenu();
            } else if (select) {
                menuAction(menuIdx);
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(0, -1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(0, 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(-1, 0);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(1, 0);
                break;
            case KeyEvent.KEYCODE_BACK:
                back();
                break;
            default:
                if (e.getRepeatCount() == 0) activate();
        }
        return true;
    }

    private boolean slotPressed, slotHoldDone;
    private final Runnable slotHold = () -> {
        slotHoldDone = true;
        if (zone == DOCK && dockAt(dockIdx) != null) openMenu();
    };

    private void move(int dx, int dy) {
        switch (zone) {
            case TOP:
                if (dx != 0) {
                    int n = topIdx + dx;
                    if (n >= 0 && n < pills.length) focus(TOP, n, true); // ends don't fall through
                } else if (dy > 0) {
                    // Down one step: the doorbell tile under the pills, else the dock
                    if (bellVisible()) focus(BELL, 0, true);
                    else focus(DOCK, dockClamp(0, dockIdx / SLOTS == 0 ? dockIdx % SLOTS : 0), true);
                }
                break;
            case BELL:
                // Left/Right do nothing; Up = pills, Down = dock row 1 (its right end)
                if (dy < 0) focus(TOP, topIdx, true);
                else if (dy > 0) focus(DOCK, dockClamp(0, COLS - 1), true);
                break;
            case DOCK: {
                int r = dockIdx / SLOTS, c = dockIdx % SLOTS;
                if (dx != 0) {
                    int nc = c + dx;
                    if (nc >= 0 && nc < rowLen(r)) focus(DOCK, r * SLOTS + nc, true);
                } else if (dy < 0) {
                    if (r == 1) focus(DOCK, dockClamp(0, c / 2), true); // 2 slots per tile above
                    else if (bellVisible()) focus(BELL, 0, true);
                    else focus(TOP, topIdx, true);
                } else if (r == 0) {
                    focus(DOCK, SLOTS + c * 2, true);
                } else if (!gridApps.isEmpty()) {
                    focus(GRID, Math.min(c / 2, gridApps.size() - 1), true);
                }
                break;
            }
            case GRID: {
                int col = gridIdx % COLS, row = gridIdx / COLS;
                if (dx != 0) {
                    int nc = col + dx;
                    int n = row * COLS + nc;
                    if (nc >= 0 && nc < COLS && n < gridApps.size()) focus(GRID, n, true);
                } else if (dy < 0) {
                    if (row == 0) {
                        if (pickSlot >= 0) cancelPick();
                        else focus(DOCK, SLOTS + col * 2, true);
                    } else {
                        focus(GRID, gridIdx - COLS, true);
                    }
                } else {
                    int lastRow = (gridApps.size() - 1) / COLS;
                    if (row < lastRow) focus(GRID, Math.min(gridIdx + COLS, gridApps.size() - 1), true);
                }
                break;
            }
            case INPUTS:
                if (dy != 0) {
                    int n = inputIdx + dy;
                    if (n >= 0 && n < inputs.size()) selectInput(n);
                }
                break;
        }
    }

    private void back() {
        if (zone == INPUTS) closeInputs();
        else if (pickSlot >= 0) cancelPick();
        else if (zone == GRID || zone == TOP || zone == BELL) {
            int r = dockIdx / SLOTS;
            focus(DOCK, dockClamp(r, dockIdx % SLOTS), true);
        }
    }

    private void activate() {
        switch (zone) {
            case TOP:
                if (topIdx == 0) openInputs();
                else if (topIdx == 1) openSettings(Settings.ACTION_WIFI_SETTINGS);
                else openSettings(Settings.ACTION_SETTINGS);
                break;
            case BELL:
                saveFocus();
                startActivity(new Intent(this, DoorbellActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case DOCK: {
                AppEntry a = dockAt(dockIdx);
                if (a != null) launch(a);
                else startPick(dockIdx / SLOTS, dockIdx % SLOTS);
                break;
            }
            case GRID:
                if (gridIdx >= gridApps.size()) break;
                if (pickSlot >= 0) finishPick(gridApps.get(gridIdx));
                else launch(gridApps.get(gridIdx));
                break;
            case INPUTS:
                if (inputIdx < inputs.size()) switchInput(inputs.get(inputIdx));
                break;
        }
    }

    // ------------------------------------------------------------------ editing dock slots

    private void startPick(int row, int slot) {
        if (gridApps.isEmpty()) return;
        pickRow = row;
        pickSlot = slot;
        Toast.makeText(this, R.string.pick_app, Toast.LENGTH_LONG).show();
        focus(GRID, 0, true);
    }

    private void finishPick(AppEntry a) {
        int row = pickRow, slot = pickSlot;
        pickSlot = -1;
        saveSlot(row, slot, a.pkg);
        setFocusedLook(GRID, gridIdx, false, false);
        zone = DOCK;
        dockIdx = row * SLOTS + slot;
        loadApps(); // the app moves from the grid into the slot
    }

    private void cancelPick() {
        int row = pickRow, slot = pickSlot;
        pickSlot = -1;
        focus(DOCK, row * SLOTS + slot, true);
    }

    /** Hold OK on a filled dock tile: replace / remove / cancel ("cancel" focused). */
    private void openMenu() {
        AppEntry a = dockAt(dockIdx);
        if (a == null) return;
        slotMenu.removeAllViews();
        TextView head = new TextView(this);
        head.setText(a.label);
        head.setTypeface(light);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        head.setTextColor(0xFFFFFFFF);
        head.setPadding(dp(16), dp(8), dp(16), dp(10));
        slotMenu.addView(head);
        for (int res : new int[]{R.string.replace_app, R.string.remove_app, R.string.cancel}) {
            TextView tv = new TextView(this);
            tv.setText(res);
            tv.setTypeface(regular);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            tv.setTextColor(res == R.string.remove_app ? 0xFFFF8A80 : 0xF2FFFFFF);
            tv.setPadding(dp(16), dp(10), dp(16), dp(10));
            slotMenu.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        slotMenu.setVisibility(View.VISIBLE);
        slotMenu.setAlpha(0f);
        slotMenu.animate().alpha(1f).setDuration(150).start();
        zone = MENU;
        selectMenu(2);
    }

    private void selectMenu(int i) {
        menuIdx = i;
        for (int c = 1; c < slotMenu.getChildCount(); c++) {
            if (c - 1 == i) slotMenu.getChildAt(c).setBackgroundResource(R.drawable.panel_item_sel);
            else slotMenu.getChildAt(c).setBackground(new ColorDrawable(0));
        }
    }

    private void closeMenu() {
        slotMenu.setVisibility(View.GONE);
        zone = DOCK;
        placeHighlight(true, false);
    }

    private void menuAction(int i) {
        int row = dockIdx / SLOTS, slot = dockIdx % SLOTS;
        closeMenu();
        if (i == 0) {
            startPick(row, slot);
        } else if (i == 1) {
            saveSlot(row, slot, "");
            Toast.makeText(this, R.string.slot_cleared, Toast.LENGTH_SHORT).show();
            loadApps(); // the app goes back into the grid
        }
    }

    // ------------------------------------------------------------------ focus

    private void focus(int newZone, int idx, boolean animate) {
        setFocusedLook(zone, currentIndex(), false, animate);
        zone = newZone;
        if (zone == TOP) topIdx = idx;
        else if (zone == DOCK) dockIdx = idx;
        else if (zone == GRID) gridIdx = idx;
        setFocusedLook(zone, idx, true, animate);
        placeHighlight(animate, false);
        scrollPage(animate);
        if (zone == DOCK) showDockTitle(animate);
        if (resumed) doorbell.setOnScreen(zone != GRID);
    }

    /** Re-applies the remembered focus after (re)loading or resuming. */
    private void refocus(boolean animate) {
        for (int i = 0; i < pills.length; i++) setFocusedLook(TOP, i, false, false);
        for (int c = 0; c < COLS; c++) setFocusedLook(DOCK, c, false, false);
        for (int c = 0; c < SLOTS; c++) setFocusedLook(DOCK, SLOTS + c, false, false);
        if (zone == BELL && !bellVisible()) zone = DOCK;
        int idx = currentIndex();
        setFocusedLook(zone, idx, true, false);
        showDockTitle(false);
        placeHighlight(animate, false);
        scrollPage(animate);
    }

    private int currentIndex() {
        return zone == TOP ? topIdx : zone == DOCK || zone == MENU ? dockIdx : zone == GRID ? gridIdx
                : zone == BELL ? 0 : inputIdx;
    }

    private static float scaleFor(int z) {
        return z == TOP ? PILL_SCALE : z == BELL ? BELL_SCALE : TILE_SCALE;
    }

    private boolean bellVisible() {
        return bellCard.getVisibility() == View.VISIBLE;
    }

    /** The view the highlight frames. */
    private View cardFor(int z, int idx) {
        if (z == BELL) return bellVisible() ? bellCard : null;
        if (z == TOP || z == INPUTS) return idx >= 0 && idx < pills.length ? pills[z == INPUTS ? 0 : idx] : null;
        if (z == DOCK) {
            int r = idx / SLOTS, c = idx % SLOTS;
            if (c >= rowLen(r) || c >= dockRowViews[r].getChildCount()) return null;
            return dockRowViews[r].getChildAt(c).findViewById(R.id.card);
        }
        View v = gridLayout.findViewByPosition(idx);
        return v == null ? null : v.findViewById(R.id.card);
    }

    /** The view that scales when focused (tile holder incl. its colour ring). */
    private View scalerFor(int z, int idx) {
        View card = cardFor(z, idx);
        if (card == null) return null;
        return (z == DOCK || z == GRID) ? (View) card.getParent() : card;
    }

    private void setFocusedLook(int z, int idx, boolean focused, boolean animate) {
        if (z == INPUTS || z == MENU) return;
        View v = scalerFor(z, idx);
        if (v == null) return;
        float s = focused ? scaleFor(z) : 1f;
        if (animate) v.animate().scaleX(s).scaleY(s).setDuration(180).start();
        else {
            v.setScaleX(s);
            v.setScaleY(s);
        }
        if (z == GRID) {
            View tile = gridLayout.findViewByPosition(idx);
            if (tile != null) {
                View label = tile.findViewById(R.id.label);
                if (animate) label.animate().alpha(focused ? 1f : 0f).setDuration(180).start();
                else label.setAlpha(focused ? 1f : 0f);
            }
        }
    }

    private final Rect tmp = new Rect();
    private final RectF tmpF = new RectF();

    private void placeHighlight(boolean animate, boolean onlyIfMoved) {
        int z = zone == INPUTS ? TOP : zone == MENU ? DOCK : zone;
        View card = cardFor(z, zone == INPUTS ? 0 : currentIndex());
        if (card == null || card.getWidth() == 0) return;
        tmp.set(0, 0, card.getWidth(), card.getHeight());
        ((ViewGroup) page).offsetDescendantRectToMyCoords(card, tmp);
        float s = scaleFor(z);
        float dx = tmp.width() * (s - 1f) / 2f, dy = tmp.height() * (s - 1f) / 2f;
        tmpF.set(tmp.left - dx, tmp.top - dy, tmp.right + dx, tmp.bottom + dy);
        if (onlyIfMoved && tmpF.equals(highlight.target())) return;
        boolean small = z == DOCK && dockIdx / SLOTS == 1;
        float radius = z == TOP ? tmpF.height() / 2f : dp(small ? 8 : 10) * s;
        highlight.moveTo(tmpF, radius, animate && !onlyIfMoved);
    }

    private void scrollPage(boolean animate) {
        float ty = 0f;
        if (zone == GRID) {
            int row = gridIdx / COLS;
            View first = gridLayout.findViewByPosition(Math.max(0, row - 1) * COLS);
            if (first != null) {
                // Focused row sits under one visible row (or at the top for row 0)
                ty = -(grid.getTop() + first.getTop() - dp(row == 0 ? 52 : 40));
                float max = content.getHeight() - root.getHeight();
                ty = Math.max(ty, -Math.max(max, 0f));
                if (row == 0) ty = Math.min(ty, -(header.getHeight() - dp(52) - grid.getPaddingTop()));
            }
        }
        if (animate) {
            page.animate().translationY(ty).setDuration(340)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        } else {
            page.animate().cancel();
            page.setTranslationY(ty);
        }
    }

    private void showDockTitle(boolean animate) {
        if (zone != DOCK && !title.getText().toString().isEmpty()) return;
        AppEntry a = dockAt(dockIdx);
        String text = a != null ? a.label : getString(R.string.add_app);
        int colour = a != null ? a.glow : 0xFF5A5A5E;
        if (animate && !text.contentEquals(title.getText())) {
            title.animate().cancel();
            title.animate().alpha(0f).setDuration(90).withEndAction(() -> {
                title.setText(text);
                title.animate().alpha(1f).setDuration(160).start();
            }).start();
        } else {
            title.setText(text);
            title.setAlpha(1f);
        }
        animateGlow(colour, animate);
    }

    private void animateGlow(int to, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        if (!animate) {
            setGlow(to);
            return;
        }
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(va -> setGlow((int) va.getAnimatedValue()));
        glowAnim.start();
    }

    private void setGlow(int c) {
        glowColor = c;
        glowDrawable.setColors(new int[]{withAlpha(c, 0x5C), withAlpha(c, 0x24), withAlpha(c, 0)});
    }

    private void saveFocus() {
        prefs.edit().putInt("zone", zone == INPUTS ? TOP : zone == MENU ? DOCK : zone).putInt("top", topIdx)
                .putInt("dock2pos", dockIdx).putInt("grid", gridIdx).apply();
    }

    // ------------------------------------------------------------------ actions

    private void launch(AppEntry a) {
        saveFocus();
        try {
            startActivity(a.launch);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Intent alt = getPackageManager().getLaunchIntentForPackage(a.pkg);
            if (alt != null) {
                try {
                    startActivity(alt);
                    return;
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(this, getString(R.string.cannot_open, a.label), Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ex) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ------------------------------------------------------------------ inputs dropdown

    private void openInputs() {
        inputs.clear();
        TvInputManager tim = getSystemService(TvInputManager.class);
        List<InputItem> hdmi = new ArrayList<>(), other = new ArrayList<>();
        InputItem tuner = null;
        if (tim != null) {
            // HDMI-CEC devices (e.g. the AV receiver) hang off their port as child inputs
            Map<String, String> cecNames = new HashMap<>();
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getParentId() != null)
                    cecNames.put(info.getParentId(), String.valueOf(info.loadLabel(this)));
            }
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getParentId() != null) continue;
                CharSequence custom = info.loadCustomLabel(this);
                String label = String.valueOf(custom != null && custom.length() > 0 ? custom : info.loadLabel(this));
                String cec = cecNames.get(info.getId());
                if (cec != null && !cec.isEmpty() && !label.contains(cec)) label += "  ·  " + cec;
                int type = info.getType();
                if (type == TvInputInfo.TYPE_TUNER) {
                    // The built-in tuner; apps such as VLC register "tuner" previews too
                    if (tuner == null && isSystem(info))
                        tuner = new InputItem(info.getId(), getString(R.string.live_tv), true);
                } else if (info.isPassthroughInput()) {
                    if (type == TvInputInfo.TYPE_HDMI) hdmi.add(new InputItem(info.getId(), label, false));
                    else other.add(new InputItem(info.getId(), label, false));
                }
            }
        }
        hdmi.sort((a, b) -> a.label.compareTo(b.label));
        if (tuner == null) tuner = new InputItem("", getString(R.string.live_tv), true);
        inputs.add(tuner);
        inputs.addAll(hdmi);
        inputs.addAll(other);

        inputsPanel.removeAllViews();
        for (InputItem it : inputs) {
            TextView tv = new TextView(this);
            tv.setText(it.label);
            tv.setTypeface(regular);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tv.setTextColor(0xF2FFFFFF);
            tv.setPadding(dp(16), dp(10), dp(16), dp(10));
            inputsPanel.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        // Drop down from just under the Inputs pill, right-aligned with it
        View pill = pills[0];
        int[] p = new int[2], r = new int[2];
        pill.getLocationInWindow(p);
        root.getLocationInWindow(r);
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) inputsPanel.getLayoutParams();
        lp.topMargin = p[1] - r[1] + pill.getHeight() + dp(12);
        lp.rightMargin = root.getWidth() - (p[0] - r[0] + pill.getWidth());
        inputsPanel.setLayoutParams(lp);
        inputsPanel.setVisibility(View.VISIBLE);
        inputsPanel.setAlpha(0f);
        inputsPanel.setTranslationY(-dp(8));
        inputsPanel.animate().alpha(1f).translationY(0).setDuration(180).start();
        zone = INPUTS;
        selectInput(0);
    }

    private static boolean isSystem(TvInputInfo info) {
        return info.getServiceInfo() != null
                && (info.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private void selectInput(int i) {
        inputIdx = i;
        for (int c = 0; c < inputsPanel.getChildCount(); c++) {
            if (c == i) inputsPanel.getChildAt(c).setBackgroundResource(R.drawable.panel_item_sel);
            else inputsPanel.getChildAt(c).setBackground(new ColorDrawable(0));
        }
    }

    private void closeInputs() {
        if (zone != INPUTS) return;
        inputsPanel.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> inputsPanel.setVisibility(View.GONE)).start();
        zone = TOP;
        topIdx = 0;
        placeHighlight(true, false);
    }

    private void switchInput(InputItem it) {
        closeInputs();
        Intent i;
        if (it.tuner) {
            i = config.liveTvPackage.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(config.liveTvPackage);
            if (i == null && !it.id.isEmpty()) i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(it.id));
            if (i == null) return;
        } else {
            i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.id));
            if (!config.liveTvPackage.isEmpty()) i.setPackage(config.liveTvPackage);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException ex) {
            i.setPackage(null);
            try {
                startActivity(i);
            } catch (ActivityNotFoundException ex2) {
                Toast.makeText(this, getString(R.string.cannot_open, it.label), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------ top-left & network

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res = h < 5 ? R.string.greet_night : h < 12 ? R.string.greet_morning
                : h < 18 ? R.string.greet_afternoon : h < 23 ? R.string.greet_evening : R.string.greet_night;
        greeting.setText(res);
    }

    private void updateNetwork() {
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            netIcon.setImageResource(R.drawable.ic_wifi);
            String ssid = null;
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
            if (wi != null) ssid = wi.getSSID();
            if (ssid == null || ssid.contains("<unknown")) ssid = getString(R.string.wifi);
            netLabel.setText(ssid.replace("\"", ""));
        } else if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            netIcon.setImageResource(R.drawable.ic_ethernet);
            netLabel.setText(R.string.ethernet);
        } else {
            netIcon.setImageResource(R.drawable.ic_wifi);
            netLabel.setText(R.string.offline);
        }
    }

    private void applyWallpaper() {
        String key = config.wallpaper == null ? "" : config.wallpaper.getPath() + config.wallpaper.lastModified();
        if (key.equals(wallpaperKey)) return;
        wallpaperKey = key;
        if (config.wallpaper == null) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            scrim.setVisibility(View.GONE);
            return;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(config.wallpaper.getPath(), o);
        int sample = 1;
        int sw = getResources().getDisplayMetrics().widthPixels;
        while (o.outWidth / (sample * 2) >= sw) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory; fine for a photo
        Bitmap b = BitmapFactory.decodeFile(config.wallpaper.getPath(), o);
        wallpaper.setImageBitmap(b);
        wallpaper.setVisibility(b != null ? View.VISIBLE : View.GONE);
        scrim.setVisibility(b != null ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ util

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int i, int size) {
        return size == 0 ? 0 : Math.max(0, Math.min(i, size - 1));
    }

    private static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
