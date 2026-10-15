package com.arman.spotplayer;

/**
 * ReplayGain-style loudness analysis math. Pure Java — zero Android imports —
 * so the whole thing is unit-testable on a plain JVM (see the harness).
 *
 * Model (a simplification of the published ReplayGain spec):
 * - 50 ms RMS blocks per track, reference level = 95th percentile of blocks,
 * - track gain = -18.0 dBFS target - reference.
 * The true spec applies psychoacoustic equal-loudness filters first; this
 * scanner works on raw RMS, which slightly overvalues deep bass. That makes
 * gains a touch conservative on bass-heavy tracks — acceptable for leveling
 * and documented here rather than faked.
 */
public final class RgScanMath {

    /** ReplayGain's 89 dB SPL reference maps to -18 dBFS RMS in digital terms. */
    public static final float TARGET_DBFS = -18.0f;

    private RgScanMath() {}

    /** 95th percentile of block RMS levels (dBFS). NaN when empty. */
    public static float percentile95(float[] blockRmsDb) {
        if (blockRmsDb == null || blockRmsDb.length == 0) return Float.NaN;
        float[] s = blockRmsDb.clone();
        java.util.Arrays.sort(s);
        int idx = (int) Math.ceil(0.95 * s.length) - 1;
        idx = Math.max(0, Math.min(idx, s.length - 1));
        return s[idx];
    }

    /** Track gain in dB from 50 ms block RMS levels in dBFS. NaN when empty. */
    public static float trackGainDb(float[] blockRmsDb) {
        float ref = percentile95(blockRmsDb);
        if (Float.isNaN(ref)) return Float.NaN;
        return TARGET_DBFS - ref;
    }

    /** Sanity clamp for measured gains: never crazier than -30..+15 dB. */
    public static float clampGainDb(float g) {
        if (Float.isNaN(g)) return Float.NaN;
        if (g < -30f) return -30f;
        if (g > 15f) return 15f;
        return g;
    }

    /** Album gain from per-track reference levels (dBFS): average, then target. */
    public static float albumGainDb(float[] trackRefDb) {
        if (trackRefDb == null || trackRefDb.length == 0) return Float.NaN;
        float sum = 0f;
        int n = 0;
        for (float v : trackRefDb) {
            if (!Float.isNaN(v)) { sum += v; n++; }
        }
        if (n == 0) return Float.NaN;
        return TARGET_DBFS - sum / n;
    }
}
