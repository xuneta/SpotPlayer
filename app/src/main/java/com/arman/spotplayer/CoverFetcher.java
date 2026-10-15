package com.arman.spotplayer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Online cover search, opt-in only. Deezer's public search API first
 * (no key, single call, 500px covers), then MusicBrainz release lookup with
 * Cover Art Archive front-art URLs as fallback. Every result is a candidate
 * the user confirms — nothing is applied automatically.
 */
public final class CoverFetcher {

    public static final class Candidate {
        public final String imageUrl;
        public final String label;

        Candidate(String imageUrl, String label) {
            this.imageUrl = imageUrl;
            this.label = label;
        }
    }

    public interface SearchCallback {
        void onResult(List<Candidate> covers); // main thread, possibly empty
    }

    public interface BytesCallback {
        void onBytes(byte[] data); // main thread, null on failure
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CoverFetcher() {}

    public static void search(Context ctx, String artist, String title,
                              SearchCallback cb) {
        new Thread(() -> {
            List<Candidate> out = new ArrayList<>();
            try {
                out.addAll(fromDeezer(artist, title));
            } catch (Exception ignored) {}
            if (out.isEmpty()) {
                try {
                    out.addAll(fromMusicBrainzCaa(artist, title));
                } catch (Exception ignored) {}
            }
            final List<Candidate> res = out;
            MAIN.post(() -> {
                try {
                    cb.onResult(res);
                } catch (Throwable ignored) {}
            });
        }).start();
    }

    public static void download(String imageUrl, BytesCallback cb) {
        new Thread(() -> {
            byte[] data = null;
            try {
                data = httpBytes(imageUrl);
            } catch (Exception ignored) {}
            final byte[] out = data;
            MAIN.post(() -> {
                try {
                    cb.onBytes(out);
                } catch (Throwable ignored) {}
            });
        }).start();
    }

    // ----- Deezer (primary) -----

    private static List<Candidate> fromDeezer(String artist, String title) {
        List<Candidate> out = new ArrayList<>();
        try {
            String q = "artist:\"" + safe(artist) + "\" track:\"" + safe(title) + "\"";
            String json = httpGet("https://api.deezer.com/search?q=" + enc(q));
            if (json == null) return out;
            JSONArray data = new JSONObject(json).optJSONArray("data");
            if (data == null) return out;
            for (int i = 0; i < Math.min(data.length(), 6); i++) {
                JSONObject t = data.optJSONObject(i);
                if (t == null) continue;
                JSONObject album = t.optJSONObject("album");
                if (album == null) continue;
                String img = album.optString("cover_big", "");
                if (img.isEmpty()) img = album.optString("cover_medium", "");
                if (img.isEmpty()) continue;
                String label = album.optString("title", "") + " — "
                        + t.optJSONObject("artist").optString("name", "");
                out.add(new Candidate(img, label));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ----- MusicBrainz + Cover Art Archive (fallback) -----

    private static List<Candidate> fromMusicBrainzCaa(String artist, String title) {
        List<Candidate> out = new ArrayList<>();
        try {
            String query = "release:\"" + safe(title) + "\" AND artist:\""
                    + safe(artist) + "\"";
            String json = httpGet("https://musicbrainz.org/ws/2/release/?query="
                    + enc(query) + "&fmt=json&limit=5");
            if (json == null) return out;
            JSONArray rels = new JSONObject(json).optJSONArray("releases");
            if (rels == null) return out;
            for (int i = 0; i < rels.length(); i++) {
                JSONObject r = rels.optJSONObject(i);
                if (r == null) continue;
                String mbid = r.optString("id", "");
                if (mbid.isEmpty()) continue;
                String label = r.optString("title", "") + " (MusicBrainz)";
                out.add(new Candidate(
                        "https://coverartarchive.org/release/" + mbid + "/front", label));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ----- http -----

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String httpGet(String url) {
        try {
            byte[] b = httpBytes(url);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] httpBytes(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "SpotPlayer/5.5 (personal music player)");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
