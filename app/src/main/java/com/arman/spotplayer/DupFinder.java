package com.arman.spotplayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Duplicate finder, Musicolet-style grouping: songs whose normalized
 * title + artist match and whose durations are within +/-2 s are duplicates.
 *
 * Pure Java on purpose (operates on Entry, not Song) so the grouping and
 * normalization rules are unit-testable on the JVM.
 */
public final class DupFinder {

    /** Minimal song view for grouping; index points back into the real list. */
    public static final class Entry {
        public final String title;
        public final String artist;
        public final long durationMs;
        public final int index;

        public Entry(String title, String artist, long durationMs, int index) {
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
            this.index = index;
        }
    }

    public static final class Group {
        public final String key;
        public final List<Entry> members = new ArrayList<>();

        Group(String key) { this.key = key; }
    }

    private static final Pattern PARENS =
            Pattern.compile("\\s*[\\(\\[][^\\(\\[\\)\\]]*?(remaster|remix|live|acoustic|demo|"
                    + "unplugged|radio\\s*edit|single\\s*version|extended|instrumental|karaoke|"
                    + "sped\\s*up|slowed)[^\\(\\[\\)\\]]*?[\\)\\]]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DASH_SUFFIX =
            Pattern.compile("\\s*[-–—]\\s*(remaster(ed)?(\\s*\\d{4})?|live|acoustic)$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9\\s]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private DupFinder() {}

    /** Lowercase, drop edition/live/remaster qualifiers, strip punctuation. */
    public static String normalizeTitle(String t) {
        if (t == null) return "";
        String s = t.toLowerCase(Locale.US);
        s = PARENS.matcher(s).replaceAll("");
        s = DASH_SUFFIX.matcher(s).replaceAll("");
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        return s;
    }

    public static String normalizeArtist(String a) {
        if (a == null) return "";
        String s = a.toLowerCase(Locale.US);
        // "Artist feat. X" and "X & Artist" variants: keep the headliner.
        int f = s.indexOf(" feat");
        if (f > 0) s = s.substring(0, f);
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        return s;
    }

    /**
     * Group key: normalized title + artist + duration bucket. The duration
     * bucket is 2 s wide (rounded), so songs within +/-2 s normally land in
     * the same bucket. Boundary straddles are accepted as a rare miss rather
     * than widening the window and merging distinct tracks.
     */
    public static String groupKey(String title, String artist, long durationMs) {
        String nt = normalizeTitle(title);
        String na = normalizeArtist(artist);
        if (nt.isEmpty() || na.isEmpty()) return null; // unknowns never group
        long bucket = Math.round(durationMs / 2000.0);
        return nt + "\u0000" + na + "\u0000" + bucket;
    }

    /** Returns only groups with 2+ members, biggest groups first. */
    public static List<Group> findDuplicates(List<Entry> entries) {
        Map<String, Group> map = new LinkedHashMap<>();
        if (entries != null) {
            for (Entry e : entries) {
                String k = groupKey(e.title, e.artist, e.durationMs);
                if (k == null) continue;
                Group g = map.get(k);
                if (g == null) {
                    g = new Group(k);
                    map.put(k, g);
                }
                g.members.add(e);
            }
        }
        List<Group> out = new ArrayList<>();
        for (Group g : map.values()) {
            if (g.members.size() >= 2) out.add(g);
        }
        out.sort((a, b) -> Integer.compare(b.members.size(), a.members.size()));
        return out;
    }
}
