import com.arman.spotplayer.WaveformMath;
import com.arman.spotplayer.DupFinder;
import java.util.*;

public class TestPolish {
    static int pass = 0, fail = 0;
    static void check(boolean c, String name) {
        if (c) { pass++; } else { fail++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] a) {
        // ----- WaveformMath.downsample -----
        float[] peaks = new float[1000];
        for (int i = 0; i < 1000; i++) peaks[i] = (i % 100) / 100f; // sawtooth
        float[] b = WaveformMath.downsample(peaks, 120);
        check(b.length == 120, "downsample length 120");
        check(Math.abs(b[0] - 0.07f) < 0.001f, "downsample bucket0 max (0..7 -> 0.07)");
        check(Math.abs(b[119] - 0.99f) < 0.001f, "downsample last bucket max 0.99");
        float[] b2 = WaveformMath.downsample(peaks, 2000); // more buckets than input
        check(b2.length == 2000, "downsample upsample length 2000");
        check(WaveformMath.downsample(null, 120).length == 120, "downsample null -> zeros");
        check(WaveformMath.downsample(new float[0], 120).length == 120, "downsample empty -> zeros");
        check(WaveformMath.downsample(peaks, 0).length == 0, "downsample 0 buckets");

        // ----- normalize -----
        float[] q = {0.1f, 0.25f, 0.05f};
        WaveformMath.normalize(q);
        check(Math.abs(q[1] - 1.0f) < 0.001f, "normalize max->1");
        check(Math.abs(q[0] - 0.4f) < 0.001f, "normalize scaled proportionally");
        float[] silent = {0.005f, 0.001f};
        WaveformMath.normalize(silent);
        check(Math.abs(silent[0] - 0.005f) < 1e-6f, "normalize leaves near-silent alone");
        float[] full = {0.5f, 1.0f};
        WaveformMath.normalize(full);
        check(Math.abs(full[1] - 1.0f) < 1e-6f, "normalize leaves full-scale alone");

        // ----- silenceFraction -----
        check(Math.abs(WaveformMath.silenceFraction(new float[]{0f, 0f, 0.5f, 0f}) - 0.75f) < 1e-6f, "silenceFraction 0.75");

        // ----- DupFinder.normalizeTitle -----
        check(DupFinder.normalizeTitle("Song Name (Remastered 2015)").equals("song name"), "strip (Remastered 2015)");
        check(DupFinder.normalizeTitle("Song Name - Remaster").equals("song name"), "strip - Remaster");
        check(DupFinder.normalizeTitle("SONG  NAME!!").equals("song name"), "punct+case");
        check(DupFinder.normalizeTitle("Track (Live at Wembley)").equals("track"), "strip (Live...)");
        check(DupFinder.normalizeTitle("A [Acoustic] Version").equals("a version"), "strip [Acoustic]");
        check(DupFinder.normalizeTitle("  Hello   World  ").equals("hello world"), "whitespace collapse");

        // ----- normalizeArtist -----
        check(DupFinder.normalizeArtist("Artist feat. Someone").equals("artist"), "strip feat");
        check(DupFinder.normalizeArtist("The ARTIST").equals("the artist"), "artist case");

        // ----- grouping -----
        List<DupFinder.Entry> es = new ArrayList<>();
        es.add(new DupFinder.Entry("Song (Remastered 2020)", "Band", 183000, 0));
        es.add(new DupFinder.Entry("song", "Band feat. X", 184500, 1)); // +1.5s -> dup
        es.add(new DupFinder.Entry("Song", "Band", 210000, 2));          // +27s -> not dup
        es.add(new DupFinder.Entry("Other", "Band", 183000, 3));
        es.add(new DupFinder.Entry("Other!", "Band", 183200, 4));        // dup of Other
        es.add(new DupFinder.Entry("", "Band", 100000, 5));              // unknown title -> never groups
        es.add(new DupFinder.Entry("", "Band", 100000, 6));
        List<DupFinder.Group> gs = DupFinder.findDuplicates(es);
        check(gs.size() == 2, "two dup groups found");
        check(gs.get(0).members.size() == 2 && gs.get(1).members.size() == 2, "each group has 2");
        boolean songGroupOk = false;
        for (DupFinder.Group g : gs) {
            Set<Integer> idx = new HashSet<>();
            for (DupFinder.Entry e : g.members) idx.add(e.index);
            if (idx.contains(0) && idx.contains(1)) songGroupOk = true;
            check(!idx.contains(2), "long version not grouped");
            check(!idx.contains(5) && !idx.contains(6), "empty titles not grouped");
        }
        check(songGroupOk, "remaster + feat variants grouped");

        System.out.println("PASS " + pass + " / FAIL " + fail);
        if (fail > 0) System.exit(1);
    }
}
