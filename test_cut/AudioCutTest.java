package com.arman.spotplayer;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * JVM round-trip test for AudioCutter: builds synthetic MP3 fixtures in
 * memory (CBR, VBR, ID3v2, ID3v1, Xing, footer gap), parses, cuts
 * (keep and remove modes), re-parses the output, and checks frame counts,
 * durations, tag preservation and Xing patching.
 * Run: javac + java from the JDK, no Android needed.
 */
public class AudioCutTest {

    static int failures = 0;

    static void check(String name, boolean cond) {
        System.out.println((cond ? "PASS" : "FAIL") + ": " + name);
        if (!cond) failures++;
    }

    static void checkEq(String name, Object a, Object b) {
        boolean ok = a == null ? b == null : a.equals(b);
        if (!ok) System.out.println("   expected=" + b + " got=" + a);
        check(name, ok);
    }

    static void checkNear(String name, double a, double b, double tol) {
        boolean ok = Math.abs(a - b) <= tol;
        if (!ok) System.out.println("   expected~" + b + " got=" + a);
        check(name, ok);
    }

    // ---------- fixture builders ----------

    /** One MPEG-1 Layer III frame header (stereo, no CRC). */
    static byte[] frameHeader(int brIdx, int pad) {
        return new byte[]{(byte) 0xFF, (byte) 0xFB,
                (byte) ((brIdx << 4) | pad), (byte) 0x00};
    }

    static int frameLenMpeg1(int brKbps, int sr) {
        return 144 * brKbps * 1000 / sr;
    }

    /** A full frame of the given bitrate index, payload zeros. */
    static byte[] frame(int brIdx, int brKbps) {
        int len = frameLenMpeg1(brKbps, 44100);
        byte[] f = new byte[len];
        byte[] h = frameHeader(brIdx, 0);
        System.arraycopy(h, 0, f, 0, 4);
        return f;
    }

    /** First frame carrying a Xing header (frames/bytes fields set). */
    static byte[] xingFrame(int brIdx, int brKbps, int frames, int bytes) {
        byte[] f = frame(brIdx, brKbps);
        int x = 4 + 32; // MPEG-1 stereo side info
        f[x] = 'X'; f[x + 1] = 'i'; f[x + 2] = 'n'; f[x + 3] = 'g';
        putInt(f, x + 4, 0x03); // flags: frames + bytes present
        putInt(f, x + 8, frames);
        putInt(f, x + 12, bytes);
        return f;
    }

    static void putInt(byte[] d, int pos, int v) {
        d[pos] = (byte) (v >>> 24);
        d[pos + 1] = (byte) (v >>> 16);
        d[pos + 2] = (byte) (v >>> 8);
        d[pos + 3] = (byte) v;
    }

    static int intAt(byte[] d, int pos) {
        return ((d[pos] & 0xFF) << 24) | ((d[pos + 1] & 0xFF) << 16)
                | ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, o, p.length);
            o += p.length;
        }
        return r;
    }

    /** Minimal ID3v2.3 header + recognizable payload bytes. */
    static byte[] id3v2(int payloadLen) {
        byte[] tag = new byte[10 + payloadLen];
        tag[0] = 'I'; tag[1] = 'D'; tag[2] = '3';
        tag[3] = 3; tag[4] = 0; tag[5] = 0;
        tag[6] = (byte) ((payloadLen >> 21) & 0x7F);
        tag[7] = (byte) ((payloadLen >> 14) & 0x7F);
        tag[8] = (byte) ((payloadLen >> 7) & 0x7F);
        tag[9] = (byte) (payloadLen & 0x7F);
        for (int i = 0; i < payloadLen; i++) tag[10 + i] = (byte) (i + 1);
        return tag;
    }

    static byte[] id3v1() {
        byte[] t = new byte[128];
        t[0] = 'T'; t[1] = 'A'; t[2] = 'G';
        Arrays.fill(t, 3, 128, (byte) 'A');
        return t;
    }

    static byte[] cbrFile(int frames) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < frames; i++) b.write(frame(9, 128), 0, 417);
        return b.toByteArray();
    }

    static byte[] cut(AudioCutter.Parsed p, byte[] src,
                      double start, double end, boolean remove) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioCutter.writeCut(p, src, start, end, remove, out);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        double frameMs = 1152 * 1000.0 / 44100; // 26.122...

        // 1. CBR parse
        byte[] cbr = cbrFile(200);
        AudioCutter.Parsed p = AudioCutter.parse(cbr);
        checkEq("CBR frame count", p.frameCount(), 200);
        checkNear("CBR total duration", p.totalMs, 200 * frameMs, 0.01);
        checkEq("CBR frame length", p.frameLen[0], 417);

        // 2. Keep-range cut [1000, 2000): frames 38..76 = 39 frames
        byte[] kept = cut(p, cbr, 1000, 2000, false);
        AudioCutter.Parsed pk = AudioCutter.parse(kept);
        checkEq("keep cut frame count", pk.frameCount(), 39);
        checkNear("keep cut duration within one frame of range",
                pk.totalMs, 1000, frameMs);

        // 3. Remove-range cut [1000, 2000): 161 frames
        byte[] removed = cut(p, cbr, 1000, 2000, true);
        AudioCutter.Parsed pr = AudioCutter.parse(removed);
        checkEq("remove cut frame count", pr.frameCount(), 161);
        checkNear("remove cut duration within one frame",
                pr.totalMs, 200 * frameMs - 1000, frameMs);

        // 4. ID3v2 preserved byte-for-byte
        byte[] tag = id3v2(20);
        byte[] tagged = concat(tag, cbr);
        AudioCutter.Parsed pt = AudioCutter.parse(tagged);
        checkEq("ID3v2 length", pt.id3v2.length, 30);
        byte[] keptTag = cut(pt, tagged, 0, 1000, false);
        check("ID3v2 bytes preserved",
                Arrays.equals(Arrays.copyOf(keptTag, 30), tag));

        // 5. Xing patched on a cut starting at frame 0
        byte[] xing = xingFrame(9, 128, 200, 200 * 417);
        byte[] xfile = concat(xing, cbrFile(199)); // 200 frames total
        AudioCutter.Parsed px = AudioCutter.parse(xfile);
        checkEq("Xing file frame count", px.frameCount(), 200);
        byte[] xcut = cut(px, xfile, 0, 1000, false);
        // Xing lives at 4 (header) + 32 (side info) in the first frame.
        check("Xing magic kept",
                xcut[36] == 'X' && xcut[37] == 'i'
                        && xcut[38] == 'n' && xcut[39] == 'g');
        checkEq("Xing frames patched", intAt(xcut, 44), 39);
        checkEq("Xing bytes patched", intAt(xcut, 48), 39 * 417);

        // 6. ID3v1 preserved at the end
        byte[] v1file = concat(cbr, id3v1());
        AudioCutter.Parsed pv = AudioCutter.parse(v1file);
        check("ID3v1 detected", pv.id3v1 != null);
        byte[] v1cut = cut(pv, v1file, 0, 1000, false);
        check("ID3v1 bytes preserved at end",
                v1cut[v1cut.length - 128] == 'T'
                        && v1cut[v1cut.length - 127] == 'A'
                        && v1cut[v1cut.length - 126] == 'G');

        // 7. Footer/gap bytes dropped, tag kept
        byte[] gap = new byte[10]; // zeros, no false sync
        byte[] gapFile = concat(tag, gap, cbr);
        AudioCutter.Parsed pg = AudioCutter.parse(gapFile);
        checkEq("gap file audioStart", pg.audioStart, 40L);
        checkEq("gap file tag kept", pg.id3v2.length, 30);
        byte[] gapCut = cut(pg, gapFile, 0, pg.totalMs, false);
        checkEq("gap cut length (tag + frames, no gap)",
                gapCut.length, 30 + 200 * 417);

        // 8. VBR: alternating 128k / 192k / 96k frames
        ByteArrayOutputStream vb = new ByteArrayOutputStream();
        int[] idx = {9, 11, 7};
        int[] kbps = {128, 192, 96};
        for (int i = 0; i < 90; i++) {
            byte[] f = frame(idx[i % 3], kbps[i % 3]);
            vb.write(f, 0, f.length);
        }
        byte[] vbr = vb.toByteArray();
        AudioCutter.Parsed pvr = AudioCutter.parse(vbr);
        checkEq("VBR frame count", pvr.frameCount(), 90);
        checkEq("VBR frame lens", pvr.frameLen[0] + "," + pvr.frameLen[1]
                + "," + pvr.frameLen[2], "417,626,313");
        checkNear("VBR total duration", pvr.totalMs, 90 * frameMs, 0.01);

        // 9. Invalid input rejected
        boolean threw = false;
        try {
            AudioCutter.parse(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("garbage input rejected", threw);

        // 10. Empty selection rejected
        boolean threw2 = false;
        try {
            cut(p, cbr, 9000, 9500, false);
        } catch (IllegalArgumentException e) {
            threw2 = true;
        }
        check("empty selection rejected", threw2);

        // 11. File-name sanitizer
        checkEq("sanitize strips illegal chars",
                AudioCutter.sanitizeFileName("AC/DC: Best?"), "ACDC Best");
        checkEq("sanitize empty falls back",
                AudioCutter.sanitizeFileName("///"), "cut");

        System.out.println(failures == 0 ? "ALL PASS"
                : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }
}
