package com.arman.spotplayer;

import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline lyrics, in priority order:
 * 1) a .lrc file sitting next to the song file (same base name)
 * 2) embedded tags: ID3 USLT (MP3) or the Vorbis "LYRICS" comment (FLAC)
 * Synced lines carry their timestamp; plain lyrics come back unsynced.
 */
public final class Lyrics {

    public static final class Line {
        public final long ms; // -1 = unsynced
        public final String text;

        Line(long ms, String text) {
            this.ms = ms;
            this.text = text;
        }
    }

    private static final Pattern TS =
            Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern META = Pattern.compile("^\\[[a-zA-Z#][^]]*].*");

    private Lyrics() {}

    public static List<Line> load(Context ctx, Song song) {
        String path = queryPath(ctx, song);
        if (path != null) {
            File f = new File(path);
            String base = f.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0 && f.getParentFile() != null) {
                File lrc = new File(f.getParentFile(), base.substring(0, dot) + ".lrc");
                String txt = readTextFile(lrc);
                if (txt != null && !txt.trim().isEmpty()) return parse(txt);
            }
        }
        String raw = embedded(ctx, song);
        if (raw != null && !raw.trim().isEmpty()) return parse(raw);
        return null;
    }

    // ----- online fallback (LRCLIB, free, no key) -----

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String httpGet(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "SpotPlayer/4.4 (personal music player)");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String fromJson(org.json.JSONObject o) {
        if (o == null) return null;
        String synced = o.optString("syncedLyrics", "");
        if (!synced.trim().isEmpty()) return synced;
        String plain = o.optString("plainLyrics", "");
        return plain.trim().isEmpty() ? null : plain;
    }

    /** Raw lyrics text (synced .lrc format when available), or null. */
    public static String fetchOnline(Song song) {
        try {
            String get = httpGet("https://lrclib.net/api/get?artist_name=" + enc(song.artist)
                    + "&track_name=" + enc(song.title)
                    + "&duration=" + (song.durationMs / 1000));
            if (get != null) {
                String r = fromJson(new org.json.JSONObject(get));
                if (r != null) return r;
            }
            String search = httpGet("https://lrclib.net/api/search?track_name="
                    + enc(song.title) + "&artist_name=" + enc(song.artist));
            if (search != null) {
                org.json.JSONArray arr = new org.json.JSONArray(search);
                for (int i = 0; i < arr.length(); i++) {
                    String r = fromJson(arr.optJSONObject(i));
                    if (r != null) return r;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ----- sources -----

    private static String queryPath(Context ctx, Song song) {
        try (Cursor c = ctx.getContentResolver().query(song.uri,
                new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String readTextFile(File f) {
        try {
            if (!f.exists()) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String embedded(Context ctx, Song song) {
        byte[] d;
        try (InputStream in = ctx.getContentResolver().openInputStream(song.uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            int cap = 12 * 1024 * 1024;
            while ((n = in.read(buf)) > 0 && out.size() < cap) out.write(buf, 0, n);
            d = out.toByteArray();
        } catch (Exception e) {
            return null;
        }
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') return parseId3(d);
        if (d.length >= 4 && d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C') {
            return parseFlac(d);
        }
        return null;
    }

    // ----- ID3v2 USLT -----

    private static int syncsafe(byte[] d, int off) {
        return ((d[off] & 0x7F) << 21) | ((d[off + 1] & 0x7F) << 14)
                | ((d[off + 2] & 0x7F) << 7) | (d[off + 3] & 0x7F);
    }

    private static String parseId3(byte[] d) {
        try {
            int ver = d[3] & 0xFF;
            int tagSize = syncsafe(d, 6);
            int end = Math.min(d.length, 10 + tagSize);
            int pos = 10;
            while (pos + 10 <= end) {
                String id = new String(d, pos, 4, StandardCharsets.ISO_8859_1);
                if (id.charAt(0) == 0) break;
                int fsize = ver >= 4 ? syncsafe(d, pos + 4)
                        : ((d[pos + 4] & 0xFF) << 24) | ((d[pos + 5] & 0xFF) << 16)
                        | ((d[pos + 6] & 0xFF) << 8) | (d[pos + 7] & 0xFF);
                if (fsize <= 0) break;
                if (id.equals("USLT") && pos + 10 + fsize <= d.length) {
                    return decodeUslt(d, pos + 10, fsize);
                }
                pos += 10 + fsize;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String decodeUslt(byte[] d, int off, int len) {
        try {
            int enc = d[off] & 0xFF;
            int p = off + 4; // skip encoding + 3-byte language
            int limit = off + len;
            // skip the descriptor, terminated per encoding
            if (enc == 1 || enc == 2) {
                while (p + 1 < limit && !(d[p] == 0 && d[p + 1] == 0)) p += 2;
                p += 2;
            } else {
                while (p < limit && d[p] != 0) p++;
                p++;
            }
            if (p >= limit) return null;
            Charset cs;
            switch (enc) {
                case 1: cs = StandardCharsets.UTF_16; break;
                case 2: cs = StandardCharsets.UTF_16BE; break;
                case 3: cs = StandardCharsets.UTF_8; break;
                default: cs = StandardCharsets.ISO_8859_1; break;
            }
            return new String(d, p, limit - p, cs).replace("\u0000", "").trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ----- FLAC Vorbis comment -----

    private static int leInt(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static String parseFlac(byte[] d) {
        try {
            int pos = 4;
            while (pos + 4 <= d.length) {
                boolean last = (d[pos] & 0x80) != 0;
                int type = d[pos] & 0x7F;
                int len = ((d[pos + 1] & 0xFF) << 16) | ((d[pos + 2] & 0xFF) << 8)
                        | (d[pos + 3] & 0xFF);
                if (type == 4) {
                    int p = pos + 4;
                    int limit = Math.min(d.length, p + len);
                    int vendor = leInt(d, p);
                    p += 4 + vendor;
                    int count = leInt(d, p);
                    p += 4;
                    for (int i = 0; i < count && p + 4 <= limit; i++) {
                        int elen = leInt(d, p);
                        p += 4;
                        if (p + elen > limit) break;
                        String entry = new String(d, p, elen, StandardCharsets.UTF_8);
                        p += elen;
                        int eq = entry.indexOf('=');
                        if (eq > 0 && entry.substring(0, eq).equalsIgnoreCase("LYRICS")) {
                            return entry.substring(eq + 1);
                        }
                    }
                    return null;
                }
                pos += 4 + len;
                if (last) break;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ----- line parsing (plain or [mm:ss.xx] synced) -----

    public static List<Line> parse(String txt) {
        List<Line> synced = new ArrayList<>();
        List<Line> plain = new ArrayList<>();
        for (String rawLine : txt.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher m = TS.matcher(line);
            List<Long> times = new ArrayList<>();
            int lastEnd = -1;
            while (m.find()) {
                long frac = 0;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    frac = Long.parseLong(f) * (f.length() == 1 ? 100 : f.length() == 2 ? 10 : 1);
                }
                times.add(Long.parseLong(m.group(1)) * 60000
                        + Long.parseLong(m.group(2)) * 1000 + frac);
                lastEnd = m.end();
            }
            if (!times.isEmpty()) {
                String text = line.substring(lastEnd).trim();
                for (long t : times) synced.add(new Line(t, text));
            } else if (!META.matcher(line).matches()) {
                plain.add(new Line(-1, line));
            }
        }
        if (!synced.isEmpty()) {
            Collections.sort(synced, (a, b) -> Long.compare(a.ms, b.ms));
            return synced;
        }
        return plain.isEmpty() ? null : plain;
    }
}
