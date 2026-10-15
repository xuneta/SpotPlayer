package com.arman.spotplayer;

/**
 * Pure peak-bucket math for the waveform seekbar (SoundCloud model).
 * Android-free on purpose so it can be unit-tested on the JVM.
 *
 * Pipeline: decode song to PCM -> per-block peak (max |sample|, ~10 ms blocks)
 * -> downsample to N bar buckets -> normalize for display.
 */
public final class WaveformMath {

    private WaveformMath() {}

    /**
     * Downsamples per-block peaks to exactly {@code buckets} bars by taking
     * the max peak inside each bucket's fractional range. Works when the input
     * is longer OR shorter than the bucket count.
     */
    public static float[] downsample(float[] peaks, int buckets) {
        float[] out = new float[Math.max(0, buckets)];
        if (buckets <= 0 || peaks == null || peaks.length == 0) return out;
        int n = peaks.length;
        for (int b = 0; b < buckets; b++) {
            int from = (int) ((long) b * n / buckets);
            int to = (int) ((long) (b + 1) * n / buckets);
            if (to <= from) to = from + 1;
            if (to > n) to = n;
            float m = 0f;
            for (int i = from; i < to; i++) {
                float a = Math.abs(peaks[i]);
                if (a > m) m = a;
            }
            out[b] = m;
        }
        return out;
    }

    /**
     * Scales bars so the tallest hits 1.0, for a readable waveform on quiet
     * songs. Near-silent input is left alone so noise doesn't become art.
     */
    public static void normalize(float[] buckets) {
        if (buckets == null || buckets.length == 0) return;
        float max = 0f;
        for (float v : buckets) if (v > max) max = v;
        if (max < 0.02f || max >= 0.999f) return; // silent or already full-scale
        float k = 1f / max;
        for (int i = 0; i < buckets.length; i++) buckets[i] *= k;
    }

    /** Fraction of bars that are effectively silent (peak < 0.01). */
    public static float silenceFraction(float[] buckets) {
        if (buckets == null || buckets.length == 0) return 1f;
        int s = 0;
        for (float v : buckets) if (v < 0.01f) s++;
        return (float) s / buckets.length;
    }
}
