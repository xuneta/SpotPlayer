package com.arman.spotplayer;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SoundCloud-model waveform peaks: decode the song to PCM, take the peak
 * (|max sample|) of each ~10 ms block, downsample to 120 display buckets,
 * normalize, and cache per song id in app storage (filesDir/waveforms).
 *
 * Failures (undecodable file, OOM, anything) return null to the callback —
 * the UI then keeps the plain seekbar. Never throws to callers.
 */
public final class Waveform {

    public static final int BUCKETS = 120;
    private static final int BLOCK_MS = 10;

    public interface Callback {
        /** Runs on the main thread. Null = no waveform available. */
        void onPeaks(float[] peaks);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final android.util.LruCache<Long, float[]> MEM =
            new android.util.LruCache<>(64);

    private Waveform() {}

    public static void requestPeaks(Context ctx, Song song, Callback cb) {
        final Context app = ctx.getApplicationContext();
        final long id = song.id;
        float[] mem = MEM.get(id);
        if (mem != null) {
            MAIN.post(() -> cb.onPeaks(mem));
            return;
        }
        EXEC.execute(() -> {
            float[] peaks = load(app, id);
            if (peaks == null) {
                try {
                    peaks = compute(app, song);
                } catch (Throwable t) {
                    peaks = null;
                }
                if (peaks != null) {
                    MEM.put(id, peaks);
                    save(app, id, peaks);
                }
            } else {
                MEM.put(id, peaks);
            }
            final float[] out = peaks;
            MAIN.post(() -> {
                try {
                    cb.onPeaks(out);
                } catch (Throwable ignored) {}
            });
        });
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "waveforms");
        d.mkdirs();
        return d;
    }

    private static float[] load(Context ctx, long id) {
        File f = new File(dir(ctx), "w" + id + ".bin");
        if (!f.exists() || f.length() != BUCKETS * 4L) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            float[] p = new float[BUCKETS];
            for (int i = 0; i < BUCKETS; i++) p[i] = in.readFloat();
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static void save(Context ctx, long id, float[] peaks) {
        try (DataOutputStream out = new DataOutputStream(
                new FileOutputStream(new File(dir(ctx), "w" + id + ".bin")))) {
            for (float v : peaks) out.writeFloat(v);
        } catch (Exception ignored) {}
    }

    private static float[] compute(Context ctx, Song song) throws Exception {
        float[] blocks = decodeBlockPeaks(ctx, song);
        if (blocks == null || blocks.length == 0) return null;
        float[] b = WaveformMath.downsample(blocks, BUCKETS);
        WaveformMath.normalize(b);
        // A fully-silent decode is a failure for display purposes.
        if (WaveformMath.silenceFraction(b) > 0.98f) return null;
        return b;
    }

    /**
     * Full PCM decode, ~10 ms peak blocks (max |sample| across channels).
     * Same MediaExtractor/MediaCodec shape as RgScanner's decoder.
     */
    private static float[] decodeBlockPeaks(Context ctx, Song song) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, song.uri, null);
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
                return decodeLoop(ex, codec, sampleRate, channels, floatOut);
            } finally {
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
            }
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    private static float[] decodeLoop(MediaExtractor ex, MediaCodec codec,
                                      int sampleRate, int channels, boolean floatOut) {
        final int blockSamples = Math.max(64, sampleRate * BLOCK_MS / 1000);
        List<Float> blocks = new ArrayList<>();
        float blockPeak = 0f;
        int n = 0; // samples (frames) in current block
        boolean sawInputEos = false, sawOutputEos = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000;
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
                        int frames = floatOut ? info.size / (4 * channels)
                                : info.size / (2 * channels);
                        for (int f = 0; f < frames; f++) {
                            float peak = 0f;
                            for (int c = 0; c < channels; c++) {
                                float v = floatOut ? b.getFloat()
                                        : b.getShort() / 32768f;
                                float a = Math.abs(v);
                                if (a > peak) peak = a;
                            }
                            if (peak > blockPeak) blockPeak = peak;
                            if (++n >= blockSamples) {
                                blocks.add(blockPeak);
                                blockPeak = 0f;
                                n = 0;
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
        if (n > 0) blocks.add(blockPeak);
        float[] r = new float[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) r[i] = blocks.get(i);
        return r;
    }
}
