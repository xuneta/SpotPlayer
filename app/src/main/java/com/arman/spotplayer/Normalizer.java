package com.arman.spotplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Measures a song's loudness (RMS of the first seconds, decoded on a
 * background thread) and caches a gain in dB so playback can be evened
 * out Spotify-style. Target RMS ≈ -20 dBFS; gain clamped to [-12, +10] dB.
 * Analysis failures simply leave the gain at 0 (no change).
 */
public class Normalizer {

    public interface Callback {
        void onGain(float gainDb);
    }

    private static final String PREFS = "normgains";
    private static final float TARGET_DBFS = -20f;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static float cachedGainDb(Context ctx, long songId) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getFloat("g_" + songId, 0f);
    }

    public static void analyzeAsync(Context ctx, Song song, Callback cb) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = "g_" + song.id;
        if (p.contains(key)) {
            if (cb != null) cb.onGain(p.getFloat(key, 0f));
            return;
        }
        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            float gain = measure(app, song);
            p.edit().putFloat(key, gain).apply();
            if (cb != null) MAIN.post(() -> cb.onGain(gain));
        });
    }

    private static float measure(Context ctx, Song song) {
        MediaExtractor ex = null;
        MediaCodec dec = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(ctx, song.uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) return 0f;
            ex.selectTrack(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();

            long maxSamples = (long) sampleRate * channels * 12; // ~12 seconds is enough
            long totalSamples = 0;
            double sumSq = 0;
            boolean inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 20000;

            while (totalSamples < maxSamples && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    int inIdx = dec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = dec.getInputBuffer(inIdx);
                        int size = buf != null ? ex.readSampleData(buf, 0) : -1;
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = dec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    ByteBuffer out = dec.getOutputBuffer(outIdx);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        out.order(ByteOrder.LITTLE_ENDIAN);
                        int n = info.size / 2;
                        for (int i = 0; i < n; i++) {
                            double v = out.getShort() / 32768.0;
                            sumSq += v * v;
                        }
                        totalSamples += n;
                    }
                    dec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = dec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }
            if (totalSamples < sampleRate) return 0f; // too little signal to judge
            double mean = sumSq / totalSamples;
            if (mean <= 0) return 0f;
            double rmsDb = 10.0 * Math.log10(mean);
            double gain = TARGET_DBFS - rmsDb;
            if (gain > 10) gain = 10;
            if (gain < -12) gain = -12;
            return (float) gain;
        } catch (Exception e) {
            return 0f;
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) {}
            try { if (ex != null) ex.release(); } catch (Exception ignored) {}
        }
    }
}
