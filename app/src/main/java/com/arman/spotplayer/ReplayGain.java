package com.arman.spotplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReplayGain, following Poweramp's rules verbatim:
 * - source: Track or Album gain (falls back to the other if absent)
 * - mode: Apply Gain / Apply Gain + prevent clipping according to Peak / off
 * - RG preamp added when tags exist; a separate default preamp when no tags
 * Tags are read from MP3 ID3v2 TXXX frames or FLAC/OGG Vorbis comments,
 * cached per song id in SharedPreferences "rggains".
 */
public final class ReplayGain {

    private static final String PREFS = "rggains";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ReplayGain() {}

    /** Parsed RG values; NaN = absent. */
    static final class Rg {
        float trackDb = Float.NaN;
        float albumDb = Float.NaN;
        float trackPeak = Float.NaN;
        float albumPeak = Float.NaN;
        boolean any = false;

        void set(String key, float v) {
            any = true;
            if (key.equals("REPLAYGAIN_TRACK_GAIN")) trackDb = v;
            else if (key.equals("REPLAYGAIN_ALBUM_GAIN")) albumDb = v;
            else if (key.equals("REPLAYGAIN_TRACK_PEAK")) trackPeak = v;
            else if (key.equals("REPLAYGAIN_ALBUM_PEAK")) albumPeak = v;
        }
    }

    public static boolean hasCache(Context ctx, long songId) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("g_" + songId + "_ok", false);
    }

    /** True when the cache holds a real track or album value (tag or scanned). */
    public static boolean hasAnyValue(Context ctx, long songId) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("g_" + songId + "_ok", false)) return false;
        float t = p.getFloat("g_" + songId + "_t", Float.NaN);
        float a = p.getFloat("g_" + songId + "_a", Float.NaN);
        return !Float.isNaN(t) || !Float.isNaN(a);
    }

    /** Parses RG tags in the background if not cached, then runs cb on the main thread. */
    public static void ensureAsync(Context ctx, Song song, Runnable cb) {
        final Context app = ctx.getApplicationContext();
        final long id = song.id;
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean("g_" + id + "_ok", false)) {
            if (cb != null) MAIN.post(cb);
            return;
        }
        EXEC.execute(() -> {
            Rg rg = parse(app, song);
            SharedPreferences.Editor e = p.edit()
                    .putBoolean("g_" + id + "_ok", true);
            if (rg != null) {
                e.putFloat("g_" + id + "_t", rg.trackDb)
                 .putFloat("g_" + id + "_a", rg.albumDb)
                 .putFloat("g_" + id + "_tp", rg.trackPeak)
                 .putFloat("g_" + id + "_ap", rg.albumPeak);
            } else {
                e.putFloat("g_" + id + "_t", Float.NaN)
                 .putFloat("g_" + id + "_a", Float.NaN)
                 .putFloat("g_" + id + "_tp", Float.NaN)
                 .putFloat("g_" + id + "_ap", Float.NaN);
            }
            e.apply();
            if (cb != null) MAIN.post(cb);
        });
    }

    /**
     * Final ReplayGain offset in dB, or NaN when RG is off or no tags are known.
     * Mode 2 caps the gain so peak * 10^(gain/20) <= 1.0 (no clipping).
     */
    public static float applyDb(Context ctx, SharedPreferences fx, long songId) {
        int mode = fx.getInt("rg_mode", 0);
        if (mode == 0) return Float.NaN;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("g_" + songId + "_ok", false)) return Float.NaN;
        String base = "g_" + songId + "_";
        int src = fx.getInt("rg_source", 0);
        float db = p.getFloat(base + (src == 0 ? "t" : "a"), Float.NaN);
        if (Float.isNaN(db)) db = p.getFloat(base + (src == 0 ? "a" : "t"), Float.NaN);
        if (Float.isNaN(db)) return Float.NaN;
        float gdb = db + fx.getInt("rg_preamp", 0);
        if (mode == 2) {
            float peak = p.getFloat(base + (src == 0 ? "tp" : "ap"), Float.NaN);
            if (Float.isNaN(peak)) peak = p.getFloat(base + (src == 0 ? "ap" : "tp"), Float.NaN);
            if (!Float.isNaN(peak) && peak > 0) {
                float maxDb = (float) (20.0 * Math.log10(1.0 / peak));
                if (gdb > maxDb) gdb = maxDb;
            }
        }
        return gdb;
    }

    // ----- tag parsing -----

    private static Rg parse(Context ctx, Song song) {
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
        Rg rg = new Rg();
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            parseId3(d, rg);
        } else if (d.length >= 4 && d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C') {
            parseFlac(d, rg);
        } else if (d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') {
            parseOgg(d, rg);
        }
        return rg.any ? rg : null;
    }

    private static int syncsafe(byte[] d, int off) {
        return ((d[off] & 0x7F) << 21) | ((d[off + 1] & 0x7F) << 14)
                | ((d[off + 2] & 0x7F) << 7) | (d[off + 3] & 0x7F);
    }

    private static int be32(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static int leInt(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static void parseId3(byte[] d, Rg rg) {
        try {
            int ver = d[3] & 0xFF;
            if (ver == 2) { parseId3v22(d, rg); return; }
            if (ver < 3 || ver > 4) return;
            int tagSize = ver == 4 ? syncsafe(d, 6) : be32(d, 6);
            int end = Math.min(d.length, 10 + tagSize);
            int pos = 10;
            while (pos + 10 <= end) {
                String id = new String(d, pos, 4, StandardCharsets.ISO_8859_1);
                if (id.charAt(0) == 0) break;
                int fsize = ver == 4 ? syncsafe(d, pos + 4) : be32(d, pos + 4);
                if (fsize <= 0 || pos + 10 + fsize > end) break;
                if (id.equals("TXXX")) decodeTxxx(d, pos + 10, fsize, rg);
                pos += 10 + fsize;
            }
        } catch (Exception ignored) {}
    }

    private static void parseId3v22(byte[] d, Rg rg) {
        try {
            int tagSize = ((d[6] & 0xFF) << 16) | ((d[7] & 0xFF) << 8) | (d[8] & 0xFF);
            int end = Math.min(d.length, 10 + tagSize);
            int pos = 10;
            while (pos + 6 <= end) {
                String id = new String(d, pos, 3, StandardCharsets.ISO_8859_1);
                if (id.charAt(0) == 0) break;
                int fsize = ((d[pos + 3] & 0xFF) << 16) | ((d[pos + 4] & 0xFF) << 8)
                        | (d[pos + 5] & 0xFF);
                if (fsize <= 0 || pos + 6 + fsize > end) break;
                if (id.equals("TXX")) decodeTxxx(d, pos + 6, fsize, rg);
                pos += 6 + fsize;
            }
        } catch (Exception ignored) {}
    }

    private static void decodeTxxx(byte[] d, int off, int len, Rg rg) {
        try {
            int enc = d[off] & 0xFF;
            int limit = off + len;
            int p = off + 1;
            int descEnd;
            if (enc == 1 || enc == 2) {
                descEnd = p;
                while (descEnd + 1 < limit && !(d[descEnd] == 0 && d[descEnd + 1] == 0)) descEnd += 2;
                p = descEnd + 2;
            } else {
                descEnd = p;
                while (descEnd < limit && d[descEnd] != 0) descEnd++;
                p = descEnd + 1;
            }
            if (p >= limit) return;
            Charset cs = charset(enc);
            String desc = new String(d, off + 1, Math.max(0, descEnd - off - 1), cs)
                    .replace("\u0000", "").trim().toUpperCase();
            String val = new String(d, p, limit - p, cs).replace("\u0000", "").trim();
            float v = parseNumber(val);
            if (Float.isNaN(v)) return;
            if (desc.startsWith("REPLAYGAIN_")) rg.set(desc, v);
        } catch (Exception ignored) {}
    }

    private static Charset charset(int enc) {
        switch (enc) {
            case 1: return StandardCharsets.UTF_16;
            case 2: return StandardCharsets.UTF_16BE;
            case 3: return StandardCharsets.UTF_8;
            default: return StandardCharsets.ISO_8859_1;
        }
    }

    /** First number in a string like "-7.32 dB" or "0.987654". NaN if none. */
    private static float parseNumber(String s) {
        try {
            StringBuilder b = new StringBuilder();
            boolean started = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') {
                    b.append(c);
                    started = true;
                } else if (started) {
                    break;
                }
            }
            if (b.length() == 0) return Float.NaN;
            return Float.parseFloat(b.toString());
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    private static void parseVorbisEntries(byte[] d, int off, int limit, Rg rg) {
        try {
            int p = off;
            int vendor = leInt(d, p);
            p += 4 + vendor;
            int count = leInt(d, p);
            p += 4;
            for (int i = 0; i < count && p + 4 <= limit; i++) {
                int elen = leInt(d, p);
                p += 4;
                if (elen <= 0 || p + elen > limit) break;
                String entry = new String(d, p, elen, StandardCharsets.UTF_8);
                p += elen;
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    String key = entry.substring(0, eq).trim().toUpperCase();
                    if (key.startsWith("REPLAYGAIN_")) {
                        float v = parseNumber(entry.substring(eq + 1).trim());
                        if (!Float.isNaN(v)) rg.set(key, v);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void parseFlac(byte[] d, Rg rg) {
        try {
            int pos = 4;
            while (pos + 4 <= d.length) {
                boolean last = (d[pos] & 0x80) != 0;
                int type = d[pos] & 0x7F;
                int len = ((d[pos + 1] & 0xFF) << 16) | ((d[pos + 2] & 0xFF) << 8)
                        | (d[pos + 3] & 0xFF);
                if (type == 4) {
                    parseVorbisEntries(d, pos + 4, Math.min(d.length, pos + 4 + len), rg);
                    return;
                }
                pos += 4 + len;
                if (last) break;
            }
        } catch (Exception ignored) {}
    }

    private static void parseOgg(byte[] d, Rg rg) {
        try {
            int limit = Math.min(d.length, 4 * 1024 * 1024);
            int pos = 0;
            ByteArrayOutputStream pkt = null;
            while (pos + 27 <= limit) {
                if (!(d[pos] == 'O' && d[pos + 1] == 'g'
                        && d[pos + 2] == 'g' && d[pos + 3] == 'S')) {
                    pos++;
                    continue;
                }
                int segCount = d[pos + 26] & 0xFF;
                if (pos + 27 + segCount > limit) break;
                int p = pos + 27 + segCount;
                for (int i = 0; i < segCount && p <= limit; i++) {
                    int segLen = d[pos + 27 + i] & 0xFF;
                    if (pkt == null) pkt = new ByteArrayOutputStream();
                    pkt.write(d, p, Math.min(segLen, limit - p));
                    p += segLen;
                    if (segLen < 255) {
                        byte[] b = pkt.toByteArray();
                        pkt = null;
                        if (b.length > 7 && b[0] == 0x03 && b[1] == 'v' && b[2] == 'o'
                                && b[3] == 'r' && b[4] == 'b' && b[5] == 'i' && b[6] == 's') {
                            parseVorbisEntries(b, 7, b.length, rg);
                            return;
                        }
                    }
                }
                pos = p;
            }
        } catch (Exception ignored) {}
    }
}
