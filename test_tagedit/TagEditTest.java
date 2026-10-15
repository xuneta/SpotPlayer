package com.arman.spotplayer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * JVM round-trip test for TagEditor: builds MP3 (ID3v2.4) and FLAC fixtures
 * in memory, parses, edits, rebuilds, re-parses, and checks every field plus
 * byte-level preservation of unknown frames and audio data.
 * Run: javac + java from the JDK, no Android needed.
 */
public class TagEditTest {

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

    // ---------- fixture builders ----------

    static byte[] tinyPng() {
        byte[] h = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        byte[] body = new byte[40];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 7 + 3);
        return concat(h, body);
    }

    static byte[] tinyJpg() {
        byte[] h = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] body = new byte[60];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 13 + 5);
        return concat(h, body);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static byte[] utf8Body(String s) {
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        byte[] r = new byte[u.length + 1];
        r[0] = 3;
        System.arraycopy(u, 0, r, 1, u.length);
        return r;
    }

    static byte[] frame24(String id, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] bid = id.getBytes(StandardCharsets.ISO_8859_1);
        o.write(bid, 0, 4);
        int n = body.length;
        o.write((n >> 21) & 0x7F); o.write((n >> 14) & 0x7F);
        o.write((n >> 7) & 0x7F); o.write(n & 0x7F);
        o.write(0); o.write(0);
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    static byte[] txxxBody(String desc, String val) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(3); // UTF-8
        byte[] d = desc.getBytes(StandardCharsets.UTF_8);
        o.write(d, 0, d.length);
        o.write(0); // null terminator
        byte[] v = val.getBytes(StandardCharsets.UTF_8);
        o.write(v, 0, v.length);
        return o.toByteArray();
    }

    static byte[] apicBody(byte[] pic, String mime) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(3);
        byte[] m = mime.getBytes(StandardCharsets.ISO_8859_1);
        o.write(m, 0, m.length);
        o.write(0);
        o.write(3);
        o.write(0);
        o.write(pic, 0, pic.length);
        return o.toByteArray();
    }

    static byte[] usltBody(String lyrics) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(3);
        o.write('e'); o.write('n'); o.write('g');
        o.write(0);
        byte[] u = lyrics.getBytes(StandardCharsets.UTF_8);
        o.write(u, 0, u.length);
        return o.toByteArray();
    }

    static byte[] mp3Fixture() {
        byte[][] fr = {
            frame24("TIT2", utf8Body("Old Title")),
            frame24("TPE1", utf8Body("Old Artist")),
            frame24("TALB", utf8Body("Old Album")),
            frame24("TPE2", utf8Body("Old AlbumArtist")),
            frame24("TCON", utf8Body("(13)")),
            frame24("TDRC", utf8Body("2020")),
            frame24("TRCK", utf8Body("4/12")),
            frame24("TXXX", txxxBody("REPLAYGAIN_TRACK_GAIN", "-7.32 dB")),
            frame24("USLT", usltBody("old lyrics here")),
            frame24("APIC", apicBody(tinyPng(), "image/png")),
        };
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] f : fr) all.write(f, 0, f.length);
        byte[] fb = all.toByteArray();
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.write('I'); tag.write('D'); tag.write('3');
        tag.write(4); tag.write(0); tag.write(0);
        int n = fb.length;
        tag.write((n >> 21) & 0x7F); tag.write((n >> 14) & 0x7F);
        tag.write((n >> 7) & 0x7F); tag.write(n & 0x7F);
        tag.write(fb, 0, fb.length);
        // fake audio: MP3-ish sync bytes pattern
        byte[] audio = new byte[8192];
        for (int i = 0; i < audio.length; i++) audio[i] = (byte) (i % 2 == 0 ? 0xFF : 0xFB);
        return concat(tag.toByteArray(), audio);
    }

    static void writeLe32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }

    static void writeBe32(ByteArrayOutputStream o, int v) {
        o.write((v >> 24) & 0xFF); o.write((v >> 16) & 0xFF);
        o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
    }

    static byte[] vorbisEntry(String kv) {
        byte[] b = kv.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeLe32(o, b.length);
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    static byte[] flacFixture() {
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        // STREAMINFO (34 bytes)
        meta.write(0); meta.write(0); meta.write(0); meta.write(34);
        byte[] si = new byte[34];
        for (int i = 0; i < 34; i++) si[i] = (byte) (i + 1);
        meta.write(si, 0, 34);
        // VORBIS_COMMENT
        ByteArrayOutputStream vc = new ByteArrayOutputStream();
        byte[] vendor = "reference".getBytes(StandardCharsets.UTF_8);
        writeLe32(vc, vendor.length);
        vc.write(vendor, 0, vendor.length);
        byte[][] entries = {
            vorbisEntry("TITLE=Old Title"),
            vorbisEntry("ARTIST=Old Artist"),
            vorbisEntry("REPLAYGAIN_TRACK_GAIN=-5.00 dB"),
            vorbisEntry("CUSTOMKEY=keepme"),
        };
        writeLe32(vc, entries.length);
        for (byte[] e : entries) vc.write(e, 0, e.length);
        byte[] vcb = vc.toByteArray();
        meta.write(4); // VORBIS_COMMENT, not last
        meta.write((vcb.length >> 16) & 0xFF);
        meta.write((vcb.length >> 8) & 0xFF);
        meta.write(vcb.length & 0xFF);
        meta.write(vcb, 0, vcb.length);
        // PICTURE
        ByteArrayOutputStream pic = new ByteArrayOutputStream();
        writeBe32(pic, 3);
        byte[] mime = "image/png".getBytes(StandardCharsets.ISO_8859_1);
        writeBe32(pic, mime.length);
        pic.write(mime, 0, mime.length);
        writeBe32(pic, 0);
        writeBe32(pic, 0); writeBe32(pic, 0); writeBe32(pic, 0); writeBe32(pic, 0);
        byte[] png = tinyPng();
        writeBe32(pic, png.length);
        pic.write(png, 0, png.length);
        byte[] picb = pic.toByteArray();
        meta.write(6 | 0x80); // last block
        meta.write(0); meta.write((picb.length >> 8) & 0xFF); meta.write(picb.length & 0xFF);
        meta.write(picb, 0, picb.length);
        byte[] file = new byte[4 + meta.size() + 4096];
        file[0] = 'f'; file[1] = 'L'; file[2] = 'a'; file[3] = 'C';
        System.arraycopy(meta.toByteArray(), 0, file, 4, meta.size());
        for (int i = 4 + meta.size(); i < file.length; i++) file[i] = (byte) (i * 3);
        return file;
    }

    // ---------- tests ----------

    static void testMp3() {
        System.out.println("--- MP3 (ID3v2.4) ---");
        byte[] orig = mp3Fixture();
        check("detect MP3", TagEditor.detect(orig) == TagEditor.Format.MP3_ID3);
        TagEditor.Parsed p = TagEditor.read(orig);
        TagEditor.Tags t = p.tags;
        checkEq("title", t.title, "Old Title");
        checkEq("artist", t.artist, "Old Artist");
        checkEq("album", t.album, "Old Album");
        checkEq("albumArtist", t.albumArtist, "Old AlbumArtist");
        checkEq("genre (13)->Pop", t.genre, "Pop");
        checkEq("year", t.year, "2020");
        checkEq("track", t.track, "4");
        checkEq("trackTotal", t.trackTotal, "12");
        checkEq("lyrics", t.lyrics, "old lyrics here");
        check("cover present", t.cover != null && t.cover.length == tinyPng().length);
        checkEq("cover mime", t.coverMime, "image/png");
        check("unknown TXXX frame preserved in list", p.unknownFrames.size() == 1
                && p.unknownFrames.get(0).id.equals("TXXX"));

        // edit everything
        TagEditor.Tags e = new TagEditor.Tags();
        e.title = "New Title"; e.artist = "New Artist"; e.album = ""; // remove album
        e.albumArtist = "New AA"; e.genre = "Rock"; e.year = "2021";
        e.track = "5"; e.trackTotal = "10"; e.lyrics = "new\nlyrics";
        e.cover = tinyJpg(); e.coverMime = "image/jpeg";
        byte[] newTag = TagEditor.buildMp3Tag(p, e);
        byte[] full = concat(newTag, Arrays.copyOfRange(orig, (int) p.audioOffset, orig.length));

        // verify()
        check("verify() passes", TagEditor.verify(full, e));

        TagEditor.Parsed p2 = TagEditor.read(full);
        TagEditor.Tags t2 = p2.tags;
        checkEq("new title", t2.title, "New Title");
        checkEq("new artist", t2.artist, "New Artist");
        checkEq("album removed", t2.album, "");
        checkEq("new albumArtist", t2.albumArtist, "New AA");
        checkEq("new genre", t2.genre, "Rock");
        checkEq("new year", t2.year, "2021");
        checkEq("new track", t2.track, "5");
        checkEq("new trackTotal", t2.trackTotal, "10");
        checkEq("new lyrics", t2.lyrics, "new\nlyrics");
        check("new cover is jpg", t2.cover != null && t2.cover.length == tinyJpg().length
                && "image/jpeg".equals(t2.coverMime));
        // unknown TXXX preserved byte-for-byte
        byte[] oldTxxxBody = p.unknownFrames.get(0).body;
        byte[] newTxxxBody = p2.unknownFrames.get(0).body;
        check("unknown TXXX preserved byte-for-byte", Arrays.equals(oldTxxxBody, newTxxxBody));
        // audio tail identical
        byte[] audioOrig = Arrays.copyOfRange(orig, (int) p.audioOffset, orig.length);
        byte[] audioNew = Arrays.copyOfRange(full, (int) p2.audioOffset, full.length);
        check("audio tail byte-identical", Arrays.equals(audioOrig, audioNew));
        // version kept
        check("id3 v2.4 kept", p2.id3Ver == 4);

        // cover removal
        TagEditor.Tags r = new TagEditor.Tags();
        r.title = "New Title"; r.artist = "New Artist"; r.removeCover = true;
        byte[] tagR = TagEditor.buildMp3Tag(p, r);
        byte[] fullR = concat(tagR, Arrays.copyOfRange(orig, (int) p.audioOffset, orig.length));
        TagEditor.Parsed p3 = TagEditor.read(fullR);
        check("cover removed", p3.tags.cover == null);
        check("verify() passes on removal", TagEditor.verify(fullR, r));

        // untouched cover preserved
        TagEditor.Tags u = new TagEditor.Tags();
        u.title = "New Title"; u.artist = "New Artist";
        byte[] tagU = TagEditor.buildMp3Tag(p, u);
        byte[] fullU = concat(tagU, Arrays.copyOfRange(orig, (int) p.audioOffset, orig.length));
        TagEditor.Parsed p4 = TagEditor.read(fullU);
        check("untouched cover kept", p4.tags.cover != null
                && Arrays.equals(p4.tags.cover, tinyPng()));
    }

    static void testFlac() {
        System.out.println("--- FLAC ---");
        byte[] orig = flacFixture();
        check("detect FLAC", TagEditor.detect(orig) == TagEditor.Format.FLAC);
        TagEditor.Parsed p = TagEditor.read(orig);
        TagEditor.Tags t = p.tags;
        checkEq("title", t.title, "Old Title");
        checkEq("artist", t.artist, "Old Artist");
        check("cover present", t.cover != null && t.cover.length == tinyPng().length);
        check("unknown vorbis entries kept", p.unknownVorbis.size() == 2);

        TagEditor.Tags e = new TagEditor.Tags();
        e.title = "New Title"; e.artist = "New Artist"; e.album = "New Album";
        e.genre = "Jazz"; e.year = "2022"; e.track = "7"; e.lyrics = "flac lyrics";
        e.cover = tinyJpg(); e.coverMime = "image/jpeg";
        byte[] newMeta = TagEditor.buildFlacMetadata(p, e);
        byte[] full = concat(newMeta, Arrays.copyOfRange(orig, (int) p.audioOffset, orig.length));

        check("verify() passes", TagEditor.verify(full, e));
        TagEditor.Parsed p2 = TagEditor.read(full);
        TagEditor.Tags t2 = p2.tags;
        checkEq("new title", t2.title, "New Title");
        checkEq("new artist", t2.artist, "New Artist");
        checkEq("new album", t2.album, "New Album");
        checkEq("new genre", t2.genre, "Jazz");
        checkEq("new year", t2.year, "2022");
        checkEq("new track", t2.track, "7");
        checkEq("new lyrics", t2.lyrics, "flac lyrics");
        check("new cover jpg", t2.cover != null && t2.cover.length == tinyJpg().length);
        // STREAMINFO byte-identical
        check("STREAMINFO preserved", Arrays.equals(p.streamInfo, p2.streamInfo));
        // REPLAYGAIN + CUSTOMKEY preserved verbatim
        boolean rg = false, custom = false;
        for (byte[] en : p2.unknownVorbis) {
            String s = new String(en, StandardCharsets.UTF_8);
            if (s.equals("REPLAYGAIN_TRACK_GAIN=-5.00 dB")) rg = true;
            if (s.equals("CUSTOMKEY=keepme")) custom = true;
        }
        check("REPLAYGAIN_* preserved verbatim", rg);
        check("custom vorbis key preserved", custom);
        // audio tail identical
        byte[] audioOrig = Arrays.copyOfRange(orig, (int) p.audioOffset, orig.length);
        byte[] audioNew = Arrays.copyOfRange(full, (int) p2.audioOffset, full.length);
        check("audio tail byte-identical", Arrays.equals(audioOrig, audioNew));
    }

    static void testOggReadOnly() {
        System.out.println("--- OGG (read-only) ---");
        byte[] d = new byte[]{'O', 'g', 'g', 'S', 0, 0, 0};
        check("detect OGG", TagEditor.detect(d) == TagEditor.Format.OGG);
        check("OGG not editable", !TagEditor.isEditable(TagEditor.Format.OGG));
        check("MP3 editable", TagEditor.isEditable(TagEditor.Format.MP3_ID3));
        check("FLAC editable", TagEditor.isEditable(TagEditor.Format.FLAC));
    }

    public static void main(String[] args) {
        testMp3();
        testFlac();
        testOggReadOnly();
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
