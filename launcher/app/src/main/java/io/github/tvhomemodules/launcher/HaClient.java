package io.github.tvhomemodules.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** One persistent connection to Home Assistant's WebSocket API. Lights carrying
 *  the HA label ha_label (default "TV"; all lights if no light has it) are kept
 *  in sync from state_changed events, so the panel never polls. */
final class HaClient {
    private static final String TAG = "HaClient";

    static final class Light {
        String id, name, area = "";
        boolean on, available = true, dimmable;
        int pct;  // brightness 0-100
        int tier; // rooms in ha_rooms order, then same floor, then other floors (farther = higher)
        boolean motion;   // motion-controlled (ha_motion_label): shown quieter, last in its room
        boolean farFloor; // on another floor than the TV: shown dimmed
        boolean tvRoom;
        String floor = ""; // HA floor name (shown as a heading for the other floors)
    }

    interface Listener {
        void onLights(List<Light> lights, String status);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS).build();
    private final Context ctx;
    private final Listener listener;
    private WebSocket ws;
    private boolean running;
    private long backoff = 5000;
    private int nextId = 1;
    private String status = "";

    // Registry data collected after auth, then the light list built from it
    private final Map<Integer, String> pending = new HashMap<>();
    private JSONArray entityReg, deviceReg, areaReg, labelReg, floorReg, states;
    private String rejectedToken; // don't retry a token HA has refused
    private final LinkedHashMap<String, Light> lights = new LinkedHashMap<>();

    HaClient(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    void start() {
        running = true;
        connect();
    }

    void stop() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        if (ws != null) ws.close(1000, "bye");
        ws = null;
    }

    boolean configured() {
        return !Config.load(ctx).haToken.isEmpty();
    }

    List<Light> snapshot() {
        return new ArrayList<>(lights.values());
    }

    String status() {
        return status;
    }

    private void connect() {
        Config c = Config.load(ctx);
        if (!running) return;
        if (c.haToken.isEmpty() || c.haToken.equals(rejectedToken)) {
            setStatus(c.haToken.isEmpty() ? "no_token" : "bad_token");
            ui.postDelayed(this::connect, 60_000); // waiting for tools/set_ha.py
            return;
        }
        String url = c.haUrl.replaceFirst("^http", "ws").replaceAll("/+$", "") + "/api/websocket";
        ws = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onMessage(@NonNull WebSocket s, @NonNull String text) {
                try {
                    handle(s, new JSONObject(text), c);
                } catch (Exception e) {
                    Log.w(TAG, "message: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket s, @NonNull Throwable t, Response r) {
                Log.w(TAG, "connection: " + t.getMessage());
                retry();
            }

            @Override
            public void onClosed(@NonNull WebSocket s, int code, @NonNull String reason) {
                retry();
            }
        });
    }

    private void retry() {
        if (!running) return;
        setStatus("offline");
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(this::connect, backoff);
        backoff = Math.min(backoff * 2, 300_000);
    }

    private void send(WebSocket s, String what, JSONObject msg) throws Exception {
        int id = nextId++;
        msg.put("id", id);
        pending.put(id, what);
        s.send(msg.toString());
    }

    private void handle(WebSocket s, JSONObject m, Config cfg) throws Exception {
        String token = cfg.haToken;
        String type = m.optString("type");
        switch (type) {
            case "auth_required":
                s.send(new JSONObject().put("type", "auth").put("access_token", token).toString());
                break;
            case "auth_invalid":
                Log.w(TAG, "token rejected");
                rejectedToken = token; // wait for a new one instead of hammering HA
                setStatus("bad_token");
                s.close(1000, "auth");
                break;
            case "auth_ok":
                backoff = 5000;
                fetchAll(s);
                send(s, "events", new JSONObject().put("type", "subscribe_events").put("event_type", "state_changed"));
                // Labels, areas, floors or entities edited in HA -> rebuild the list
                for (String ev : new String[]{"entity_registry_updated", "area_registry_updated",
                        "label_registry_updated", "floor_registry_updated", "device_registry_updated"})
                    send(s, "events", new JSONObject().put("type", "subscribe_events").put("event_type", ev));
                break;
            case "result": {
                String what = pending.remove(m.optInt("id"));
                JSONArray r = m.optJSONArray("result");
                if (what == null) break;
                if (r == null) r = new JSONArray(); // e.g. no floors defined
                switch (what) {
                    case "entities": entityReg = r; break;
                    case "devices": deviceReg = r; break;
                    case "areas": areaReg = r; break;
                    case "labels": labelReg = r; break;
                    case "floors": floorReg = r; break;
                    case "states": states = r; break;
                    default: return;
                }
                if (entityReg != null && deviceReg != null && areaReg != null && labelReg != null
                        && floorReg != null && states != null)
                    build(cfg);
                break;
            }
            case "event": {
                JSONObject d = m.optJSONObject("event") == null ? null : m.getJSONObject("event").optJSONObject("data");
                if (d == null) break;
                String evType = m.getJSONObject("event").optString("event_type");
                if (evType.endsWith("_registry_updated")) {
                    ui.removeCallbacks(refetch);
                    ui.postDelayed(refetch, 1500); // edits come in bursts
                    break;
                }
                Light l = lights.get(d.optString("entity_id"));
                JSONObject ns = d.optJSONObject("new_state");
                if (l != null && ns != null) {
                    apply(l, ns);
                    publish();
                }
                break;
            }
        }
    }

    /** Lights with the label (or all lights), nearest to the TV first: rooms in
     *  ha_rooms order, then other rooms on the TV's floor, then other floors by
     *  distance (dimmed). Within a room: manual lights, then motion-controlled. */
    private void build(Config cfg) throws Exception {
        Set<String> showIds = labelIds(cfg.haLabel), motionIds = labelIds(cfg.haMotionLabel);
        Map<String, String> areaName = new HashMap<>(), deviceArea = new HashMap<>(), areaFloor = new HashMap<>();
        Map<String, Integer> floorLevel = new HashMap<>();
        Map<String, String> floorName = new HashMap<>();
        for (int i = 0; i < floorReg.length(); i++) {
            JSONObject f = floorReg.getJSONObject(i);
            floorLevel.put(f.optString("floor_id"), f.isNull("level") ? i : f.optInt("level"));
            floorName.put(f.optString("floor_id"), f.optString("name"));
        }
        String tvAreaId = null;
        for (int i = 0; i < areaReg.length(); i++) {
            JSONObject a = areaReg.getJSONObject(i);
            String id = a.optString("area_id");
            areaName.put(id, a.optString("name"));
            areaFloor.put(id, a.isNull("floor_id") ? null : a.optString("floor_id"));
            if (cfg.haTvArea.equalsIgnoreCase(a.optString("name")) || cfg.haTvArea.equalsIgnoreCase(id)) tvAreaId = id;
        }
        String tvFloor = tvAreaId == null ? null : areaFloor.get(tvAreaId);
        int minLevel = floorLevel.isEmpty() ? 0 : java.util.Collections.min(floorLevel.values());
        Integer tvLevel = tvFloor == null ? null : floorLevel.get(tvFloor);
        for (int i = 0; i < deviceReg.length(); i++)
            deviceArea.put(deviceReg.getJSONObject(i).optString("id"), deviceReg.getJSONObject(i).optString("area_id"));
        Map<String, JSONObject> stateOf = new HashMap<>();
        for (int i = 0; i < states.length(); i++) stateOf.put(states.getJSONObject(i).optString("entity_id"), states.getJSONObject(i));

        List<Light> tagged = new ArrayList<>(), all = new ArrayList<>();
        for (int i = 0; i < entityReg.length(); i++) {
            JSONObject e = entityReg.getJSONObject(i);
            String id = e.optString("entity_id");
            if (!id.startsWith("light.") || !e.isNull("disabled_by") || !e.isNull("hidden_by")) continue;
            JSONObject st = stateOf.get(id);
            if (st == null) continue;
            Light l = new Light();
            l.id = id;
            String area = e.isNull("area_id") ? deviceArea.get(e.optString("device_id")) : e.optString("area_id");
            if (area != null && area.equals("null")) area = null;
            l.area = area == null ? "" : areaName.getOrDefault(area, "");
            l.tvRoom = area != null && area.equals(tvAreaId);
            String fl = area == null ? null : areaFloor.get(area);
            Integer lv = fl == null ? null : floorLevel.get(fl);
            l.floor = fl == null ? "" : floorName.getOrDefault(fl, "");
            // The floor decides first (TV's floor, then nearer floors), then your room
            // order (ha_rooms) within the floor, then the remaining rooms of that floor.
            int pos = indexOfIgnoreCase(cfg.haRooms, l.area);
            int dist;
            if (l.tvRoom || (fl != null && fl.equals(tvFloor)) || (fl == null && tvFloor == null)) dist = 0;
            else if (lv != null && tvLevel != null) dist = Math.max(1, Math.abs(lv - tvLevel));
            else if (lv != null && tvFloor == null) dist = lv - minLevel;  // no TV room set: lowest floor first
            else dist = 90;                                             // floor unknown: last
            l.farFloor = dist > 0;
            l.tier = l.tvRoom ? -1 : dist * 1000 + (pos >= 0 ? pos : 500);
            JSONArray labels = e.optJSONArray("labels");
            boolean show = false;
            for (int k = 0; labels != null && k < labels.length(); k++) {
                String lab = labels.getString(k);
                if (showIds.contains(lab)) show = true;
                if (motionIds.contains(lab)) l.motion = true;
            }
            apply(l, st);
            all.add(l);
            if (show) tagged.add(l);
        }
        List<Light> use = tagged.isEmpty() ? all : tagged;
        Collator col = Collator.getInstance(Locale.getDefault());
        use.sort((a, b) -> {
            if (a.tier != b.tier) return Integer.compare(a.tier, b.tier);
            int c = col.compare(a.area.isEmpty() ? "￿" : a.area, b.area.isEmpty() ? "￿" : b.area);
            if (c != 0) return c;
            if (a.motion != b.motion) return a.motion ? 1 : -1;        // motion lights last in their room
            return col.compare(a.name, b.name);
        });
        lights.clear();
        for (Light l : use) {
            lights.put(l.id, l);
            Log.d(TAG, "light " + l.id + " | " + l.name + " | " + l.area + " | tier " + l.tier
                    + " | floor " + l.floor + (l.tvRoom ? " | tv" : "") + (l.motion ? " | motion" : "") + (l.farFloor ? " | far" : "")
                    + " | " + (l.on ? "on " + l.pct + "%" : "off") + (l.dimmable ? " | dim" : ""));
        }
        entityReg = deviceReg = states = null; // free the big lists
        Log.i(TAG, "connected: " + lights.size() + " lights" + (tagged.isEmpty() ? " (no '" + cfg.haLabel + "' label: all lights)" : "")
                + ", TV room " + (tvAreaId == null ? "not found" : tvAreaId) + ", floors " + floorReg.length());
        setStatus(tagged.isEmpty() ? "all" : "label");
    }

    private Set<String> labelIds(String label) throws Exception {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < labelReg.length(); i++) {
            JSONObject l = labelReg.getJSONObject(i);
            if (label.equalsIgnoreCase(l.optString("name")) || label.equalsIgnoreCase(l.optString("label_id")))
                ids.add(l.optString("label_id"));
        }
        return ids;
    }

    private static int indexOfIgnoreCase(List<String> list, String s) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).equalsIgnoreCase(s)) return i;
        return -1;
    }

    private void fetchAll(WebSocket s) throws Exception {
        entityReg = deviceReg = areaReg = labelReg = floorReg = states = null;
        send(s, "entities", new JSONObject().put("type", "config/entity_registry/list"));
        send(s, "devices", new JSONObject().put("type", "config/device_registry/list"));
        send(s, "areas", new JSONObject().put("type", "config/area_registry/list"));
        send(s, "labels", new JSONObject().put("type", "config/label_registry/list"));
        send(s, "floors", new JSONObject().put("type", "config/floor_registry/list"));
        send(s, "states", new JSONObject().put("type", "get_states"));
    }

    private final Runnable refetch = () -> {
        WebSocket s = ws;
        if (s == null) return;
        try {
            Log.i(TAG, "HA registry changed: reloading the light list");
            fetchAll(s);
        } catch (Exception e) {
            Log.w(TAG, "refetch: " + e.getMessage());
        }
    };

    private static void apply(Light l, JSONObject st) {
        JSONObject a = st.optJSONObject("attributes");
        String s = st.optString("state");
        l.available = !"unavailable".equals(s) && !"unknown".equals(s);
        l.on = "on".equals(s);
        if (a != null) {
            l.name = shortName(a.optString("friendly_name", l.id), l.area);
            int b = a.optInt("brightness", -1);
            l.pct = b < 0 ? (l.on ? 100 : 0) : Math.round(b * 100f / 255f);
            JSONArray modes = a.optJSONArray("supported_color_modes");
            l.dimmable = false;
            for (int i = 0; modes != null && i < modes.length(); i++)
                if (!"onoff".equals(modes.optString(i))) l.dimmable = true;
        }
        if (!l.on) l.pct = 0;
    }

    static String shortName(String name, String area) {
        if (area == null || area.isEmpty() || name.length() <= area.length()) return name;
        if (!name.regionMatches(true, 0, area, 0, area.length())) return name;
        String rest = name.substring(area.length()).replaceFirst("^[\\s\\-–:]+", "");
        if (rest.isEmpty()) return name;
        return Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
    }

    /** light.toggle / light.turn_on with brightness_step_pct; the UI updates from the event. */
    void call(String service, String entityId, Integer stepPct) {
        WebSocket s = ws;
        if (s == null) return;
        try {
            JSONObject data = new JSONObject().put("entity_id", entityId);
            if (stepPct != null) data.put("brightness_step_pct", stepPct);
            send(s, "call", new JSONObject().put("type", "call_service")
                    .put("domain", "light").put("service", service).put("service_data", data));
        } catch (Exception e) {
            Log.w(TAG, "call: " + e.getMessage());
        }
    }

    private void setStatus(String s) {
        status = s;
        publish();
    }

    private void publish() {
        List<Light> copy = snapshot();
        String st = status;
        ui.post(() -> listener.onLights(copy, st));
    }
}
