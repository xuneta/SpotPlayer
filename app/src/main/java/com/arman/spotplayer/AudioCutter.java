package com.arman.spotplayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java MP3 frame cutter: cuts a time range out of (or keeps only a time
 * range of) an MP3 file WITHOUT re-encoding, so there is no quality loss.
 * The original file is never touched; the caller writes the cut to a new
 * file (see MainActivity: cut is saved as "title (cut).mp3").
 *
 * This file imports NOTHING from android.* so the byte core is unit-testable
 * on a plain JVM (see test_cut/AudioCutTest.java).
 *
 * Scope, stated honestly:
 *  - MP3 only (MPEG-1/2/2.5 Layer III, CBR and VBR). FLAC/M4A/OGG are
 *    rejected with IllegalArgumentException ("MP3 only for now").
 *  - Cut precision is one frame (~26 ms at 44.1 kHz MPEG-1, ~13 ms at
 *    44.1 kHz MPEG-2). The kept/removed range is rounded to frame
 *    boundaries: a frame is kept if it overlaps the requested range.
 *  - ID3v2 is copied byte-for-byte; ID3v1 (128-byte TAG trailer) is copied
 *    if present. Any other trailing data (APETag, Lyrics3) is dropped.
 *  - A Xing/Info header in the first frame is patched with the new frame
 *    and byte counts when the cut starts at frame 0. The stale 100-byte
 *    TOC inside Xing is left as-is (players fall back to frame counting).
 *  - Gapless (LAME encoder delay/padding) info is not adjusted; the cut
 *    may gain/lose a few ms of silence at the edges.
 */
public final class AudioCutter {

    private AudioCutter() {}

    /** Result of indexing an MP3 file. Offsets are relative to audioStart. */
    public static final class Parsed {
        public byte[] id3v2;          // null when the file has no ID3v2 tag
        public byte[] id3v1;          // null when absent (128 bytes when present)
        public long audioStart;       // offset of the first frame in the source
        public int[] frameOff;        // frame offsets, relative to audioStart
        public int[] frameLen;        // frame lengths in bytes
        public double[] frameStartMs; // cumulative start time of each frame
        public double[] frameDurMs;   // duration of each frame
        public double totalMs;        // total duration of the audio stream

        public int frameCount() { return frameOff.length; }
    }

    // Layer III bitrates in kbps, indexed by the 4-bit bitrate field.
    // Index 0 = free format (unsupported), 15 = bad.
    private static final int[] BR_MPEG1 = {
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0 };
    private static final int[] BR_MPEG2 = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0 };
    private static final int[][] SAMPLERATES = {
            { 11025, 12000, 8000, 0 },   // MPEG-2.5 (version bits 00)
            { 0, 0, 0, 0 },              // reserved (01)
            { 22050, 24000, 16000, 0 },  // MPEG-2 (10)
            { 44100, 48000, 32000, 0 }   // MPEG-1 (11)
    };

    /** One parsed frame header, or null when the bytes are not a Layer III header. */
    private static final class Header {
        int versionBits;   // 0 = 2.5, 2 = 2, 3 = 1
        int samplerate;
        int frameLen;
        int samples;
        int channels;      // 3 = single channel (mono), else stereo-ish
    }

    private static Header parseHeader(byte[] d, int pos) {
        if (pos + 4 > d.length) return null;
        int b0 = d[pos] & 0xFF;
        int b1 = d[pos + 1] & 0xFF;
        int b2 = d[pos + 2] & 0xFF;
        int b3 = d[pos + 3] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) return null;
        int versionBits = (b1 >> 3) & 0x03;
        if (versionBits == 1) return null;              // reserved version
        int layer = (b1 >> 1) & 0x03;
        if (layer != 1) return null;                    // we only cut Layer III
        int brIdx = (b2 >> 4) & 0x0F;
        int srIdx = (b2 >> 2) & 0x03;
        int padding = (b2 >> 1) & 0x01;
        if (brIdx == 0 || brIdx == 15 || srIdx == 3) return null;
        int[] brTable = (versionBits == 3) ? BR_MPEG1 : BR_MPEG2;
        int bitrate = brTable[brIdx] * 1000;
        int samplerate = SAMPLERATES[versionBits][srIdx];
        if (bitrate <= 0 || samplerate <= 0) return null;
        Header h = new Header();
        h.versionBits = versionBits;
        h.samplerate = samplerate;
        h.channels = (b3 >> 6) & 0x03;
        h.samples = (versionBits == 3) ? 1152 : 576;
        int coeff = (versionBits == 3) ? 144 : 72;
        h.frameLen = coeff * bitrate / samplerate + padding;
        if (h.frameLen < 4) return null;
        return h;
    }

    /** True when the two headers belong to the same stream (chain check). */
    private static boolean chained(Header a, Header b) {
        return a != null && b != null
                && a.versionBits == b.versionBits
                && a.samplerate == b.samplerate;
    }

    /**
     * Index an MP3 file held in memory. Throws IllegalArgumentException when
     * the data is not a cuttable MP3 (no frame pair found, or no frames).
     */
    public static Parsed parse(byte[] src) {
        if (src == null || src.length < 8) {
            throw new IllegalArgumentException("not an MP3 file");
        }
        int pos = 0;
        byte[] id3v2 = null;
        if (src[0] == 'I' && src[1] == 'D' && src[2] == '3' && src.length >= 10) {
            int size = ((src[6] & 0x7F) << 21) | ((src[7] & 0x7F) << 14)
                    | ((src[8] & 0x7F) << 7) | (src[9] & 0x7F);
            pos = 10 + size;
            if (pos > src.length) {
                throw new IllegalArgumentException("truncated ID3v2 tag");
            }
        }
        // Find the first frame: a header chained to a second valid header.
        // Scanning forward from the computed position also absorbs a rare
        // ID3v2 footer (10 bytes) or stray tag bytes; the gap is dropped.
        int first = -1;
        int scanEnd = Math.min(src.length - 4, pos + 65536);
        for (int p = pos; p <= scanEnd; p++) {
            Header h = parseHeader(src, p);
            if (h == null) continue;
            if (p + h.frameLen + 4 > src.length) break;
            Header next = parseHeader(src, p + h.frameLen);
            if (chained(h, next)) {
                first = p;
                break;
            }
        }
        if (first < 0) {
            throw new IllegalArgumentException("no MP3 frames found");
        }
        // The tag region (header + frames, per the size field) is copied
        // as-is. Any gap between it and the first frame (a rare ID3v2
        // footer, or stray bytes) is dropped, not copied.
        if (pos > 0) {
            id3v2 = new byte[pos];
            System.arraycopy(src, 0, id3v2, 0, pos);
        }
        List<Integer> offs = new ArrayList<>();
        List<Integer> lens = new ArrayList<>();
        List<Double> starts = new ArrayList<>();
        List<Double> durs = new ArrayList<>();
        double t = 0;
        int p = first;
        while (p + 4 <= src.length) {
            Header h = parseHeader(src, p);
            if (h == null || p + h.frameLen > src.length) break;
            double dur = h.samples * 1000.0 / h.samplerate;
            offs.add(p - first);
            lens.add(h.frameLen);
            starts.add(t);
            durs.add(dur);
            t += dur;
            p += h.frameLen;
        }
        if (offs.isEmpty()) {
            throw new IllegalArgumentException("no MP3 frames found");
        }
        Parsed r = new Parsed();
        r.id3v2 = id3v2;
        r.audioStart = first;
        r.frameOff = toIntArray(offs);
        r.frameLen = toIntArray(lens);
        r.frameStartMs = toDoubleArray(starts);
        r.frameDurMs = toDoubleArray(durs);
        r.totalMs = t;
        // ID3v1 trailer: last 128 bytes starting with "TAG", only when it
        // sits after the last frame (not overlapping audio data).
        if (src.length >= 128 && p <= src.length - 128
                && src[src.length - 128] == 'T' && src[src.length - 127] == 'A'
                && src[src.length - 126] == 'G') {
            r.id3v1 = new byte[128];
            System.arraycopy(src, src.length - 128, r.id3v1, 0, 128);
        }
        return r;
    }

    private static int[] toIntArray(List<Integer> l) {
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    private static double[] toDoubleArray(List<Double> l) {
        double[] r = new double[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    /** Expected duration of the cut result, in ms. */
    public static double expectedMs(Parsed p, double startMs, double endMs,
                                    boolean removeMode) {
        double range = endMs - startMs;
        return removeMode ? p.totalMs - range : range;
    }

    /**
     * Write the cut to out: ID3v2 + selected frames + ID3v1.
     * removeMode=false keeps frames overlapping [startMs, endMs);
     * removeMode=true keeps everything EXCEPT that range.
     * Throws IllegalArgumentException when the selection is empty.
     */
    public static void writeCut(Parsed p, byte[] src, double startMs,
                                double endMs, boolean removeMode,
                                OutputStream out) throws IOException {
        List<Integer> sel = new ArrayList<>();
        for (int i = 0; i < p.frameCount(); i++) {
            double fs = p.frameStartMs[i];
            double fe = fs + p.frameDurMs[i];
            boolean overlap = fs < endMs && fe > startMs;
            if (overlap != removeMode) sel.add(i);
        }
        if (sel.isEmpty()) {
            throw new IllegalArgumentException("selection has no frames");
        }
        if (p.id3v2 != null) out.write(p.id3v2);
        long totalAudioBytes = 0;
        for (int idx : sel) totalAudioBytes += p.frameLen[idx];
        for (int k = 0; k < sel.size(); k++) {
            int i = sel.get(k);
            int off = (int) (p.audioStart + p.frameOff[i]);
            int len = p.frameLen[i];
            if (k == 0 && i == 0) {
                // First frame of the original stream: patch Xing counts.
                byte[] frame = new byte[len];
                System.arraycopy(src, off, frame, 0, len);
                patchXing(frame, sel.size(), totalAudioBytes);
                out.write(frame);
            } else {
                out.write(src, off, len);
            }
        }
        if (p.id3v1 != null) out.write(p.id3v1);
    }

    /**
     * If the frame carries a Xing/Info header, rewrite its frame and byte
     * counts. Layout after the 4-byte header: side info (17/32 bytes MPEG-1,
     * 9/17 MPEG-2/2.5, mono vs stereo), then "Xing"/"Info", flags, then the
     * optional fields in order: frames, bytes, TOC (100), quality.
     */
    private static void patchXing(byte[] frame, int newFrames, long newBytes) {
        if (frame.length < 8) return;
        Header h = parseHeader(frame, 0);
        if (h == null) return;
        int sideInfo;
        if (h.versionBits == 3) {
            sideInfo = (h.channels == 3) ? 17 : 32;
        } else {
            sideInfo = (h.channels == 3) ? 9 : 17;
        }
        int x = 4 + sideInfo;
        if (x + 8 > frame.length) return;
        boolean isXing = frame[x] == 'X' && frame[x + 1] == 'i'
                && frame[x + 2] == 'n' && frame[x + 3] == 'g';
        boolean isInfo = frame[x] == 'I' && frame[x + 1] == 'n'
                && frame[x + 2] == 'f' && frame[x + 3] == 'o';
        if (!isXing && !isInfo) return;
        int flags = intAt(frame, x + 4);
        int q = x + 8;
        if ((flags & 0x01) != 0) {
            if (q + 4 > frame.length) return;
            putInt(frame, q, newFrames);
            q += 4;
        }
        if ((flags & 0x02) != 0) {
            if (q + 4 > frame.length) return;
            putInt(frame, q, (int) newBytes);
        }
    }

    private static int intAt(byte[] d, int pos) {
        return ((d[pos] & 0xFF) << 24) | ((d[pos + 1] & 0xFF) << 16)
                | ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
    }

    private static void putInt(byte[] d, int pos, int v) {
        d[pos] = (byte) (v >>> 24);
        d[pos + 1] = (byte) (v >>> 16);
        d[pos + 2] = (byte) (v >>> 8);
        d[pos + 3] = (byte) v;
    }

    /** File-name safe: strips characters Android/MediaStore reject in names. */
    public static String sanitizeFileName(String s) {
        if (s == null) return "cut";
        String r = s.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (r.isEmpty()) r = "cut";
        if (r.length() > 80) r = r.substring(0, 80).trim();
        return r;
    }
}
