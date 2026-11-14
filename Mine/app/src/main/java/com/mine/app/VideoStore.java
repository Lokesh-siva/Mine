package com.mine.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Exercise videos the user picks or records. They are copied into the app's private storage
 * (never backed up, never leave the phone) and streamed to the page at __video/<exercise>.mp4.
 */
final class VideoStore {
    static final String PATH = "/android_asset/__video/";
    private static final String PREFS = "mine_videos";

    private VideoStore() { }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "videos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String safe(String key) {
        String k = key == null ? "" : key.toLowerCase().replaceAll("[^a-z0-9-]", "");
        return k.isEmpty() ? "video" : k;
    }

    private static File file(Context c, String key) {
        return new File(dir(c), safe(key) + ".bin");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Copies the picked video in. Runs off the main thread: videos can be large. */
    static boolean save(Context c, Uri src, String key) {
        File tmp = new File(dir(c), safe(key) + ".tmp"), out = file(c, key);
        try (InputStream in = c.getContentResolver().openInputStream(src); OutputStream os = new FileOutputStream(tmp)) {
            if (in == null) return false;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) return false;
        String mime = c.getContentResolver().getType(src);
        if (mime == null || !mime.startsWith("video/")) mime = "video/mp4";
        prefs(c).edit().putString(safe(key), mime).apply();
        return true;
    }

    static boolean delete(Context c, String key) {
        prefs(c).edit().remove(safe(key)).apply();
        return file(c, key).delete();
    }

    /** {"exercise": {"size": bytes, "t": modified ms}} */
    static String list(Context c) {
        JSONObject o = new JSONObject();
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (!n.endsWith(".bin")) continue;
            try {
                JSONObject v = new JSONObject();
                v.put("size", f.length());
                v.put("t", f.lastModified());
                o.put(n.substring(0, n.length() - 4), v);
            } catch (Exception ignored) { }
        }
        return o.toString();
    }

    /** Serves a saved video, honouring Range requests so the player can seek. */
    static WebResourceResponse serve(Context c, Uri u, Map<String, String> reqHeaders) {
        String name = u.getLastPathSegment() == null ? "" : u.getLastPathSegment();
        String key = name.endsWith(".mp4") ? name.substring(0, name.length() - 4) : name;
        File f = file(c, key);
        if (!f.exists()) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
        String range = null;
        if (reqHeaders != null) for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
            if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
        }
        long len = f.length(), start = 0, end = len - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            try {
                String[] p = range.substring(6).split("-", 2);
                if (!p[0].isEmpty()) start = Long.parseLong(p[0].trim());
                if (p.length > 1 && !p[1].trim().isEmpty()) end = Math.min(Long.parseLong(p[1].trim()), len - 1);
                partial = start > 0 || end < len - 1;
            } catch (NumberFormatException ignored) {
                start = 0;
                end = len - 1;
            }
        }
        if (start > end) start = 0;
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Content-Length", String.valueOf(end - start + 1));
        h.put("Cache-Control", "no-store");
        if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
        try {
            InputStream in = new FileInputStream(f);
            long skip = start;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) break;
                skip -= s;
            }
            String mime = prefs(c).getString(safe(key), "video/mp4");
            return new WebResourceResponse(mime, null, partial ? 206 : 200, partial ? "Partial Content" : "OK", h, new Limited(in, end - start + 1));
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 500, "Error", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
    }

    /** Stops reading after the requested byte range. */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long n) {
            super(in);
            left = n;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
