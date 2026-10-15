package com.arman.spotplayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java tag reader/writer for MP3 (ID3v2.2/2.3/2.4) and FLAC
 * (Vorbis comments + PICTURE block). OGG Vorbis comments can be READ;
 * writing OGG pages is deliberately out of scope ("not editable yet").
 *
 * Product rule (TagLib data model + Musicolet editor behavior):
 *  - fields: title, artist, album, album artist, genre, year, track (#/total),
 *    lyrics, cover art
 *  - PRESERVE everything we don't understand byte-for-byte: unknown ID3 frames,
 *    unknown Vorbis entries (incl. REPLAYGAIN_*), unknown FLAC blocks,
 *    STREAMINFO, extended headers. Only the frames/fields the user changed
 *    are rebuilt.
 *
 * This file imports NOTHING from android.* so the byte core is unit-testable
 * on a plain JVM. All stream I/O for the app goes through the static helpers
 * readCapped/skipFully (callers pass ContentResolver streams).
 *
 * MP3/FLAC writing is done in two parts: buildMp3Tag()/buildFlacMetadata()
 * return ONLY the new tag bytes; the caller then streams the audio tail
 * (everything from audioOffset() on) after them into a temp file.
 */
public final class TagEditor {

    private TagEditor() {}

    public enum Format { MP3_ID3, FLAC, OGG, OTHER }

    /** Editable fields. Empty string = remove the tag. */
    public static final class Tags {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String albumArtist = "";
        public String genre = "";
        public String year = "";       // "2021"
        public String track = "";      // "3"
        public String trackTotal = ""; // "12"
        public String lyrics = "";
        /** New cover bytes; null + removeCover=false means "leave existing cover". */
        public byte[] cover = null;
        public String coverMime = null; // "image/jpeg" or "image/png"
        public boolean removeCover = false;
    }

    /** One raw ID3v2 frame, preserved verbatim when we don't rewrite it. */
    public static final class Id3Frame {
        public final String id;
        public final byte[] flags; // 0 bytes (v2.2) or 2 bytes
        public final byte[] body;
        public Id3Frame(String id, byte[] flags, byte[] body) {
            this.id = id; this.flags = flags; this.body = body;
        }
    }

    /** One raw FLAC metadata block (not VORBIS_COMMENT/PICTURE), preserved verbatim. */
    public static final class FlacBlock {
        public final int type;
        public final byte[] data;
        public FlacBlock(int type, byte[] data) {
            this.type = type; this.data = data;
        }
    }

    /** Result of read(); carries everything needed for a byte-preserving rewrite. */
    public static final class Parsed {
        public Format format = Format.OTHER;
        public Tags tags = new Tags();
        // ---- MP3 ----
        public int id3Ver;                 // 2, 3 or 4
        public int id3Flags;               // original header flags (unsync refused at read)
        public byte[] extHeader;           // v2.3/v2.4 extended header, verbatim, or null
        public List<Id3Frame> unknownFrames = new ArrayList<>(); // kept verbatim, in order
        public byte[] origApicBody;        // original cover frame body, verbatim, or null
        public byte[] origApicFlags;       // original cover frame flags
        // ---- FLAC ----
        public byte[] streamInfo;          // 34 bytes, verbatim
        public List<FlacBlock> preservedBlocks = new ArrayList<>(); // other blocks, in order
        public List<byte[]> unknownVorbis = new ArrayList<>(); // raw "KEY=value" entries kept
        public byte[] origPicture;        // whole PICTURE block, verbatim, or null
        public String origPictureMime;
        /** Absolute offset in the ORIGINAL file where audio data starts. */
        public long audioOffset;
    }

    // ================= stream helpers =================

    /** Read at most cap bytes from a stream. */
    public static byte[] readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, 65536));
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            int room = cap - out.size();
            if (room <= 0) break;
            out.write(buf, 0, Math.min(n, room));
            if (out.size() >= cap) break;
        }
        return out.toByteArray();
    }

    /** Skip exactly n bytes, even on streams whose skip() is lazy. */
    public static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s > 0) { left -= s; continue; }
            int b = in.read();
            if (b < 0) throw new IOException("truncated while skipping");
            left--;
        }
    }

    // ================= detect =================

    public static Format detect(byte[] d) {
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            // FLAC with a leading ID3v2 tag is ambiguous (MediaStore reads its
            // Vorbis comments, not the ID3) — refuse instead of half-editing it.
            try {
                int ver = d[3] & 0xFF;
                int tagSize = ver == 4 ? syncsafe(d, 6)
                        : ver == 3 ? be32(d, 6)
                        : ((d[6] & 0xFF) << 16) | ((d[7] & 0xFF) << 8) | (d[8] & 0xFF);
                int off = 10 + tagSize;
                if (off + 4 <= d.length && d[off] == 'f' && d[off + 1] == 'L'
                        && d[off + 2] == 'a' && d[off + 3] == 'C') return Format.OTHER;
            } catch (Exception ignored) {}
            return Format.MP3_ID3;
        }
        if (d.length >= 4 && d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C')
            return Format.FLAC;
        if (d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S')
            return Format.OGG;
        return Format.OTHER;
    }

    public static boolean isEditable(Format f) {
        return f == Format.MP3_ID3 || f == Format.FLAC;
    }

    public static Parsed read(byte[] d) {
        Format f = detect(d);
        Parsed p = new Parsed();
        p.format = f;
        if (f == Format.MP3_ID3) readId3(d, p);
        else if (f == Format.FLAC) readFlac(d, p);
        else if (f == Format.OGG) readOggComment(d, p);
        else throw new IllegalArgumentException("not a tagged format");
        return p;
    }

    // ================= ID3v2 reading =================

    private static int syncsafe(byte[] d, int off) {
        return ((d[off] & 0x7F) << 21) | ((d[off + 1] & 0x7F) << 14)
                | ((d[off + 2] & 0x7F) << 7) | (d[off + 3] & 0x7F);
    }

    private static int be32(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static int le32(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static void readId3(byte[] d, Parsed p) {
        if (d.length < 10) throw new IllegalArgumentException("truncated id3 header");
        int ver = d[3] & 0xFF;
        if (ver < 2 || ver > 4) throw new IllegalArgumentException("id3 v" + ver);
        p.id3Ver = ver;
        int flags = d[4] & 0xFF;
        if ((flags & 0x80) != 0) throw new IllegalArgumentException("unsynchronized tag");
        p.id3Flags = flags;
        int tagSize = ver == 4 ? syncsafe(d, 6)
                : ver == 3 ? be32(d, 6)
                : ((d[6] & 0xFF) << 16) | ((d[7] & 0xFF) << 8) | (d[8] & 0xFF);
        int framesStart = 10;
        if (ver >= 3 && (flags & 0x40) != 0) {
            // extended header, preserved verbatim
            int extSize = ver == 4 ? syncsafe(d, 10) : be32(d, 10);
            if (extSize < 0 || 10 + extSize > d.length)
                throw new IllegalArgumentException("bad ext header");
            p.extHeader = new byte[extSize];
            System.arraycopy(d, 10, p.extHeader, 0, extSize);
            framesStart = 10 + extSize;
        }
        int framesEnd = Math.min(d.length, 10 + tagSize);
        if ((flags & 0x10) != 0) framesEnd = Math.min(d.length, framesEnd); // footer lives after
        p.audioOffset = 10L + tagSize + (((flags & 0x10) != 0) ? 10 : 0);
        boolean v22 = ver == 2;
        int pos = framesStart;
        int hdrLen = v22 ? 6 : 10;
        while (pos + hdrLen <= framesEnd) {
            int idLen = v22 ? 3 : 4;
            if (d[pos] == 0) break; // padding
            String id;
            try {
                id = new String(d, pos, idLen, StandardCharsets.ISO_8859_1);
            } catch (Exception e) { break; }
            if (!isId3Id(id)) break;
            int fsize;
            byte[] fflags;
            if (v22) {
                fsize = ((d[pos + 3] & 0xFF) << 16) | ((d[pos + 4] & 0xFF) << 8)
                        | (d[pos + 5] & 0xFF);
                fflags = new byte[0];
            } else {
                fsize = ver == 4 ? syncsafe(d, pos + 4) : be32(d, pos + 4);
                fflags = new byte[]{d[pos + 8], d[pos + 9]};
            }
            if (fsize <= 0 || pos + hdrLen + fsize > framesEnd) break;
            byte[] body = new byte[fsize];
            System.arraycopy(d, pos + hdrLen, body, 0, fsize);
            if (!mapId3Frame(p, id, fflags, body)) {
                p.unknownFrames.add(new Id3Frame(id, fflags, body));
            }
            pos += hdrLen + fsize;
        }
    }

    private static boolean isId3Id(String id) {
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /** Map a frame into Tags; returns true when the frame is an editable field. */
    private static boolean mapId3Frame(Parsed p, String id, byte[] flags, byte[] body) {
        boolean v22 = p.id3Ver == 2;
        // Only rewrite frames we fully understand; flagged (compressed/encrypted)
        // editable frames make the tag uneditable instead of corrupted.
        boolean flagged = false;
        for (byte b : flags) if (b != 0) { flagged = true; break; }
        String title = v22 ? "TT2" : "TIT2";
        String artist = v22 ? "TP1" : "TPE1";
        String album = v22 ? "TAL" : "TALB";
        String albumArtist = v22 ? "TP2" : "TPE2";
        String genre = v22 ? "TCO" : "TCON";
        String year = v22 ? "TYE" : (p.id3Ver == 4 ? "TDRC" : "TYER");
        String track = v22 ? "TRK" : "TRCK";
        String lyrics = v22 ? "ULT" : "USLT";
        String pic = v22 ? "PIC" : "APIC";
        if (id.equals(title)) {
            if (flagged) throw new IllegalArgumentException("flagged " + id);
            if (p.tags.title.isEmpty()) p.tags.title = decodeText(body);
            return true;
        }
        if (id.equals(artist)) {
            if (flagged) throw new IllegalArgumentException("flagged " + id);
            if (p.tags.artist.isEmpty()) p.tags.artist = decodeText(body);
            return true;
        }
        if (id.equals(album)) {
            if (flagged) throw new IllegalArgumentException("flagged " + id);
            if (p.tags.album.isEmpty()) p.tags.album = decodeText(body);
            return true;
        }
        if (id.equals(albumArtist)) {
            if (flagged) throw new IllegalArgumentException("flagged " + id);
            if (p.tags.albumArtist.isEmpty()) p.tags.albumArtist = decodeText(body);
            return true;
        }
        if (id.equals(genre)) {
            if (flagged) throw new IllegalArgumentException("flagged " + id);
            if (p.tags.genre.isEmpty()) p.tags.genre = decodeGenre(decodeText(body));
            return true;
        }
        if (id.equals(year)) {
            if (flagged) throw new IllegalArgumentException("flagged " + id);
            if (p.tags.year.isEmpty()) p.tags.year = first4(decodeText(body));
            return true;
        }
        if (id.equals(track)) {
            if (flagged) throw new IllegalArgumentException("flagged " + id);
            if (p.tags.track.isEmpty()) {
                String t = decodeText(body).trim();
                int slash = t.indexOf('/');
                if (slash >= 0) {
                    p.tags.track = t.substring(0, slash).trim();
                    p.tags.trackTotal = t.substring(slash + 1).trim();
                } else {
                    p.tags.track = t;
                }
            }
            return true;
        }
        if (id.equals(lyrics)) {
            if (flagged) throw new IllegalArgumentException("flagged " + id);
            if (p.tags.lyrics.isEmpty()) p.tags.lyrics = decodeUslt(body);
            return true;
        }
        if (id.equals(pic)) {
            if (flagged) throw new IllegalArgumentException("flagged " + id);
            if (p.tags.cover == null) {
                byte[][] pd = decodeApic(body, v22);
                if (pd != null) {
                    p.tags.cover = pd[1];
                    p.tags.coverMime = new String(pd[0], StandardCharsets.ISO_8859_1);
                    p.origApicBody = body;   // kept verbatim when untouched
                    p.origApicFlags = flags;
                }
            }
            return true;
        }
        return false;
    }

    private static Charset charset(int enc) {
        switch (enc) {
            case 1: return StandardCharsets.UTF_16;
            case 2: return StandardCharsets.UTF_16BE;
            case 3: return StandardCharsets.UTF_8;
            default: return StandardCharsets.ISO_8859_1;
        }
    }

    /** Decode a T*** text frame body. */
    static String decodeText(byte[] body) {
        if (body.length < 1) return "";
        int enc = body[0] & 0xFF;
        int end = body.length;
        if (enc == 1 || enc == 2) {
            while (end - 2 >= 1 && body[end - 2] == 0 && body[end - 1] == 0) end -= 2;
        } else {
            while (end - 1 >= 1 && body[end - 1] == 0) end--;
        }
        if (end <= 1) return "";
        try {
            return new String(body, 1, end - 1, charset(enc));
        } catch (Exception e) {
            return "";
        }
    }

    /** Skip a null-terminated string per encoding; returns offset past the terminator. */
    private static int skipTerm(byte[] d, int pos, int enc) {
        if (enc == 1 || enc == 2) {
            while (pos + 1 < d.length && !(d[pos] == 0 && d[pos + 1] == 0)) pos += 2;
            return Math.min(d.length, pos + 2);
        }
        while (pos < d.length && d[pos] != 0) pos++;
        return Math.min(d.length, pos + 1);
    }

    private static String decodeTextFrom(byte[] d, int pos, int enc) {
        if (pos >= d.length) return "";
        byte[] body = new byte[d.length - pos + 1];
        body[0] = (byte) enc;
        System.arraycopy(d, pos, body, 1, d.length - pos);
        return decodeText(body);
    }

    private static String decodeUslt(byte[] body) {
        if (body.length < 5) return "";
        int enc = body[0] & 0xFF;
        int pos = skipTerm(body, 1 + 3, enc); // skip lang(3) + descriptor
        return decodeTextFrom(body, pos, enc);
    }

    /** @return {mimeBytes, pictureBytes} or null. */
    private static byte[][] decodeApic(byte[] body, boolean v22) {
        try {
            if (body.length < 5) return null;
            int enc = body[0] & 0xFF;
            int pos = 1;
            byte[] mime;
            if (v22) {
                mime = new byte[3];
                System.arraycopy(body, pos, mime, 0, 3);
                pos += 3;
            } else {
                int mEnd = pos;
                while (mEnd < body.length && body[mEnd] != 0) mEnd++;
                mime = new byte[mEnd - pos];
                System.arraycopy(body, pos, mime, 0, mime.length);
                pos = mEnd + 1;
            }
            pos++; // picture type byte
            pos = skipTerm(body, pos, enc); // description
            if (pos >= body.length) return null;
            byte[] pic = new byte[body.length - pos];
            System.arraycopy(body, pos, pic, 0, pic.length);
            String m = new String(mime, StandardCharsets.ISO_8859_1).trim();
            if (v22) {
                if (m.equalsIgnoreCase("JPG")) m = "image/jpeg";
                else if (m.equalsIgnoreCase("PNG")) m = "image/png";
            }
            return new byte[][]{m.getBytes(StandardCharsets.ISO_8859_1), pic};
        } catch (Exception e) {
            return null;
        }
    }

    private static String first4(String s) {
        s = s.trim();
        return s.length() >= 4 ? s.substring(0, 4) : s;
    }

    private static String decodeGenre(String raw) {
        String s = raw.trim();
        // "(17)" or "17" or "(17)Rock" -> numeric code lookup
        int code = -1;
        if (s.startsWith("(")) {
            int e = s.indexOf(')');
            if (e > 1) {
                try { code = Integer.parseInt(s.substring(1, e)); } catch (Exception ignored) {}
            }
        } else {
            try { code = Integer.parseInt(s); } catch (Exception ignored) {}
        }
        if (code >= 0 && code < GENRES.length) return GENRES[code];
        return s;
    }

    private static final String[] GENRES = {
        "Blues","Classic Rock","Country","Dance","Disco","Funk","Grunge","Hip-Hop","Jazz",
        "Metal","New Age","Oldies","Other","Pop","R&B","Rap","Reggae","Rock","Techno",
        "Industrial","Alternative","Ska","Death Metal","Pranks","Soundtrack","Euro-Techno",
        "Ambient","Trip-Hop","Vocal","Jazz+Funk","Fusion","Trance","Classical","Instrumental",
        "Acid","House","Game","Sound Clip","Gospel","Noise","Alternative Rock","Bass","Soul",
        "Punk","Space","Meditative","Instrumental Pop","Instrumental Rock","Ethnic","Gothic",
        "Darkwave","Techno-Industrial","Electronic","Pop-Folk","Eurodance","Dream","Southern Rock",
        "Comedy","Cult","Gangsta","Top 40","Christian Rap","Pop/Funk","Jungle","Native US",
        "Cabaret","New Wave","Psychadelic","Rave","Showtunes","Trailer","Lo-Fi","Tribal",
        "Acid Punk","Acid Jazz","Polka","Retro","Musical","Rock & Roll","Hard Rock","Folk",
        "Folk-Rock","National Folk","Swing","Fast Fusion","Bebob","Latin","Revival","Celtic",
        "Bluegrass","Avantgarde","Gothic Rock","Progressive Rock","Psychedelic Rock",
        "Symphonic Rock","Slow Rock","Big Band","Chorus","Easy Listening","Acoustic","Humour",
        "Speech","Chanson","Opera","Chamber Music","Sonata","Symphony","Booty Bass","Primus",
        "Porn Groove","Satire","Slow Jam","Club","Tango","Samba","Folklore","Ballad",
        "Power Ballad","Rhythmic Soul","Freestyle","Duet","Punk Rock","Drum Solo","A capella",
        "Euro-House","Dance Hall","Goa","Drum & Bass","Club-House","Hardcore","Terror","Indie",
        "BritPop","Negerpunk","Polsk Punk","Beat","Christian Gangsta Rap","Heavy Metal",
        "Black Metal","Crossover","Contemporary Christian","Christian Rock","Merengue","Salsa",
        "Thrash Metal","Anime","JPop","Synthpop"
    };

    // ================= FLAC reading =================

    private static void readFlac(byte[] d, Parsed p) {
        if (d.length < 4) throw new IllegalArgumentException("truncated flac");
        int pos = 4;
        boolean seenStreamInfo = false;
        while (pos + 4 <= d.length) {
            boolean last = (d[pos] & 0x80) != 0;
            int type = d[pos] & 0x7F;
            int len = ((d[pos + 1] & 0xFF) << 16) | ((d[pos + 2] & 0xFF) << 8)
                    | (d[pos + 3] & 0xFF);
            if (pos + 4 + len > d.length)
                throw new IllegalArgumentException("truncated flac block");
            byte[] blk = new byte[len];
            System.arraycopy(d, pos + 4, blk, 0, len);
            if (type == 0) {
                if (len != 34) throw new IllegalArgumentException("bad STREAMINFO");
                p.streamInfo = blk;
                seenStreamInfo = true;
            } else if (type == 4) {
                parseVorbisBlock(blk, p);
            } else if (type == 6) {
                if (p.origPicture == null) { // first picture wins for display
                    byte[] whole = new byte[4 + len];
                    System.arraycopy(d, pos, whole, 0, 4 + len);
                    p.origPicture = whole;
                    byte[][] pd = decodeFlacPicture(blk);
                    if (pd != null) {
                        p.tags.cover = pd[1];
                        p.tags.coverMime = new String(pd[0], StandardCharsets.ISO_8859_1);
                    }
                } else {
                    p.preservedBlocks.add(new FlacBlock(type, blk)); // extra pictures preserved
                }
            } else {
                p.preservedBlocks.add(new FlacBlock(type, blk));
            }
            pos += 4 + len;
            if (last) break;
        }
        if (!seenStreamInfo) throw new IllegalArgumentException("no STREAMINFO");
        p.audioOffset = pos;
    }

    private static void parseVorbisBlock(byte[] blk, Parsed p) {
        try {
            int pos = 0;
            int vendor = le32(blk, pos); pos += 4 + vendor;
            int count = le32(blk, pos); pos += 4;
            for (int i = 0; i < count && pos + 4 <= blk.length; i++) {
                int elen = le32(blk, pos); pos += 4;
                if (elen <= 0 || pos + elen > blk.length) break;
                byte[] entry = new byte[elen];
                System.arraycopy(blk, pos, entry, 0, elen);
                pos += elen;
                if (!mapVorbisEntry(p, entry)) p.unknownVorbis.add(entry);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("bad vorbis comment");
        }
    }

    /** Map one raw "KEY=value" entry into Tags; true when it's an editable field. */
    private static boolean mapVorbisEntry(Parsed p, byte[] entry) {
        int eq = -1;
        for (int i = 0; i < entry.length; i++) {
            if (entry[i] == '=') { eq = i; break; }
        }
        if (eq <= 0) return true; // malformed -> drop silently? no: preserve
        String key;
        try {
            key = new String(entry, 0, eq, StandardCharsets.UTF_8).trim().toUpperCase();
        } catch (Exception e) { return false; }
        String val = new String(entry, eq + 1, entry.length - eq - 1, StandardCharsets.UTF_8);
        Tags t = p.tags;
        switch (key) {
            case "TITLE": if (t.title.isEmpty()) t.title = val; return true;
            case "ARTIST": if (t.artist.isEmpty()) t.artist = val; return true;
            case "ALBUM": if (t.album.isEmpty()) t.album = val; return true;
            case "ALBUMARTIST": if (t.albumArtist.isEmpty()) t.albumArtist = val; return true;
            case "GENRE": if (t.genre.isEmpty()) t.genre = val; return true;
            case "DATE": if (t.year.isEmpty()) t.year = first4(val); return true;
            case "TRACKNUMBER": if (t.track.isEmpty()) t.track = val.trim(); return true;
            case "TRACKTOTAL": if (t.trackTotal.isEmpty()) t.trackTotal = val.trim(); return true;
            case "LYRICS": if (t.lyrics.isEmpty()) t.lyrics = val; return true;
            default: return false; // preserved verbatim (incl. REPLAYGAIN_*)
        }
    }

    /** @return {mimeBytes, pictureBytes} or null. */
    private static byte[][] decodeFlacPicture(byte[] blk) {
        try {
            int pos = 0;
            pos += 4; // picture type
            int mimeLen = be32(blk, pos); pos += 4;
            byte[] mime = new byte[mimeLen];
            System.arraycopy(blk, pos, mime, 0, mimeLen); pos += mimeLen;
            int descLen = be32(blk, pos); pos += 4 + descLen;
            pos += 16; // w,h,depth,colors
            int dataLen = be32(blk, pos); pos += 4;
            if (pos + dataLen > blk.length) return null;
            byte[] pic = new byte[dataLen];
            System.arraycopy(blk, pos, pic, 0, dataLen);
            return new byte[][]{mime, pic};
        } catch (Exception e) {
            return null;
        }
    }

    // ================= OGG Vorbis comment reading (read-only) =================

    private static void readOggComment(byte[] d, Parsed p) {
        // Find the second packet (vorbis comment header starts with 0x03'vorbis').
        try {
            int limit = Math.min(d.length, 4 * 1024 * 1024);
            int pos = 0;
            ByteArrayOutputStream pkt = null;
            while (pos + 27 <= limit) {
                if (!(d[pos] == 'O' && d[pos + 1] == 'g' && d[pos + 2] == 'g'
                        && d[pos + 3] == 'S')) { pos++; continue; }
                int segCount = d[pos + 26] & 0xFF;
                if (pos + 27 + segCount > limit) break;
                int q = pos + 27 + segCount;
                for (int i = 0; i < segCount && q <= limit; i++) {
                    int segLen = d[pos + 27 + i] & 0xFF;
                    if (pkt == null) pkt = new ByteArrayOutputStream();
                    pkt.write(d, q, Math.min(segLen, limit - q));
                    q += segLen;
                    if (segLen < 255) {
                        byte[] b = pkt.toByteArray();
                        pkt = null;
                        if (b.length > 7 && b[0] == 0x03 && b[1] == 'v' && b[2] == 'o'
                                && b[3] == 'r' && b[4] == 'b' && b[5] == 'i'
                                && b[6] == 's') {
                            byte[] blk = new byte[b.length - 7];
                            System.arraycopy(b, 7, blk, 0, blk.length);
                            parseVorbisBlock(blk, p);
                            return;
                        }
                    }
                }
                pos = q;
            }
        } catch (Exception ignored) {}
    }

    // ================= MP3 writing (tag bytes only) =================

    /**
     * Build a complete new ID3v2 tag (header + ext header + frames + padding).
     * Keeps the original ID3v2 version; text frames are written UTF-8.
     */
    public static byte[] buildMp3Tag(Parsed p, Tags t) {
        boolean v22 = p.id3Ver == 2;
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        if (v22) {
            emitV22Text(frames, "TT2", t.title);
            emitV22Text(frames, "TP1", t.artist);
            emitV22Text(frames, "TAL", t.album);
            emitV22Text(frames, "TP2", t.albumArtist);
            emitV22Text(frames, "TCO", t.genre);
            emitV22Text(frames, "TYE", first4orEmpty(t.year));
            emitV22Text(frames, "TRK", trackString(t));
            emitUslt(frames, t.lyrics, v22);
            emitApic(frames, t, p, v22);
        } else {
            emitV23Text(frames, p.id3Ver, "TIT2", t.title);
            emitV23Text(frames, p.id3Ver, "TPE1", t.artist);
            emitV23Text(frames, p.id3Ver, "TALB", t.album);
            emitV23Text(frames, p.id3Ver, "TPE2", t.albumArtist);
            emitV23Text(frames, p.id3Ver, "TCON", t.genre);
            emitV23Text(frames, p.id3Ver, p.id3Ver == 4 ? "TDRC" : "TYER", first4orEmpty(t.year));
            emitV23Text(frames, p.id3Ver, "TRCK", trackString(t));
            emitUslt(frames, t.lyrics, false);
            emitApic(frames, t, p, false);
        }
        // unknown frames preserved verbatim, in original order
        for (Id3Frame f : p.unknownFrames) {
            byte[] b = f.body;
            if (v22) {
                writeAscii(frames, f.id, 3);
                frames.write((b.length >> 16) & 0xFF);
                frames.write((b.length >> 8) & 0xFF);
                frames.write(b.length & 0xFF);
            } else {
                writeAscii(frames, f.id, 4);
                if (p.id3Ver == 4) writeSyncsafe(frames, b.length);
                else writeBe32(frames, b.length);
                frames.write(f.flags, 0, 2);
            }
            frames.write(b, 0, b.length);
        }
        // standard padding so future edits fit without growing the tag
        byte[] pad = new byte[1024];
        frames.write(pad, 0, pad.length);

        int extLen = p.extHeader != null ? p.extHeader.length : 0;
        int tagSize = extLen + frames.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(10 + tagSize);
        out.write('I'); out.write('D'); out.write('3');
        out.write(p.id3Ver); out.write(0);
        int flags = p.id3Flags & 0x7F; // never write unsync
        if (p.extHeader == null) flags &= ~0x40;
        else flags |= 0x40;
        flags &= ~0x10; // no footer on write
        out.write(flags);
        if (p.id3Ver == 4) writeSyncsafe(out, tagSize);
        else if (p.id3Ver == 3) writeBe32(out, tagSize);
        else {
            out.write((tagSize >> 16) & 0xFF);
            out.write((tagSize >> 8) & 0xFF);
            out.write(tagSize & 0xFF);
        }
        if (p.extHeader != null) out.write(p.extHeader, 0, p.extHeader.length);
        out.write(frames.toByteArray(), 0, frames.size());
        return out.toByteArray();
    }

    private static String first4orEmpty(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() >= 4 ? s.substring(0, 4) : s;
    }

    private static String trackString(Tags t) {
        String n = t.track == null ? "" : t.track.trim();
        String tot = t.trackTotal == null ? "" : t.trackTotal.trim();
        if (n.isEmpty()) return "";
        return tot.isEmpty() ? n : n + "/" + tot;
    }

    private static byte[] utf8TextBody(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x03); // UTF-8
        try {
            byte[] u = s.getBytes(StandardCharsets.UTF_8);
            b.write(u, 0, u.length);
        } catch (Exception ignored) {}
        return b.toByteArray();
    }

    private static void emitV23Text(ByteArrayOutputStream out, int ver, String id, String val) {
        if (val == null || val.isEmpty()) return;
        byte[] body = utf8TextBody(val);
        writeAscii(out, id, 4);
        if (ver == 4) writeSyncsafe(out, body.length);
        else writeBe32(out, body.length);
        out.write(0); out.write(0); // flags
        out.write(body, 0, body.length);
    }

    private static void emitV22Text(ByteArrayOutputStream out, String id, String val) {
        if (val == null || val.isEmpty()) return;
        byte[] body = utf8TextBody(val);
        writeAscii(out, id, 3);
        out.write((body.length >> 16) & 0xFF);
        out.write((body.length >> 8) & 0xFF);
        out.write(body.length & 0xFF);
        out.write(body, 0, body.length);
    }

    private static void emitUslt(ByteArrayOutputStream out, String lyrics, boolean v22) {
        if (lyrics == null || lyrics.isEmpty()) return;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x03); // UTF-8
        b.write('e'); b.write('n'); b.write('g');
        b.write(0); // empty descriptor
        try {
            byte[] u = lyrics.getBytes(StandardCharsets.UTF_8);
            b.write(u, 0, u.length);
        } catch (Exception ignored) {}
        byte[] body = b.toByteArray();
        String id = v22 ? "ULT" : "USLT";
        if (v22) {
            writeAscii(out, id, 3);
            out.write((body.length >> 16) & 0xFF);
            out.write((body.length >> 8) & 0xFF);
            out.write(body.length & 0xFF);
        } else {
            writeAscii(out, id, 4);
            writeBe32(out, body.length);
            out.write(0); out.write(0);
        }
        out.write(body, 0, body.length);
    }

    private static void emitApic(ByteArrayOutputStream out, Tags t, Parsed p, boolean v22) {
        byte[] body;
        if (t.removeCover) return; // explicit removal -> no frame
        if (t.cover != null) {
            body = buildApicBody(t.cover,
                    t.coverMime != null ? t.coverMime : "image/jpeg", v22);
        } else if (p.origApicBody != null) {
            body = p.origApicBody; // untouched: original bytes, byte-for-byte
        } else {
            return;
        }
        String id = v22 ? "PIC" : "APIC";
        if (v22) {
            writeAscii(out, id, 3);
            out.write((body.length >> 16) & 0xFF);
            out.write((body.length >> 8) & 0xFF);
            out.write(body.length & 0xFF);
        } else {
            writeAscii(out, id, 4);
            if (p.id3Ver == 4) writeSyncsafe(out, body.length);
            else writeBe32(out, body.length);
            if (p.origApicFlags != null && t.cover == null && !t.removeCover) {
                out.write(p.origApicFlags, 0, 2); // original flags with original body
            } else {
                out.write(0); out.write(0);
            }
        }
        out.write(body, 0, body.length);
    }

    private static byte[] buildApicBody(byte[] pic, String mime, boolean v22) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x03); // UTF-8 description encoding
        if (v22) {
            String m3 = mime.equalsIgnoreCase("image/png") ? "PNG" : "JPG";
            writeAscii(b, m3, 3);
            b.write(0x03); // front cover
            b.write(0); // empty description
        } else {
            try {
                byte[] m = mime.getBytes(StandardCharsets.ISO_8859_1);
                b.write(m, 0, m.length);
            } catch (Exception ignored) {}
            b.write(0);
            b.write(0x03); // front cover
            b.write(0); // empty description
        }
        b.write(pic, 0, pic.length);
        return b.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s, int len) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < len; i++) out.write(i < b.length ? b[i] : 0);
    }

    private static void writeSyncsafe(ByteArrayOutputStream out, int v) {
        out.write((v >> 21) & 0x7F);
        out.write((v >> 14) & 0x7F);
        out.write((v >> 7) & 0x7F);
        out.write(v & 0x7F);
    }

    private static void writeBe32(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    // ================= FLAC writing (metadata bytes only) =================

    /**
     * Build a complete new FLAC metadata section ("fLaC" + blocks).
     * STREAMINFO and unknown blocks are copied verbatim; VORBIS_COMMENT is
     * rebuilt (unknown entries incl. REPLAYGAIN_* preserved verbatim);
     * the PICTURE block is replaced/removed/kept per the cover decision.
     */
    public static byte[] buildFlacMetadata(Parsed p, Tags t) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('f'); out.write('L'); out.write('a'); out.write('C');
        List<FlacBlock> blocks = new ArrayList<>();
        blocks.add(new FlacBlock(0, p.streamInfo));
        blocks.add(new FlacBlock(4, buildVorbisBlock(p, t)));
        byte[] pic = buildFlacPicture(p, t);
        if (pic != null) blocks.add(new FlacBlock(6, pic));
        blocks.addAll(p.preservedBlocks);
        // standard padding so future edits fit without rewriting audio
        blocks.add(new FlacBlock(1, new byte[1024]));
        for (int i = 0; i < blocks.size(); i++) {
            FlacBlock b = blocks.get(i);
            boolean last = i == blocks.size() - 1;
            out.write((last ? 0x80 : 0x00) | (b.type & 0x7F));
            out.write((b.data.length >> 16) & 0xFF);
            out.write((b.data.length >> 8) & 0xFF);
            out.write(b.data.length & 0xFF);
            out.write(b.data, 0, b.data.length);
        }
        return out.toByteArray();
    }

    private static byte[] buildVorbisBlock(Parsed p, Tags t) {
        List<byte[]> entries = new ArrayList<>();
        for (byte[] e : p.unknownVorbis) entries.add(e); // preserved verbatim
        addVorbis(entries, "TITLE", t.title);
        addVorbis(entries, "ARTIST", t.artist);
        addVorbis(entries, "ALBUM", t.album);
        addVorbis(entries, "ALBUMARTIST", t.albumArtist);
        addVorbis(entries, "GENRE", t.genre);
        addVorbis(entries, "DATE", first4orEmpty(t.year));
        addVorbis(entries, "TRACKNUMBER", trimOrEmpty(t.track));
        addVorbis(entries, "TRACKTOTAL", trimOrEmpty(t.trackTotal));
        addVorbis(entries, "LYRICS", t.lyrics == null ? "" : t.lyrics);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] vendor = "SpotPlayer".getBytes(StandardCharsets.UTF_8);
        writeLe32(out, vendor.length);
        out.write(vendor, 0, vendor.length);
        writeLe32(out, entries.size());
        for (byte[] e : entries) {
            writeLe32(out, e.length);
            out.write(e, 0, e.length);
        }
        return out.toByteArray();
    }

    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static void addVorbis(List<byte[]> entries, String key, String val) {
        if (val == null || val.isEmpty()) return;
        byte[] e = (key + "=" + val).getBytes(StandardCharsets.UTF_8);
        entries.add(e);
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    /** @return PICTURE block payload (without the 4-byte block header), or null. */
    private static byte[] buildFlacPicture(Parsed p, Tags t) {
        if (t.removeCover) return null;
        byte[] pic;
        String mime;
        if (t.cover != null) {
            pic = t.cover;
            mime = t.coverMime != null ? t.coverMime : "image/jpeg";
        } else if (p.origPicture != null) {
            byte[] whole = p.origPicture;
            byte[] payload = new byte[whole.length - 4];
            System.arraycopy(whole, 4, payload, 0, payload.length);
            return payload; // untouched: verbatim
        } else {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBe32(out, 3); // front cover
        byte[] m = mime.getBytes(StandardCharsets.ISO_8859_1);
        writeBe32(out, m.length);
        out.write(m, 0, m.length);
        writeBe32(out, 0); // empty description
        writeBe32(out, 0); writeBe32(out, 0); writeBe32(out, 0); writeBe32(out, 0); // w,h,depth,colors
        writeBe32(out, pic.length);
        out.write(pic, 0, pic.length);
        return out.toByteArray();
    }

    // ================= verify =================

    /**
     * Re-parse data and check it matches the intended tags.
     * Used to verify the temp file before the original is replaced.
     */
    public static boolean verify(byte[] data, Tags expected) {
        Parsed p;
        try {
            p = read(data);
        } catch (Exception e) {
            return false;
        }
        Tags got = p.tags;
        if (!eq(got.title, expected.title)) return false;
        if (!eq(got.artist, expected.artist)) return false;
        if (!eq(got.album, expected.album)) return false;
        if (!eq(got.albumArtist, expected.albumArtist)) return false;
        if (!eq(got.genre, expected.genre)) return false;
        if (!eq(first4orEmpty(got.year), first4orEmpty(expected.year))) return false;
        if (!eq(trimOrEmpty(got.track), trimOrEmpty(expected.track))) return false;
        if (!eq(trimOrEmpty(got.trackTotal), trimOrEmpty(expected.trackTotal))) return false;
        if (!eq(got.lyrics, expected.lyrics == null ? "" : expected.lyrics)) return false;
        if (expected.removeCover) {
            if (got.cover != null) return false;
        } else if (expected.cover != null) {
            if (got.cover == null || got.cover.length != expected.cover.length) return false;
        }
        return true;
    }

    private static boolean eq(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        return x.equals(y);
    }
}
