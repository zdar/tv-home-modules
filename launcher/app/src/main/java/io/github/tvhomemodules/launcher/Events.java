package io.github.tvhomemodules.launcher;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Doorbell events: RING, MOTION, PERSON from two sources at once
 *   1. ONVIF PullPoint subscription straight to the doorbell (long-poll, no polling loop)
 *   2. Home Assistant as a backup: POST http://tv:8765/event {"type":"ring"} + X-Token
 *  The same event from both sources within DEDUP_MS counts once. What each event
 *  does is set in launcher.properties (on_ring / on_motion / on_person). */
final class Events {
    static final String RING = "ring", MOTION = "motion", PERSON = "person", PET = "pet", VEHICLE = "vehicle";

    static boolean known(String t) {
        return RING.equals(t) || MOTION.equals(t) || PERSON.equals(t) || PET.equals(t) || VEHICLE.equals(t);
    }
    private static final String TAG = "DoorbellEvents";
    private static final long DEDUP_MS = 15000;

    interface Sink {
        void onAction(String action, String event);
    }

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> lastSeen = new HashMap<>();
    private static volatile boolean running;
    private static Thread onvif, http;
    private static ServerSocket server;
    private static Context app;
    private static Sink sink;

    static synchronized void start(Context c, Sink s) {
        if (running) return;
        running = true;
        app = c.getApplicationContext();
        sink = s;
        onvif = new Thread(Events::onvifLoop, "onvif-events");
        http = new Thread(Events::httpLoop, "ha-events");
        onvif.setDaemon(true);
        http.setDaemon(true);
        onvif.start();
        http.start();
    }

    static synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        if (onvif != null) onvif.interrupt();
    }

    // Ongoing ONVIF states (topic -> active), so the full-screen view stays open
    // while motion / a person is still in front of the door
    private static final Map<String, Boolean> activeTopics = new HashMap<>();

    static boolean anyActive() {
        synchronized (activeTopics) {
            return activeTopics.containsValue(Boolean.TRUE);
        }
    }

    /** Entry point for both sources. */
    static void dispatch(String event, String source) {
        ui.post(() -> { // any event keeps an open doorbell view (full screen or PiP) open
            DoorbellActivity.doorbellActivity();
            KeyHelperService.pipActivity();
            MainActivity.doorbellEvent(event); // flash the Home tile
        });
        long now = SystemClock.elapsedRealtime();
        synchronized (lastSeen) {
            Long prev = lastSeen.get(event);
            if (prev != null && now - prev < DEDUP_MS) {
                Log.i(TAG, event + " from " + source + " (duplicate, ignored)");
                return;
            }
            lastSeen.put(event, now);
        }
        Config cfg = Config.load(app);
        String action = cfg.eventAction(event);
        Log.i(TAG, event + " from " + source + " -> " + action);
        if (!"none".equals(action) && sink != null) ui.post(() -> sink.onAction(action, event));
    }

    // ------------------------------------------------------------------ ONVIF

    private static void onvifLoop() {
        long backoff = 5000;
        while (running) {
            try {
                Config cfg = Config.load(app);
                Uri u = Uri.parse(cfg.doorbellUrl);
                String userInfo = u.getEncodedUserInfo();
                if (cfg.doorbellUrl.isEmpty() || userInfo == null || !cfg.onvifEnabled) {
                    sleep(60000); // nothing configured yet; look again in a minute
                    continue;
                }
                int colon = userInfo.indexOf(':');
                String user = Uri.decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
                String pass = colon < 0 ? "" : Uri.decode(userInfo.substring(colon + 1));
                String base = "http://" + u.getHost() + ":" + cfg.onvifPort;
                long offset = cameraClockOffset(base + "/onvif/device_service");
                String events = base + "/onvif/event_service";
                String sub = createPullPoint(events, user, pass, offset);
                Log.i(TAG, "ONVIF subscribed");
                backoff = 5000;
                long renewAt = SystemClock.elapsedRealtime() + 8 * 60_000;
                while (running && SystemClock.elapsedRealtime() < renewAt) {
                    String resp = soap(sub, header(user, pass, offset,
                            "http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest", sub)
                            + "<s:Body><PullMessages xmlns=\"http://www.onvif.org/ver10/events/wsdl\">"
                            + "<Timeout>PT30S</Timeout><MessageLimit>20</MessageLimit></PullMessages></s:Body>", 45000);
                    parseMessages(resp);
                }
                try { // tidy up; a new subscription follows
                    soap(sub, header(user, pass, offset, "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest", sub)
                            + "<s:Body><Unsubscribe xmlns=\"http://docs.oasis-open.org/wsn/b-2\"/></s:Body>", 5000);
                } catch (Exception ignored) {
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.w(TAG, "ONVIF: " + e.getMessage() + " (retry in " + backoff / 1000 + " s)");
                sleep(backoff);
                backoff = Math.min(backoff * 2, 120000);
            }
        }
    }

    private static final Pattern MSG = Pattern.compile("<(?:\\w+:)?NotificationMessage>(.*?)</(?:\\w+:)?NotificationMessage>", Pattern.DOTALL);
    private static final Pattern TOPIC = Pattern.compile("<(?:\\w+:)?Topic[^>]*>([^<]+)<");
    private static final Pattern ITEM = Pattern.compile("<(?:\\w+:)?SimpleItem[^>]*Name=\"(\\w+)\"[^>]*Value=\"(\\w+)\"");

    /** Reolink topics: RuleEngine/MyRuleDetector/Visitor (doorbell button),
     *  RuleEngine/CellMotionDetector/Motion (IsMotion), RuleEngine/MyRuleDetector/PeopleDetect. */
    private static void parseMessages(String xml) {
        Matcher m = MSG.matcher(xml);
        while (m.find()) {
            String msg = m.group(1);
            Matcher t = TOPIC.matcher(msg);
            String topic = t.find() ? t.group(1).trim() : "?";
            boolean active = false;
            Matcher it = ITEM.matcher(msg);
            while (it.find()) if ("true".equalsIgnoreCase(it.group(2))) active = true;
            synchronized (activeTopics) {
                activeTopics.put(topic, active);
            }
            if (msg.contains("PropertyOperation=\"Initialized\"")) continue; // state at subscribe time, not an event
            Log.d(TAG, "ONVIF " + topic + " active=" + active);
            if (!active) continue;
            String lower = topic.toLowerCase(Locale.ROOT);
            if (lower.contains("visitor") || lower.contains("doorbell")) dispatch(RING, "onvif");
            else if (lower.contains("people") || lower.contains("person") || lower.contains("human")) dispatch(PERSON, "onvif");
            else if (lower.contains("dogcat") || lower.contains("pet") || lower.contains("animal")) dispatch(PET, "onvif");
            else if (lower.contains("vehicle") || lower.contains("car")) dispatch(VEHICLE, "onvif");
            else if (lower.contains("motion")) dispatch(MOTION, "onvif");
        }
    }

    private static String createPullPoint(String events, String user, String pass, long offset) throws Exception {
        String resp = soap(events, header(user, pass, offset,
                "http://www.onvif.org/ver10/events/wsdl/EventPortType/CreatePullPointSubscriptionRequest", events)
                + "<s:Body><CreatePullPointSubscription xmlns=\"http://www.onvif.org/ver10/events/wsdl\">"
                + "<InitialTerminationTime>PT600S</InitialTerminationTime></CreatePullPointSubscription></s:Body>", 10000);
        Matcher m = Pattern.compile("<(?:\\w+:)?Address>([^<]+)</").matcher(resp);
        if (!m.find()) throw new IllegalStateException("no subscription address");
        return m.group(1).trim();
    }

    /** Camera UTC minus our clock, so the WS-Security timestamp is accepted. */
    private static long cameraClockOffset(String device) {
        try {
            String r = soap(device, "<s:Body><GetSystemDateAndTime xmlns=\"http://www.onvif.org/ver10/device/wsdl\"/></s:Body>", 5000);
            Matcher blk = Pattern.compile("UTCDateTime>(.*?)</(?:\\w+:)?UTCDateTime", Pattern.DOTALL).matcher(r);
            if (!blk.find()) return 0;
            String b = blk.group(1);
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.set(num(b, "Year"), num(b, "Month") - 1, num(b, "Day"), num(b, "Hour"), num(b, "Minute"), num(b, "Second"));
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis() - System.currentTimeMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int num(String b, String tag) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + tag + ">(\\d+)<").matcher(b);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String header(String user, String pass, long offset, String action, String to) throws Exception {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String created = f.format(System.currentTimeMillis() + offset);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(nonce);
        sha1.update(created.getBytes(StandardCharsets.UTF_8));
        sha1.update(pass.getBytes(StandardCharsets.UTF_8));
        String digest = Base64.encodeToString(sha1.digest(), Base64.NO_WRAP);
        return "<s:Header>"
                + "<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">"
                + "<UsernameToken><Username>" + xml(user) + "</Username>"
                + "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + digest + "</Password>"
                + "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"
                + Base64.encodeToString(nonce, Base64.NO_WRAP) + "</Nonce>"
                + "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" + created + "</Created>"
                + "</UsernameToken></Security>"
                + "<a:Action s:mustUnderstand=\"1\">" + action + "</a:Action>"
                + "<a:To s:mustUnderstand=\"1\">" + xml(to) + "</a:To>"
                + "</s:Header>";
    }

    private static String soap(String url, String headerAndBody, int timeoutMs) throws Exception {
        String env = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://www.w3.org/2005/08/addressing\">"
                + headerAndBody + "</s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) {
            o.write(env.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = in == null ? "" : read(in);
        c.disconnect();
        if (code >= 400) {
            Matcher m = Pattern.compile("<(?:\\w+:)?Text[^>]*>([^<]+)<").matcher(body);
            throw new IllegalStateException("HTTP " + code + (m.find() ? " " + m.group(1) : ""));
        }
        return body;
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------------ Home Assistant backup

    /** Tiny HTTP listener: POST /event with header X-Token (or ?token=), type as
     *  ?type=ring or JSON {"type":"ring"}. Off unless event_token is set. */
    private static void httpLoop() {
        while (running) {
            Config cfg = Config.load(app);
            if (cfg.eventToken.isEmpty()) {
                sleep(60000);
                continue;
            }
            try (ServerSocket ss = new ServerSocket()) {
                server = ss;
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(cfg.eventPort));
                Log.i(TAG, "HA listener on port " + cfg.eventPort);
                while (running) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(3000);
                        handle(s);
                    } catch (Exception e) {
                        if (!running) return;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "HA listener: " + e.getMessage());
                sleep(10000);
            }
        }
    }

    private static void handle(Socket s) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String request = r.readLine();
        if (request == null) return;
        String token = "", line;
        int len = 0;
        while ((line = r.readLine()) != null && !line.isEmpty()) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("x-token:")) token = line.substring(8).trim();
            if (l.startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
        }
        char[] body = new char[Math.min(len, 1024)];
        int got = 0;
        while (got < body.length) {
            int n = r.read(body, got, body.length - got);
            if (n < 0) break;
            got += n;
        }
        String path = request.split(" ").length > 1 ? request.split(" ")[1] : "/";
        Map<String, String> q = new HashMap<>();
        int qi = path.indexOf('?');
        if (qi >= 0) {
            for (String kv : path.substring(qi + 1).split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) q.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            }
            path = path.substring(0, qi);
        }
        if (token.isEmpty() && q.containsKey("token")) token = q.get("token");
        String type = q.get("type");
        if (type == null) {
            Matcher m = Pattern.compile("\"type\"\\s*:\\s*\"(\\w+)\"").matcher(new String(body, 0, got));
            if (m.find()) type = m.group(1);
        }
        Config cfg = Config.load(app);
        int status;
        if (!"/event".equals(path)) status = 404;
        else if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), cfg.eventToken.getBytes(StandardCharsets.UTF_8))) status = 403;
        else if (type == null || !known(type)) status = 400;
        else {
            dispatch(type, "ha");
            status = 200;
        }
        String reply = status == 200 ? "ok" : status == 403 ? "forbidden" : status == 404 ? "not found" : "unknown type";
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 " + status + " X\r\nContent-Type: text/plain\r\nContent-Length: " + reply.length()
                + "\r\nConnection: close\r\n\r\n" + reply).getBytes(StandardCharsets.UTF_8));
        o.flush();
    }
}
