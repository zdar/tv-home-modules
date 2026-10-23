package io.github.tvhomemodules.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/** Paintings from the old masters up to (Post-)Impressionism, from free museum
 *  open-access collections (public domain / CC0): The Met (European Paintings
 *  highlights), Cleveland Museum of Art (highlights, CC0) and the Art Institute
 *  of Chicago (boosted public-domain paintings, when its API is up); all up to
 *  art_until_year (default 1910). The list of works is refreshed every two
 *  weeks; images are downloaded one at a time and kept in a small cache, so it
 *  keeps going offline. Runs on a background thread. */
final class ArtSource {
    private static final String TAG = "ArtSource";
    private static final String UA = "Mozilla/5.0 (Linux; Android 9; TV) atvlauncher-screensaver";
    private static final long POOL_MAX_AGE = 14L * 24 * 3600 * 1000;
    private static final int CACHE_FILES = 25, RECENT = 80;

    static final class Art {
        final Bitmap bitmap;
        final String title, artist, date, museum;

        Art(Bitmap b, String title, String artist, String date, String museum) {
            this.bitmap = b;
            this.title = title;
            this.artist = artist;
            this.date = date;
            this.museum = museum;
        }
    }

    private final Context ctx;
    private final File poolFile, cacheDir;
    private final SharedPreferences prefs;
    private final Random rnd = new Random();
    private final int maxW, maxH;
    private final int untilYear; // latest year of the works (1910 = up to Post-Impressionism)

    ArtSource(Context c, int screenW, int screenH) {
        ctx = c.getApplicationContext();
        untilYear = Config.load(ctx).artUntilYear;
        poolFile = new File(ctx.getFilesDir(), "art_pool_" + untilYear + ".tsv");
        cacheDir = new File(ctx.getCacheDir(), "art");
        cacheDir.mkdirs();
        prefs = ctx.getSharedPreferences("art", Context.MODE_PRIVATE);
        maxW = screenW;
        maxH = screenH;
    }

    /** Next painting, never one of the last RECENT shown; null if nothing at all is available. */
    Art next() {
        List<String[]> pool = pool();
        LinkedList<String> recent = new LinkedList<>(Arrays.asList(prefs.getString("recent", "").split("\n")));
        Collections.shuffle(pool, rnd);
        for (String[] e : pool) {
            String key = e[0] + ":" + e[1];
            if (recent.contains(key)) continue;
            Art a = fetch(e);
            if (a == null) continue;
            recent.addFirst(key);
            while (recent.size() > RECENT) recent.removeLast();
            prefs.edit().putString("recent", String.join("\n", recent)).apply();
            return a;
        }
        return fromCache(); // offline: show something we already have
    }

    // ------------------------------------------------------------------ the list of works

    /** Entries: source, key, imageUrl ("" = look up), title, artist, date. */
    private List<String[]> pool() {
        List<String[]> out = new ArrayList<>();
        if (poolFile.isFile() && System.currentTimeMillis() - poolFile.lastModified() < POOL_MAX_AGE) read(out);
        if (!out.isEmpty()) return out;
        try {
            met(out);
        } catch (Exception e) {
            Log.w(TAG, "Met: " + e.getMessage());
        }
        try {
            cleveland(out);
        } catch (Exception e) {
            Log.w(TAG, "Cleveland: " + e.getMessage());
        }
        try {
            aic(out);
        } catch (Exception e) {
            Log.w(TAG, "AIC: " + e.getMessage());
        }
        if (out.isEmpty()) {
            read(out); // stale list is better than none
        } else {
            StringBuilder sb = new StringBuilder();
            for (String[] e : out) sb.append(String.join("\t", e)).append('\n');
            write(poolFile, sb.toString());
            Log.i(TAG, "pool: " + out.size() + " works");
        }
        return out;
    }

    private void met(List<String[]> out) throws Exception {
        for (int offset = 0; offset < 1000; offset += 100) {
            JSONObject r = new JSONObject(get("https://collectionapi.metmuseum.org/public/collection/v1.1/search"
                    + "?isHighlight=true&hasImages=true&departmentId=11&q=painting&limit=100&offset=" + offset));
            JSONArray ids = r.optJSONArray("objectIDs");
            if (ids == null || ids.length() == 0) break;
            for (int i = 0; i < ids.length(); i++)
                out.add(new String[]{"met", String.valueOf(ids.getLong(i)), "", "", "", ""});
            if (offset + 100 >= r.optInt("total")) break;
        }
        // The Impressionists and Post-Impressionists beyond the highlight list
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] e : out) seen.add(e[1]);
        for (String artist : IMPRESSIONISTS) {
            try {
                JSONObject r = new JSONObject(get("https://collectionapi.metmuseum.org/public/collection/v1.1/search"
                        + "?hasImages=true&departmentId=11&limit=25&offset=0&q=" + java.net.URLEncoder.encode(artist, "UTF-8")));
                JSONArray ids = r.optJSONArray("objectIDs");
                for (int i = 0; ids != null && i < ids.length(); i++) {
                    String id = String.valueOf(ids.getLong(i));
                    if (seen.add(id)) out.add(new String[]{"met", id, "", "", "", ""});
                }
            } catch (Exception e) {
                Log.w(TAG, "Met " + artist + ": " + e.getMessage());
            }
        }
    }

    private static final String[] IMPRESSIONISTS = {"Claude Monet", "Auguste Renoir", "Edgar Degas", "Edouard Manet",
            "Camille Pissarro", "Alfred Sisley", "Berthe Morisot", "Mary Cassatt", "Paul Cézanne",
            "Vincent van Gogh", "Paul Gauguin", "Georges Seurat"};

    private void cleveland(List<String[]> out) throws Exception {
        JSONObject r = new JSONObject(get("https://openaccess-api.clevelandart.org/api/artworks/"
                + "?type=Painting&cc0=1&has_image=1&highlight=1&created_before=" + untilYear + "&limit=200"));
        JSONArray d = r.getJSONArray("data");
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.getJSONObject(i);
            JSONObject img = o.optJSONObject("images");
            JSONObject print = img == null ? null : img.optJSONObject("print");
            if (print == null) continue;
            JSONArray cr = o.optJSONArray("creators");
            String artist = cr != null && cr.length() > 0 ? cr.getJSONObject(0).optString("description") : "";
            out.add(new String[]{"cma", String.valueOf(o.optLong("id")), print.optString("url"),
                    clean(o.optString("title")), clean(artist.replaceAll("\\s*\\(.*", "")), clean(o.optString("creation_date"))});
        }
    }

    private void aic(List<String[]> out) throws Exception {
        String q = "https://api.artic.edu/api/v1/artworks/search?limit=100&fields=id,title,artist_title,date_display,image_id"
                + "&query[bool][must][][term][is_public_domain]=true&query[bool][must][][term][is_boosted]=true"
                + "&query[bool][must][][match][classification_title]=painting&query[bool][must][][range][date_end][lte]=" + untilYear;
        JSONArray d = new JSONObject(get(q)).getJSONArray("data");
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.getJSONObject(i);
            String iid = o.optString("image_id", "");
            if (iid.isEmpty() || "null".equals(iid)) continue;
            out.add(new String[]{"aic", String.valueOf(o.optLong("id")),
                    "https://www.artic.edu/iiif/2/" + iid + "/full/1686,/0/default.jpg",
                    clean(o.optString("title")), clean(o.optString("artist_title")), clean(o.optString("date_display"))});
        }
    }

    // ------------------------------------------------------------------ images

    private Art fetch(String[] e) {
        try {
            String url = e[2], title = e[3], artist = e[4], date = e[5], museum;
            if ("met".equals(e[0])) {
                JSONObject o = new JSONObject(get("https://collectionapi.metmuseum.org/public/collection/v1/objects/" + e[1]));
                if (!o.optBoolean("isPublicDomain") || o.optInt("objectEndDate", 9999) > untilYear) return null;
                url = o.optString("primaryImage");
                title = clean(o.optString("title"));
                artist = clean(o.optString("artistDisplayName"));
                date = clean(o.optString("objectDate"));
                museum = "The Metropolitan Museum of Art";
            } else {
                museum = "cma".equals(e[0]) ? "The Cleveland Museum of Art" : "The Art Institute of Chicago";
            }
            if (url == null || url.isEmpty()) return null;
            File f = new File(cacheDir, (e[0] + "_" + e[1]).replaceAll("\\W", "_") + ".jpg");
            if (!f.isFile()) download(url, f);
            write(new File(f.getPath() + ".txt"), title + "\n" + artist + "\n" + date + "\n" + museum);
            trimCache();
            Bitmap b = decode(f);
            return b == null ? null : new Art(b, title, artist, date, museum);
        } catch (Exception ex) {
            Log.w(TAG, e[0] + " " + e[1] + ": " + ex.getMessage());
            return null;
        }
    }

    /** A painting already on disk (instant, no network), or null if the cache is empty. */
    Art cached() {
        return fromCache();
    }

    private Art fromCache() {
        File[] files = cacheDir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null || files.length == 0) return null;
        File f = files[rnd.nextInt(files.length)];
        Bitmap b = decode(f);
        if (b == null) return null;
        String[] m = readText(new File(f.getPath() + ".txt")).split("\n", -1);
        return new Art(b, m.length > 0 ? m[0] : "", m.length > 1 ? m[1] : "", m.length > 2 ? m[2] : "", m.length > 3 ? m[3] : "");
    }

    /** Decoded no larger than the screen (originals can be 4000+ px). */
    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) {
            f.delete();
            return null;
        }
        int s = 1;
        while (o.outWidth / (s * 2) >= maxW || o.outHeight / (s * 2) >= maxH) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), d);
        if (b == null) return null;
        float k = Math.min(1f, Math.min(maxW / (float) b.getWidth(), maxH / (float) b.getHeight()));
        if (k < 1f) {
            Bitmap scaled = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
            if (scaled != b) b.recycle();
            b = scaled;
        }
        return b;
    }

    private void trimCache() {
        File[] files = cacheDir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null || files.length <= CACHE_FILES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - CACHE_FILES; i++) {
            new File(files[i].getPath() + ".txt").delete();
            files[i].delete();
        }
    }

    // ------------------------------------------------------------------ io

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("AIC-User-Agent", "atvlauncher (home TV screensaver)");
        if (c.getResponseCode() >= 400) throw new IllegalStateException("HTTP " + c.getResponseCode());
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection c = open(url);
        long len = c.getContentLengthLong();
        if (len > 25L * 1024 * 1024) throw new IllegalStateException("too large: " + len);
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(to)) throw new IllegalStateException("rename failed");
    }

    private void read(List<String[]> out) {
        for (String line : readText(poolFile).split("\n")) {
            String[] p = line.split("\t", -1);
            if (p.length == 6) out.add(p);
        }
    }

    private static String readText(File f) {
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static void write(File f, String s) {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String clean(String s) {
        return s == null || "null".equals(s) ? "" : s.replace('\t', ' ').replace('\n', ' ').trim();
    }
}
