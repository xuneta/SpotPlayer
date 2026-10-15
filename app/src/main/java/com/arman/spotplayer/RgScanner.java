package com.arman.spotplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Measures loudness for songs that carry no ReplayGain tags. Decodes the file
 * to PCM with MediaExtractor + MediaCodec (MP3/FLAC/AAC/OGG-Vorbis — whatever
 * the device's codecs can decode), accumulates 50 ms block RMS levels and the
 * true sample peak, then derives a ReplayGain-style gain via RgScanMath and
 * stores it in the EXISTING "rggains" cache so ReplayGain.applyDb treats
 * scanned values exactly like tag values (same modes, source, preamp and
 * clip-prevention rules apply).
 *
 * TAG VALUES ALWAYS WIN: a song whose cache holds real values is never
 * scanned and never overwritten. Failures are remembered with a scanfail
 * flag so we don't retry-loop; a manual "Scan library" retries them.
 */
public final class RgScanner {

    private static final String PREFS = "rggains";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean libScanRunning = false;
    private static volatile boolean libScanCancel = false;

    public interface Progress {
        void onProgress(int done, int total);
        void onDone(int scanned, int failed);
    }

    private RgScanner() {}

    public static boolean isRunning() { return libScanRunning; }
    public static void cancelLibraryScan() { libScanCancel = true; }

    static boolean hasScanFailed(Context ctx, long id) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("g_" + id + "_scanfail", false);
    }

    private static void markScanFailed(Context ctx, long id) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("g_" + id + "_scanfail", true).apply();
    }

    /** Single song; skips when cache holds values. Callback runs on main thread. */
    public static void scanSongAsync(Context ctx, Song song, List<Song> library, Runnable cb) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            scanOne(app, song, library);
            if (cb != null) MAIN.post(cb);
        });
    }

    /**
     * Full library scan: every song with no RG values and no recorded failure.
     * One decode at a time on a single thread. Progress/finish run on the
     * main thread so UI code can touch views directly.
     */
    public static void scanLibraryAsync(Context ctx, List<Song> library, Progress cb) {
        if (libScanRunning) return;
        libScanRunning = true;
        libScanCancel = false;
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            List<Song> work = new ArrayList<>();
            if (library != null) {
                for (Song s : library) {
                    if (!ReplayGain.hasAnyValue(app, s.id) && !hasScanFailed(app, s.id)) {
                        work.add(s);
                    }
                }
            }
            int total = work.size(), done = 0, ok = 0, fail = 0;
            for (Song s : work) {
                if (libScanCancel) break;
                try {
                    if (scanOne(app, s, library) != null) ok++; else fail++;
                } catch (Exception e) {
                    fail++;
                    markScanFailed(app, s.id);
                }
                done++;
                final int fd = done;
                MAIN.post(() -> cb.onProgress(fd, total));
            }
            libScanRunning = false;
            final int fok = ok, ffail = fail;
            MAIN.post(() -> cb.onDone(fok, ffail));
        });
    }

    /** Decodes one song and stores its gain+peak. Null = skipped or failed. */
    static ScanResult scanOne(Context ctx, Song song, List<Song> library) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean("g_" + song.id + "_ok", false)) return null; // tags win
        DecodeResult d;
        try {
            d = decode(ctx, song.uri);
        } catch (Exception e) {
            markScanFailed(ctx, song.id);
            return null;
        }
        if (d == null || d.blockRmsDb == null || d.blockRmsDb.length == 0) {
            markScanFailed(ctx, song.id);
            return null;
        }
        float ref = RgScanMath.percentile95(d.blockRmsDb);
        float gain = RgScanMath.clampGainDb(RgScanMath.trackGainDb(d.blockRmsDb));
        if (Float.isNaN(gain) || Float.isNaN(ref)) {
            markScanFailed(ctx, song.id);
            return null;
        }
        SharedPreferences.Editor e = p.edit()
                .putBoolean("g_" + song.id + "_ok", true)
                .putBoolean("g_" + song.id + "_scanned", true)
                .putFloat("g_" + song.id + "_t", gain)
                .putFloat("g_" + song.id + "_tp", Math.max(0f, d.peak))
                .putFloat("g_" + song.id + "_ref", ref)
                .remove("g_" + song.id + "_scanfail");
        e.apply();
        maybeAlbumGain(ctx, song, library);
        ScanResult r = new ScanResult();
        r.gainDb = gain;
        r.peak = d.peak;
        return r;
    }

    /** When every member of an album has been scanned, derive its album gain. */
    private static void maybeAlbumGain(Context ctx, Song song, List<Song> library) {
        if (library == null || library.isEmpty()) return;
        List<Song> members = new ArrayList<>();
        for (Song s : library) {
            if (s.albumId == song.albumId && s.albumId > 0) members.add(s);
        }
        if (members.size() < 2) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float[] refs = new float[members.size()];
        for (int i = 0; i < members.size(); i++) {
            Song m = members.get(i);
            // Only when ALL members are scanned values can the album average
            // be meaningful; tags stay untouched either way.
            if (!p.getBoolean("g_" + m.id + "_scanned", false)) return;
            refs[i] = p.getFloat("g_" + m.id + "_ref", Float.NaN);
            if (Float.isNaN(refs[i])) return;
        }
        float ag = RgScanMath.clampGainDb(RgScanMath.albumGainDb(refs));
        if (Float.isNaN(ag)) return;
        SharedPreferences.Editor e = p.edit();
        for (Song m : members) {
            // Tag-provided album gains always win: fill only missing ones.
            if (Float.isNaN(p.getFloat("g_" + m.id + "_a", Float.NaN))) {
                e.putFloat("g_" + m.id + "_a", ag);
            }
        }
        e.apply();
    }

    static final class ScanResult {
        float gainDb;
        float peak;
    }

    private static final class DecodeResult {
        float[] blockRmsDb;
        float peak;
    }

    /**
     * Full PCM decode of an audio Uri. 50 ms RMS blocks across all channels.
     * Supports the device's PCM_16BIT and PCM_FLOAT decoder output; anything
     * else (compressed passthrough etc.) returns null — an honest failure.
     */
    private static DecodeResult decode(Context ctx, Uri uri) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            String mime = null;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    mime = m;
                    break;
                }
            }
            if (track < 0) return null;
            int sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            if (channels < 1) channels = 1;
            int pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT;
            if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                pcmEncoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING);
            }
            final boolean floatOut =
                    pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT;
            if (!floatOut && pcmEncoding != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                return null;
            }
            ex.selectTrack(track);
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();
            try {
                return decodeLoop(ex, codec, sampleRate, floatOut);
            } finally {
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
            }
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    private static DecodeResult decodeLoop(MediaExtractor ex, MediaCodec codec,
                                           int sampleRate, boolean floatOut) {
        final int blockSamples = Math.max(64, (int) (sampleRate * 0.05)); // 50 ms
        List<Float> blocks = new ArrayList<>();
        double sumSq = 0;
        int n = 0;
        float peak = 0f;
        boolean sawInputEos = false, sawOutputEos = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000; // 10 min cap
        while (!sawOutputEos) {
            if (System.currentTimeMillis() > deadline) break;
            if (!sawInputEos) {
                int in = codec.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer b = codec.getInputBuffer(in);
                    int sz = b != null ? ex.readSampleData(b, 0) : -1;
                    if (sz < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEos = true;
                    } else {
                        codec.queueInputBuffer(in, 0, sz, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int out = codec.dequeueOutputBuffer(info, 10_000);
            if (out >= 0) {
                if (info.size > 0) {
                    ByteBuffer b = codec.getOutputBuffer(out);
                    if (b != null) {
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        b.order(ByteOrder.nativeOrder());
                        if (floatOut) {
                            while (b.remaining() >= 4) {
                                float v = b.getFloat();
                                float a = Math.abs(v);
                                if (a > peak) peak = a;
                                sumSq += (double) v * v;
                                if (++n >= blockSamples) {
                                    blocks.add(dbFs(sumSq, n));
                                    sumSq = 0;
                                    n = 0;
                                }
                            }
                        } else {
                            while (b.remaining() >= 2) {
                                float v = b.getShort() / 32768f;
                                float a = Math.abs(v);
                                if (a > peak) peak = a;
                                sumSq += (double) v * v;
                                if (++n >= blockSamples) {
                                    blocks.add(dbFs(sumSq, n));
                                    sumSq = 0;
                                    n = 0;
                                }
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEos = true;
                }
            }
        }
        if (n > 0) blocks.add(dbFs(sumSq, n)); // trailing partial block
        DecodeResult r = new DecodeResult();
        r.blockRmsDb = new float[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) r.blockRmsDb[i] = blocks.get(i);
        r.peak = peak;
        return r;
    }

    private static float dbFs(double sumSq, int n) {
        if (n <= 0) return -96f;
        double rms = Math.sqrt(sumSq / n);
        if (rms <= 0) return -96f;
        float db = (float) (20.0 * Math.log10(rms));
        return Math.max(db, -96f);
    }
}
