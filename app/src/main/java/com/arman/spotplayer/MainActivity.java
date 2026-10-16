package com.arman.spotplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.media.MediaMetadataRetriever;
import android.media.audiofx.Equalizer;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity implements Player.Listener {

    private static final int REQ_AUDIO = 42;

    private List<Song> allSongs = new ArrayList<>();
    private SongAdapter homeAdapter, libraryAdapter;
    private View homeHeader;
    private boolean homeHeaderAdded;
    private SearchAdapter searchAdapter;
    private QuickAdapter quickAdapter;
    // ----- library sort (v5.3): per-page sort keys, persisted in "libsort" prefs -----

    private static final String[][] SONG_SORTS = {
            {"date", "Recent"}, {"az", "A–Z"}, {"za", "Z–A"}, {"artist", "Artist"},
            {"album", "Album"}, {"dur", "Duration"}, {"played", "Most played"}
    };
    private static final String[][] GROUP_SORTS = {
            {"az", "A–Z"}, {"za", "Z–A"}, {"count", "Most songs"}
    };

    private SharedPreferences libSort() {
        return getSharedPreferences("libsort", MODE_PRIVATE);
    }

    private boolean isGroupSortPage(String page) {
        return "artists".equals(page) || "albums".equals(page) || "genres".equals(page);
    }

    private String currentSortKey(String page) {
        return libSort().getString(page, "songs".equals(page) ? "date" : "az");
    }

    private String sortLabel(String page, String key) {
        String[][] opts = isGroupSortPage(page) ? GROUP_SORTS : SONG_SORTS;
        for (String[] o : opts) if (o[0].equals(key)) return o[1];
        return opts[0][1];
    }

    private void cycleSort(String page, String[][] opts) {
        String cur = currentSortKey(page);
        int i = 0;
        for (int j = 0; j < opts.length; j++) {
            if (opts[j][0].equals(cur)) { i = j; break; }
        }
        libSort().edit().putString(page, opts[(i + 1) % opts.length][0]).apply();
    }

    private List<Song> sortSongs(List<Song> in, String key) {
        List<Song> out = new ArrayList<>(in);
        switch (key) {
            case "az":
                Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
                break;
            case "za":
                Collections.sort(out, (a, b) -> b.title.compareToIgnoreCase(a.title));
                break;
            case "artist":
                Collections.sort(out, (a, b) -> {
                    int d = a.artist.compareToIgnoreCase(b.artist);
                    return d != 0 ? d : a.title.compareToIgnoreCase(b.title);
                });
                break;
            case "album":
                Collections.sort(out, (a, b) -> {
                    int d = a.album.compareToIgnoreCase(b.album);
                    return d != 0 ? d : a.title.compareToIgnoreCase(b.title);
                });
                break;
            case "dur":
                Collections.sort(out, (a, b) -> Long.compare(b.durationMs, a.durationMs));
                break;
            case "played": {
                Map<Long, Integer> counts = playCounts();
                Collections.sort(out, (a, b) -> {
                    int d = counts.getOrDefault(b.id, 0) - counts.getOrDefault(a.id, 0);
                    return d != 0 ? d : a.title.compareToIgnoreCase(b.title);
                });
                break;
            }
            default: // "date" — recently added
                Collections.sort(out, (a, b) -> Long.compare(b.dateAdded, a.dateAdded));
                break;
        }
        return out;
    }

    private List<String> sortGroupNames(List<String> names, Map<String, List<Song>> map, String key) {
        List<String> out = new ArrayList<>(names);
        switch (key) {
            case "za":
                Collections.sort(out, String.CASE_INSENSITIVE_ORDER.reversed());
                break;
            case "count":
                Collections.sort(out, (a, b) -> {
                    int d = map.get(b).size() - map.get(a).size();
                    return d != 0 ? d : a.compareToIgnoreCase(b);
                });
                break;
            default:
                Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
                break;
        }
        return out;
    }

    private String panelSortPage() {
        if (panelMode == 3) return "artists";
        if (panelMode == 4) return "albums";
        if (panelMode == 9) return "genres";
        return "group";
    }

    private void cyclePanelSort() {
        String page = panelSortPage();
        cycleSort(page, isGroupSortPage(page) ? GROUP_SORTS : SONG_SORTS);
        refreshPanel();
    }

    // ----- star ratings (v5.3, Poweramp model): 1-5 stars per song, "ratings" prefs -----

    private SharedPreferences ratings() {
        return getSharedPreferences("ratings", MODE_PRIVATE);
    }

    private int getRating(long songId) {
        return ratings().getInt("r_" + songId, 0);
    }

    private void setRating(long songId, int stars) {
        if (stars < 0) stars = 0;
        if (stars > 5) stars = 5;
        ratings().edit().putInt("r_" + songId, stars).apply();
        Song cur = Player.get().current();
        if (cur != null && cur.id == songId) updateNpStars(cur);
        refreshAllSongLists();
    }

    private void refreshAllSongLists() {
        if (libraryAdapter != null) libraryAdapter.notifyDataSetChanged();
        if (homeAdapter != null) homeAdapter.notifyDataSetChanged();
        if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
        if (panelView != null && panelView.getVisibility() == View.VISIBLE) refreshPanel();
    }

    private void showRateDialog(Song song) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int pad = (int) dp(20);
        row.setPadding(pad, pad, pad, pad);
        final TextView[] stars = new TextView[5];
        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < 5; i++) {
            TextView st = new TextView(this);
            st.setText("★");
            st.setTextSize(36);
            st.setPadding((int) dp(6), 0, (int) dp(6), 0);
            final int n = i + 1;
            st.setOnClickListener(v -> {
                setRating(song.id, n);
                if (holder[0] != null) holder[0].dismiss();
            });
            row.addView(st);
            stars[i] = st;
        }
        for (int i = 0; i < 5; i++) {
            stars[i].setTextColor(getColor(
                    i < getRating(song.id) ? R.color.spotify_green : R.color.spotify_gray));
        }
        holder[0] = new AlertDialog.Builder(this)
                .setTitle(song.title)
                .setView(row)
                .setNegativeButton("Clear", (d, w) -> setRating(song.id, 0))
                .create();
        holder[0].show();
    }

    private void updateNpStars(Song s) {
        if (npStars == null) return;
        int r = getRating(s.id);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < r ? "★" : "☆");
        npStars.setText(sb.toString());
        npStars.setTextColor(getColor(r > 0 ? R.color.spotify_green : R.color.spotify_gray));
    }

    /** Poweramp's "Top Rated" list: rated songs, stars desc, then play count. */
    private List<Song> topRatedSongs() {
        Map<Long, Integer> counts = playCounts();
        List<Song> out = new ArrayList<>();
        for (Song s : allSongs) {
            if (getRating(s.id) > 0) out.add(s);
        }
        Collections.sort(out, (a, b) -> {
            int d = getRating(b.id) - getRating(a.id);
            if (d != 0) return d;
            return counts.getOrDefault(b.id, 0) - counts.getOrDefault(a.id, 0);
        });
        return out;
    }

    private List<Song> recentlyAddedSongs() {
        List<Song> out = sortSongs(allSongs, "date");
        return out.size() > 100 ? new ArrayList<>(out.subList(0, 100)) : out;
    }

    private Map<String, List<Song>> genreMap() {
        Map<String, List<Song>> map = new java.util.LinkedHashMap<>();
        for (Song s : allSongs) {
            String g = (s.genre == null || s.genre.isEmpty()) ? "Unknown genre" : s.genre;
            List<Song> l = map.get(g);
            if (l == null) {
                l = new ArrayList<>();
                map.put(g, l);
            }
            l.add(s);
        }
        return map;
    }

    private void openGroupPage(String title, List<Song> songs, boolean sortable) {
        groupTitle = title;
        groupSongs = songs;
        groupSortable = sortable;
        openPanel(5, -1);
    }

    // ----- save queue as playlist (v5.3, Musicolet trick) -----

    private void showSaveQueueDialog() {
        List<Song> queue = Player.get().getQueue();
        if (queue == null || queue.isEmpty()) {
            toast("Nothing queued.");
            return;
        }
        EditText input = new EditText(this);
        input.setHint("Playlist name");
        input.setTextColor(getColor(R.color.spotify_white));
        input.setHintTextColor(getColor(R.color.spotify_gray));
        int pad = (int) dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this)
                .setTitle("Save queue as playlist")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    JSONArray arr = loadPlaylists();
                    try {
                        JSONObject o = new JSONObject();
                        o.put("name", name);
                        JSONArray ids = new JSONArray();
                        for (Song s : queue) ids.put(s.id);
                        o.put("ids", ids);
                        arr.put(o);
                    } catch (Exception ignored) {}
                    savePlaylists(arr);
                    renderPlaylistRows();
                    toast("Queue saved");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ----- grouped search (v5.3, Spotify library model): Songs / Artists / Albums -----

    private static class SearchItem {
        static final int HEADER = 0, SONG = 1, GROUP = 2;
        int type;
        String label;
        Song song;
        List<Song> groupSongs;
        boolean artists;
    }

    private class SearchAdapter extends android.widget.BaseAdapter {
        private final List<SearchItem> items = new ArrayList<>();
        private final List<Song> flatSongs = new ArrayList<>();
        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        void setQuery(String q) {
            items.clear();
            flatSongs.clear();
            String query = q == null ? "" : q.trim().toLowerCase();
            List<Song> songs = new ArrayList<>();
            java.util.LinkedHashMap<String, List<Song>> artists = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, List<Song>> albums = new java.util.LinkedHashMap<>();
            for (Song s : allSongs) {
                if (query.isEmpty()
                        || s.title.toLowerCase().contains(query)
                        || s.artist.toLowerCase().contains(query)
                        || s.album.toLowerCase().contains(query)) {
                    songs.add(s);
                }
                if (!query.isEmpty()) {
                    if (s.artist.toLowerCase().contains(query)) {
                        List<Song> l = artists.get(s.artist);
                        if (l == null) { l = new ArrayList<>(); artists.put(s.artist, l); }
                        l.add(s);
                    }
                    if (!s.album.isEmpty() && s.album.toLowerCase().contains(query)) {
                        List<Song> l = albums.get(s.album);
                        if (l == null) { l = new ArrayList<>(); albums.put(s.album, l); }
                        l.add(s);
                    }
                }
            }
            if (!songs.isEmpty()) {
                addHeader("Songs");
                for (Song s : songs) {
                    SearchItem it = new SearchItem();
                    it.type = SearchItem.SONG;
                    it.song = s;
                    items.add(it);
                    flatSongs.add(s);
                }
            }
            if (!artists.isEmpty()) {
                addHeader("Artists");
                for (Map.Entry<String, List<Song>> e : artists.entrySet()) {
                    addGroup(e.getKey(), e.getValue(), true);
                }
            }
            if (!albums.isEmpty()) {
                addHeader("Albums");
                for (Map.Entry<String, List<Song>> e : albums.entrySet()) {
                    addGroup(e.getKey(), e.getValue(), false);
                }
            }
            notifyDataSetChanged();
            findViewById(R.id.searchEmpty).setVisibility(
                    items.isEmpty() ? View.VISIBLE : View.GONE);
        }

        private void addHeader(String label) {
            SearchItem it = new SearchItem();
            it.type = SearchItem.HEADER;
            it.label = label;
            items.add(it);
        }

        private void addGroup(String label, List<Song> songs, boolean artists) {
            SearchItem it = new SearchItem();
            it.type = SearchItem.GROUP;
            it.label = label;
            it.groupSongs = songs;
            it.artists = artists;
            items.add(it);
        }

        List<Song> songItems() {
            return new ArrayList<>(flatSongs);
        }

        @Override public int getCount() { return items.size(); }

        @Override public Object getItem(int position) {
            SearchItem it = items.get(position);
            return it.type == SearchItem.SONG ? it.song : it;
        }

        @Override public long getItemId(int position) {
            SearchItem it = items.get(position);
            return it.type == SearchItem.SONG && it.song != null ? it.song.id : -position - 1;
        }

        @Override public int getViewTypeCount() { return 3; }

        @Override public int getItemViewType(int position) { return items.get(position).type; }

        @Override public boolean isEnabled(int position) {
            return items.get(position).type != SearchItem.HEADER;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SearchItem it = items.get(position);
            if (it.type == SearchItem.HEADER) {
                TextView tv = (TextView) convertView;
                if (tv == null) {
                    tv = new TextView(MainActivity.this);
                    tv.setTextSize(13);
                    tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
                    tv.setTextColor(getColor(R.color.spotify_gray));
                    tv.setPadding((int) dp(16), (int) dp(12), (int) dp(16), (int) dp(4));
                }
                tv.setText(it.label);
                return tv;
            }
            if (it.type == SearchItem.GROUP) {
                TextView tv = (TextView) convertView;
                if (tv == null) {
                    tv = (TextView) inflater.inflate(R.layout.item_group_row, parent, false);
                }
                int c = it.groupSongs != null ? it.groupSongs.size() : 0;
                tv.setText(it.label + "   ·   " + (c == 1 ? "1 song" : c + " songs"));
                return tv;
            }
            View v = convertView;
            if (v == null) {
                v = inflater.inflate(R.layout.item_song, parent, false);
            }
            SongAdapter.bindRow(v, it.song);
            return v;
        }
    }

    private View permissionView, mainContent;
    private View homeView, searchView, libraryView;
    private ImageView navHomeIcon, navSearchIcon, navLibraryIcon;
    private TextView navHomeLabel, navSearchLabel, navLibraryLabel;

    private View miniPlayer;
    private ImageView miniArt, miniPlay;
    private TextView miniTitle, miniArtist;
    private ProgressBar miniProgress;

    private View nowPlaying;
    private View panelView, eqView;
    private TextView panelTitle, panelSub, npQuality;
    private ListView panelList;
    private SongAdapter panelAdapter;
    private List<Song> panelSongs = new ArrayList<>();
    private int panelMode = 0; // 0 liked, 1 playlist, 2 queue
    private int panelPlaylistIndex = -1;
    private ImageView npSleepBtn, panelShuffleIcon;
    private boolean lastSleepActive = false;
    private final Map<Long, String> qualityCache = new HashMap<>();
    private boolean eqBuilt = false;
    private Equalizer probeEq;
    private int eqBandMin = -1500;
    private final List<SeekBar> eqBandSeeks = new ArrayList<>();
    private final List<TextView> eqBandVals = new ArrayList<>();
    private final List<TextView> eqPresetChips = new ArrayList<>();
    private final List<TextView> eqXfadeChips = new ArrayList<>();
    private final List<TextView> eqRgModeChips = new ArrayList<>();
    private final List<TextView> eqRgSrcChips = new ArrayList<>();
    private final List<TextView> eqXfModeChips = new ArrayList<>();
    private TextView rgPreChip, rgDefChip;

    // v5.4 listening pack
    private TextView rgScanStatus, rgScanChip;
    private TextView eqRouteLabel;
    private final List<TextView> eqSkipChips = new ArrayList<>();
    private final List<TextView> eqDblChips = new ArrayList<>();
    private final List<TextView> eqTrpChips = new ArrayList<>();
    private static final int[] SKIP_SHORT_VALUES = {0, 10, 20, 30};

    // Pro EQ: 10 parametric peaking bands at ISO centers (Hz), Q = 1.0, -15..+15 dB
    private static final int[] PRO_ISO_HZ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private static final String[] PRO_PRESET_NAMES = {
            "Flat", "Bass", "Treble", "Vocal", "Rock",
            "Pop", "Jazz", "Electronic", "Classical", "Dance"};
    private static final float[][] PRO_PRESETS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},      // Flat
            {6, 5, 4, 2, 0, 0, 0, 0, 0, 0},      // Bass
            {0, 0, 0, 0, 0, 1, 2, 3, 4, 5},      // Treble
            {0, 0, -2, -1, 0, 2, 4, 3, 1, 0},    // Vocal
            {5, 4, 2, 0, -1, -1, 0, 2, 4, 5},    // Rock
            {-1, 2, 4, 3, 1, 0, 0, 1, 2, 3},    // Pop
            {3, 2, 1, 0, 1, 2, 1, 0, 2, 3},      // Jazz
            {5, 4, 2, 0, 0, 0, 1, 2, 4, 5},      // Electronic
            {4, 3, 2, 0, 0, 0, 1, 2, 3, 4},      // Classical
            {5, 4, 3, 1, 0, 0, 1, 2, 3, 4},      // Dance
    };
    private Switch eqGaplessSwitch;
    private final int[] xfadeValues = {0, 2000, 4000, 6000};
    private Song pendingAddSong;
    private View npBackground;
    private ImageView npArt, npPlayIcon, npShuffle, npRepeat, npLike;
    private View npShuffleDot, npRepeatDot;
    private LinearLayout shelfRow;
    private View lyricsView, carView;
    private TextView lyricsText, lyricsSong, carTitle, carArtist, npSpeed;
    private android.widget.ScrollView lyricsScroll;
    private ImageView carArt, carPlayIcon;
    private List<Lyrics.Line> lyricLines;
    private long lyricSongId = -1;
    private int lyricHighlight = -2;
    private ArrayAdapter<String> groupAdapter;
    private List<String> groupNames;
    private Map<String, List<Song>> groupMapCache;
    private List<Song> groupSongs;
    private String groupTitle;
    private boolean groupSortable = true;
    private Map<String, List<Song>> genreMapCache;
    private final float[] speedSteps = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private long lastSwipeAt = 0;
    private boolean suppressRecordOnce = false;
    private TextView npTitle, npArtist, npAlbum, npPos, npDur, npRepeatBadge;
    private TextView npStars;
    private TextView npNextUp;
    private TextView npAb;
    private ImageView npBigHeart;
    private TextView forgotTitle;
    private View forgotScroll;
    private LinearLayout forgotRow;
    private int abA = -1;
    private int abB = -1;
    private long lastArtTapUp = 0;
    private SeekBar npSeek;
    private WaveformView npWave;
    private boolean userSeeking = false;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private Runnable tick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v5.6.1 crash catcher: save the stack trace, show it on next launch.
        final Thread.UncaughtExceptionHandler prevHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                getSharedPreferences("crashlog", MODE_PRIVATE).edit()
                        .putString("last", sw.toString()).commit();
            } catch (Exception ignored) {}
            if (prevHandler != null) prevHandler.uncaughtException(t, e);
        });
        // v5.5 AMOLED theme must be set before inflation.
        if (getSharedPreferences("ui", MODE_PRIVATE).getBoolean("amoled", false)) {
            setTheme(R.style.AppTheme_Amoled);
        }
        super.onCreate(savedInstanceState);
        // v5.6.5: the UI is designed LTR (Spotify layout). On RTL locales
        // (e.g. Persian) the system mirrors every layout and scrambles the
        // player, so pin the window to LTR (manifest also sets
        // supportsRtl=false).
        try {
            getWindow().getDecorView().setLayoutDirection(
                    android.view.View.LAYOUT_DIRECTION_LTR);
        } catch (Exception ignored) {}
        setContentView(R.layout.activity_main);
        bindViews();
        showCrashLogIfAny();

        if (hasAudioPermission()) {
            permissionView.setVisibility(View.GONE);
            mainContent.setVisibility(View.VISIBLE);
            initLibrary();
        } else {
            permissionView.setVisibility(View.VISIBLE);
            mainContent.setVisibility(View.GONE);
            findViewById(R.id.btnAllow).setOnClickListener(v -> onAllowAccessClicked());
        }

        Player.get().setListener(this);
        tick = new Runnable() {
            @Override
            public void run() {
                updateProgress();
                tickListeningAndAb();
                ticker.postDelayed(this, 200);
            }
        };
        ticker.post(tick);
    }

    /** Shows the saved crash trace (v5.6.1 diagnostics) with copy/clear actions. */
    private void showCrashLogIfAny() {
        String trace = getSharedPreferences("crashlog", MODE_PRIVATE)
                .getString("last", null);
        if (trace == null || trace.isEmpty()) return;
        TextView tv = new TextView(this);
        tv.setText(trace);
        tv.setTextSize(10);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(32, 16, 32, 16);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Crash report")
                .setView(sv)
                .setPositiveButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("crash", trace));
                })
                .setNegativeButton("Clear", (d, w) ->
                        getSharedPreferences("crashlog", MODE_PRIVATE).edit()
                                .remove("last").apply())
                .setNeutralButton("Close", null)
                .show();
    }

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** "Allow access" tap. After a permanent denial ("Don't ask again") the system
     *  silently ignores requestPermissions — send the user to the app settings
     *  page instead of leaving a dead button. */
    private void onAllowAccessClicked() {
        boolean asked = getSharedPreferences("ui", MODE_PRIVATE)
                .getBoolean("perm_asked", false);
        if (asked && !shouldShowRequestPermissionRationale(audioPermission())) {
            android.widget.Toast.makeText(this,
                    "Permission is off. Turn on \u201CMusic and audio\u201D in Settings.",
                    android.widget.Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        getSharedPreferences("ui", MODE_PRIVATE).edit()
                .putBoolean("perm_asked", true).apply();
        requestPermissions(new String[]{audioPermission()}, REQ_AUDIO);
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            permissionView.setVisibility(View.GONE);
            mainContent.setVisibility(View.VISIBLE);
            initLibrary();
        }
    }

    private void bindViews() {
        permissionView = findViewById(R.id.permissionView);
        mainContent = findViewById(R.id.mainContent);
        homeView = findViewById(R.id.homeView);
        searchView = findViewById(R.id.searchView);
        libraryView = findViewById(R.id.libraryView);

        navHomeIcon = findViewById(R.id.navHomeIcon);
        navSearchIcon = findViewById(R.id.navSearchIcon);
        navLibraryIcon = findViewById(R.id.navLibraryIcon);
        navHomeLabel = findViewById(R.id.navHomeLabel);
        navSearchLabel = findViewById(R.id.navSearchLabel);
        navLibraryLabel = findViewById(R.id.navLibraryLabel);
        findViewById(R.id.navHome).setOnClickListener(v -> showTab(0));
        findViewById(R.id.navSearch).setOnClickListener(v -> showTab(1));
        findViewById(R.id.navLibrary).setOnClickListener(v -> showTab(2));

        miniPlayer = findViewById(R.id.miniPlayer);
        miniArt = findViewById(R.id.miniArt);
        miniTitle = findViewById(R.id.miniTitle);
        miniArtist = findViewById(R.id.miniArtist);
        miniPlay = findViewById(R.id.miniPlay);
        miniProgress = findViewById(R.id.miniProgress);
        miniPlay.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            Player.get().toggle(this);
        });
        miniPlayer.setOnClickListener(v -> {
            if (android.os.SystemClock.uptimeMillis() - lastSwipeAt < 400) return;
            if (Player.get().current() != null) {
                showNowPlaying();
            }
        });
        // Mini-player gestures (MOTION_SPEC): horizontal swipe = next/prev,
        // drag UP = expand to Now Playing, drag DOWN = dismiss + full stop.
        // The bar follows the finger vertically; release settles with the
        // spec's springs (350 ms emphasized back, 320 ms emphasized-accel out).
        final float[] gDown = {0f, 0f};
        final int[] gMode = {0}; // 0 undecided, 1 horizontal, 2 vertical
        final android.view.VelocityTracker[] gVt = {null};
        miniPlayer.setOnTouchListener((v, e) -> {
            final float slop = dp(8);
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    gDown[0] = e.getX();
                    gDown[1] = e.getY();
                    gMode[0] = 0;
                    if (gVt[0] != null) gVt[0].recycle();
                    gVt[0] = android.view.VelocityTracker.obtain();
                    gVt[0].addMovement(e);
                    return false;
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (gVt[0] != null) gVt[0].addMovement(e);
                    float dx = e.getX() - gDown[0];
                    float dy = e.getY() - gDown[1];
                    if (gMode[0] == 0 && Math.max(Math.abs(dx), Math.abs(dy)) > slop) {
                        gMode[0] = Math.abs(dy) > Math.abs(dx) ? 2 : 1;
                        if (gMode[0] == 2) v.animate().cancel();
                    }
                    if (gMode[0] == 2 && Player.get().current() != null) {
                        float h = Math.max(v.getHeight(), 1);
                        if (dy >= 0) {
                            v.setTranslationY(dy);
                            v.setAlpha(1f - 0.45f * Math.min(1f, dy / h));
                        } else {
                            v.setTranslationY(Math.max(dy * 0.55f, -dp(72)));
                            v.setAlpha(1f);
                        }
                        return true;
                    }
                    return gMode[0] == 2;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    float dx = e.getX() - gDown[0];
                    float dy = e.getY() - gDown[1];
                    float vy = 0f;
                    if (gVt[0] != null) {
                        gVt[0].addMovement(e);
                        gVt[0].computeCurrentVelocity(1000);
                        vy = gVt[0].getYVelocity();
                        gVt[0].recycle();
                        gVt[0] = null;
                    }
                    boolean wasVertical = gMode[0] == 2;
                    gMode[0] = 0;
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                        if (wasVertical) {
                            v.animate().translationY(0f).alpha(1f).setDuration(350)
                                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                                    .start();
                        }
                        return wasVertical;
                    }
                    if (wasVertical && Player.get().current() != null) {
                        lastSwipeAt = android.os.SystemClock.uptimeMillis();
                        float h = Math.max(v.getHeight(), 1);
                        if (dy > h * 0.45f || (dy > dp(20) && vy > 800f)) {
                            // dismiss: slide out, then stop playback completely
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                            v.animate().translationY(h + dp(8)).alpha(0f).setDuration(320)
                                    .setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f))
                                    .withEndAction(() -> {
                                        Player.get().stopFully(MainActivity.this);
                                        v.setTranslationY(0f);
                                        v.setAlpha(1f);
                                    })
                                    .start();
                        } else if (dy < -dp(40) || (dy < -dp(16) && vy < -800f)) {
                            // expand: settle back, Now Playing's own entrance carries it
                            v.animate().translationY(0f).alpha(1f).setDuration(180)
                                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                                    .withEndAction(() -> showNowPlaying())
                                    .start();
                        } else {
                            v.animate().translationY(0f).alpha(1f).setDuration(350)
                                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                                    .start();
                        }
                        return true;
                    }
                    if (Math.abs(dx) > dp(56) && Player.get().current() != null) {
                        lastSwipeAt = android.os.SystemClock.uptimeMillis();
                        if (dx < 0) Player.get().next(this);
                        else Player.get().prev(this);
                    }
                    return false;
                }
                default:
                    return false;
            }
        });

        nowPlaying = findViewById(R.id.nowPlaying);
        npBackground = findViewById(R.id.npBackground);
        npArt = findViewById(R.id.npArt);
        npPlayIcon = findViewById(R.id.npPlayIcon);
        npShuffle = findViewById(R.id.npShuffle);
        npRepeat = findViewById(R.id.npRepeat);
        npLike = findViewById(R.id.npLike);
        npTitle = findViewById(R.id.npTitle);
        npArtist = findViewById(R.id.npArtist);
        npAlbum = findViewById(R.id.npAlbum);
        npPos = findViewById(R.id.npPos);
        npDur = findViewById(R.id.npDur);
        npRepeatBadge = findViewById(R.id.npRepeatBadge);
        npShuffleDot = findViewById(R.id.npShuffleDot);
        npRepeatDot = findViewById(R.id.npRepeatDot);
        lyricsView = findViewById(R.id.lyricsView);
        carView = findViewById(R.id.carView);
        lyricsText = findViewById(R.id.lyricsText);
        lyricsSong = findViewById(R.id.lyricsSong);
        lyricsScroll = findViewById(R.id.lyricsScroll);
        carArt = findViewById(R.id.carArt);
        carPlayIcon = findViewById(R.id.carPlayIcon);
        carTitle = findViewById(R.id.carTitle);
        carArtist = findViewById(R.id.carArtist);
        npSpeed = findViewById(R.id.npSpeed);
        npNextUp = findViewById(R.id.npNextUp);
        npAb = findViewById(R.id.npAb);
        npBigHeart = findViewById(R.id.npBigHeart);
        npSeek = findViewById(R.id.npSeek);
        npStars = findViewById(R.id.npStars);
        if (npStars != null) {
            npStars.setOnClickListener(v -> {
                Song s = Player.get().current();
                if (s != null) showRateDialog(s);
            });
        }
        npArt.setClipToOutline(true);
        miniArt.setClipToOutline(true);

        findViewById(R.id.npClose).setOnClickListener(v -> hideNowPlaying());
        findViewById(R.id.npPlayBtn).setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            Player.get().toggle(this);
        });
        findViewById(R.id.npNext).setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            Player.get().next(this);
        });
        findViewById(R.id.npPrev).setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            Player.get().prev(this);
        });
        npShuffle.setOnClickListener(v -> {
            boolean s = !Player.get().isShuffle();
            Player.get().setShuffle(s);
            tintToggle(npShuffle, s);
            updateNextUp();
        });
        findViewById(R.id.npRepeatBtn).setOnClickListener(v -> {
            int r = Player.get().cycleRepeat();
            tintToggle(npRepeat, r != 0);
            npRepeatBadge.setVisibility(r == 2 ? View.VISIBLE : View.GONE);
            updateNextUp();
        });
        npLike.setOnClickListener(v -> toggleLike());
        npSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) npPos.setText(Song.formatTime(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
                npSeek.setThumb(getDrawable(R.drawable.thumb_white_big));
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                npSeek.setThumb(getDrawable(R.drawable.thumb_white));
                Player.get().seekTo(sb.getProgress());
            }
        });
        initWaveform();
        initDuplicates();
  
        final float[] artDown = new float[2];
        npArt.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    artDown[0] = e.getX();
                    artDown[1] = e.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = e.getX() - artDown[0];
                    float dy = e.getY() - artDown[1];
                    if (Math.abs(dx) < dp(24) && Math.abs(dy) < dp(24)) {
                        long nowUp = android.os.SystemClock.uptimeMillis();
                        if (nowUp - lastArtTapUp < 320) {
                            lastArtTapUp = 0;
                            doubleTapLike();
                        } else {
                            lastArtTapUp = nowUp;
                        }
                        return true;
                    }
                    if (Math.abs(dx) > dp(64) && Math.abs(dx) > Math.abs(dy)) {
                        boolean next = dx < 0;
                        npArt.animate().translationX(next ? -dp(36) : dp(36)).alpha(0.4f)
                                .setDuration(120)
                                .withEndAction(() -> {
                                    if (next) Player.get().next(MainActivity.this);
                                    else Player.get().prev(MainActivity.this);
                                    npArt.setTranslationX(next ? dp(36) : -dp(36));
                                    npArt.animate().translationX(0f).alpha(1f).setDuration(250)
                                            .setInterpolator(new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
                                            .start();
                                }).start();
                    } else if (dy > dp(90) && Math.abs(dy) > Math.abs(dx)) {
                        hideNowPlaying();
                    }
                    return true;
                default:
                    return false;
            }
        });
        softPress(miniPlay);
        softPress(findViewById(R.id.npPlayBtn));
        softPress(findViewById(R.id.npNext));
        softPress(findViewById(R.id.npPrev));
        softPress(npShuffle);
        softPress(npLike);
        softPress(findViewById(R.id.npClose));
        softPress(findViewById(R.id.npRepeatBtn), npRepeat, npRepeat);

        panelView = findViewById(R.id.panelView);
        eqView = findViewById(R.id.eqView);
        panelTitle = findViewById(R.id.panelTitle);
        panelSub = findViewById(R.id.panelSub);
        panelList = findViewById(R.id.panelList);
        npQuality = findViewById(R.id.npQuality);
        npSleepBtn = findViewById(R.id.npSleepBtn);
        panelShuffleIcon = findViewById(R.id.panelShuffleIcon);
        findViewById(R.id.npQueueBtn).setOnClickListener(v -> openPanel(2, -1));
        npSleepBtn.setOnClickListener(v -> showSleepDialog());
        softPress(findViewById(R.id.npQueueBtn));
        softPress(npSleepBtn);
        findViewById(R.id.npCarBtn).setOnClickListener(v -> { openCar(); });
        findViewById(R.id.npLyricsBtn).setOnClickListener(v -> { openLyrics(); });
        findViewById(R.id.lyricsClose).setOnClickListener(v -> closeLyrics());
        findViewById(R.id.lyricsEdit).setOnClickListener(v -> { openLyricsEditor(); });
        npSpeed.setOnClickListener(v -> { cycleSpeed(); });
        findViewById(R.id.npShareBtn).setOnClickListener(v -> {
            Song s = Player.get().current();
            if (s == null) return;
            new AlertDialog.Builder(this)
                    .setTitle("Share")
                    .setItems(new String[]{"Song file", "Listening card (image)"}, (d, w) -> {
                        if (w == 0) shareSong(s);
                        else shareCard(s);
                    })
                    .show();
        });
        npAb.setOnClickListener(v -> {
            Song s = Player.get().current();
            if (s == null) return;
            int pos = Player.get().position();
            if (abA < 0) {
                abA = pos;
                npAb.setText("A set…");
                android.widget.Toast.makeText(this,
                        "Loop start set — tap A–B again at the end point",
                        android.widget.Toast.LENGTH_SHORT).show();
            } else if (abB < 0 && pos > abA) {
                abB = pos;
                npAb.setText("A–B on");
                npAb.setTextColor(getColor(R.color.spotify_green));
            } else if (abB < 0) {
                // Second tap landed before A: move A here instead of silently resetting
                abA = pos;
                android.widget.Toast.makeText(this,
                        "Loop start moved — tap A–B again at the end point",
                        android.widget.Toast.LENGTH_SHORT).show();
            } else {
                resetAb();
            }
        });
        findViewById(R.id.carClose).setOnClickListener(v -> closeCar());
        findViewById(R.id.carPlayBtn).setOnClickListener(v -> { Player.get().toggle(this); });
        findViewById(R.id.carNext).setOnClickListener(v -> { Player.get().next(this); });
        findViewById(R.id.carPrev).setOnClickListener(v -> { Player.get().prev(this); });
        Player.get().setSpeed(fx().getFloat("speed", 1f));
        updateSpeedLabel();
        View panelBack = findViewById(R.id.panelBack);
        panelBack.setOnClickListener(v -> closePanel());
        softPress(panelBack);
        View panelPlayBtn = findViewById(R.id.panelPlayBtn);
        panelPlayBtn.setOnClickListener(v -> panelPlay());
        softPress(panelPlayBtn, panelPlayBtn, null);
        findViewById(R.id.panelShuffleBtn).setOnClickListener(v -> panelShuffle());
        View panelSaveBtn = findViewById(R.id.panelSaveBtn);
        if (panelSaveBtn != null) {
            panelSaveBtn.setOnClickListener(v -> showSaveQueueDialog());
            softPress(panelSaveBtn);
        }
        View panelSortChip = findViewById(R.id.panelSortChip);
        if (panelSortChip != null) {
            panelSortChip.setOnClickListener(v -> cyclePanelSort());
            softPress(panelSortChip);
        }
        panelList.setOnItemClickListener((parent, view, position, id) -> panelItemClick(position));
        panelList.setOnItemLongClickListener((parent, view, position, id) -> panelItemLongClick(position));
        panelList.setOnTouchListener((v, e) -> {
            if (panelMode != 2) return false;
            int pos = panelList.pointToPosition((int) e.getX(), (int) e.getY());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (pos == android.widget.AdapterView.INVALID_POSITION) return false;
                    dragPos = pos;
                    dragStartY = e.getY();
                    dragArmed = false;
                    dragMoved = false;
                    dragHandler.postDelayed(dragStarter, 400);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!dragArmed) {
                        if (Math.abs(e.getY() - dragStartY) > dp(10)) {
                            dragHandler.removeCallbacks(dragStarter);
                        }
                        return false;
                    }
                    if (pos != android.widget.AdapterView.INVALID_POSITION
                            && dragPos >= 0 && pos != dragPos) {
                        Player.get().moveQueueItem(dragPos, pos);
                        dragMoved = true;
                        panelSongs = Player.get().getQueue();
                        if (panelAdapter != null) panelAdapter.setSongs(panelSongs);
                        dragPos = pos;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean wasArmed = dragArmed;
                    cancelDrag();
                    return wasArmed;
                default:
                    return false;
            }
        });
    }

    private void initLibrary() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
        }
        new Thread(() -> {
            List<Song> loaded = MusicLibrary.loadSongs(this);
            runOnUiThread(() -> {
                allSongs = loaded;
                Player.get().setLibrarySongs(loaded);
                setupLists();
            });
        }).start();
    }

    private void styleHomeChips(TextView[] chips, int selected) {
        for (int i = 0; i < chips.length; i++) {
            if (i == selected) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.RECTANGLE);
                g.setCornerRadius(dp(16));
                g.setColor(getColor(R.color.spotify_green));
                chips[i].setBackground(g);
                chips[i].setTextColor(0xFF000000);
            } else {
                chips[i].setBackgroundResource(R.drawable.bg_sort_chip);
                chips[i].setTextColor(getColor(R.color.spotify_white));
            }
        }
    }

    private void setupLists() {
        boolean empty = allSongs.isEmpty();
        findViewById(R.id.homeEmpty).setVisibility(empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.libraryEmpty).setVisibility(empty ? View.VISIBLE : View.GONE);

        // Home: one ListView with a Spotify-style header (greeting + quick picks + section title)
        ListView homeList = findViewById(R.id.homeList);
        if (homeHeader == null) {
            homeHeader = LayoutInflater.from(this)
                    .inflate(R.layout.layout_home_header, homeList, false);
        }
        View header = homeHeader;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greeting = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
        ((TextView) header.findViewById(R.id.greetingText)).setText(greeting);
        GridView quickGrid = header.findViewById(R.id.quickGrid);
        List<Song> quick = allSongs.subList(0, Math.min(6, allSongs.size()));
        quickAdapter = new QuickAdapter(this, new ArrayList<>(quick));
        quickGrid.setAdapter(quickAdapter);
        quickGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        quickGrid.setOnItemClickListener((parent, view, position, id) ->
                Player.get().play(this, quickAdapter.getSongs(), position));

        // Home filter chips (Spotify pattern): All / Liked / A-Z
        TextView chipAll = header.findViewById(R.id.chipAll);
        TextView chipLiked = header.findViewById(R.id.chipLiked);
        TextView chipAz = header.findViewById(R.id.chipAz);
        final TextView[] homeChips = {chipAll, chipLiked, chipAz};

        // "Jump back in" shelf: horizontal big cards, Spotify home pattern
        shelfRow = header.findViewById(R.id.shelfRow);
        View shelfTitle = header.findViewById(R.id.shelfTitle);
        View shelfScroll = header.findViewById(R.id.shelfScroll);
        forgotTitle = header.findViewById(R.id.forgotTitle);
        forgotScroll = header.findViewById(R.id.forgotScroll);
        forgotRow = header.findViewById(R.id.forgotRow);
        if (empty) {
            shelfTitle.setVisibility(View.GONE);
            shelfScroll.setVisibility(View.GONE);
        } else {
            buildShelf();
            buildForgotten();
        }
        if (!homeHeaderAdded) {
            homeList.addHeaderView(header);
            homeHeaderAdded = true;
        }
        homeAdapter = new SongAdapter(this, new ArrayList<>(allSongs));
        homeList.setAdapter(homeAdapter);
        styleHomeChips(homeChips, 0);
        chipAll.setOnClickListener(v -> {
            styleHomeChips(homeChips, 0);
            homeAdapter.setSongs(new ArrayList<>(allSongs));
        });
        chipLiked.setOnClickListener(v -> {
            styleHomeChips(homeChips, 1);
            homeAdapter.setSongs(likedSongs());
        });
        chipAz.setOnClickListener(v -> {
            styleHomeChips(homeChips, 2);
            List<Song> az = new ArrayList<>(allSongs);
            Collections.sort(az, (a, b2) -> a.title.compareToIgnoreCase(b2.title));
            homeAdapter.setSongs(az);
        });
        homeList.setOnItemClickListener((parent, view, position, id) ->
                playFromAdapter(homeList, homeAdapter, position));

        ListView searchList = findViewById(R.id.searchList);
        SongAdapter.ratingSource = this::getRating;
        searchAdapter = new SearchAdapter();
        searchList.setAdapter(searchAdapter);
        searchAdapter.setQuery("");
        searchList.setOnItemClickListener((parent, view, position, id) -> {
            Object o = parent.getItemAtPosition(position);
            if (o instanceof Song) {
                Song s = (Song) o;
                List<Song> flat = searchAdapter.songItems();
                Player.get().play(this, flat, Math.max(0, flat.indexOf(s)));
            } else if (o instanceof SearchItem) {
                SearchItem it = (SearchItem) o;
                if (it.type == SearchItem.GROUP) openGroupPage(it.label, it.groupSongs, true);
            }
        });

        EditText searchInput = findViewById(R.id.searchInput);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                searchAdapter.setQuery(s.toString());
            }
        });

        ListView libraryList = findViewById(R.id.libraryList);
        libraryAdapter = new SongAdapter(this, sortSongs(allSongs, currentSortKey("songs")));
        libraryList.setAdapter(libraryAdapter);
        libraryList.setOnItemClickListener((parent, view, position, id) ->
                playFromAdapter(libraryList, libraryAdapter, position));
        updateLibraryHeader();
        updateGroupCounts();
        restoreSessionIfAny();
        handleShortcut(getIntent());
        if (pendingShortcut != null) {
            String t = pendingShortcut;
            pendingShortcut = null;
            routeShortcut(t);
        }

        findViewById(R.id.shufflePlayBtn).setOnClickListener(v -> {
            if (allSongs.isEmpty()) return;
            Player.get().setShuffle(true);
            int start = new java.util.Random().nextInt(allSongs.size());
            Player.get().play(this, allSongs, start);
        });

        TextView sortChip = findViewById(R.id.sortChip);
        sortChip.setText(sortLabel("songs", currentSortKey("songs")));
        sortChip.setOnClickListener(v -> {
            cycleSort("songs", SONG_SORTS);
            String key = currentSortKey("songs");
            sortChip.setText(sortLabel("songs", key));
            libraryAdapter.setSongs(sortSongs(allSongs, key));
        });

        homeList.setOnItemLongClickListener(songLongClick());
        searchList.setOnItemLongClickListener(songLongClick());
        libraryList.setOnItemLongClickListener(songLongClick());
        setupLibraryEntries();

        showTab(0);
    }

    private AdapterView.OnItemLongClickListener songLongClick() {
        return (parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            if (item instanceof Song) {
                showSongOptions((Song) item);
                return true;
            }
            return false;
        };
    }

    private void playFromAdapter(ListView list, SongAdapter adapter, int position) {
        Object item = list.getItemAtPosition(position);
        if (!(item instanceof Song)) return;
        Song song = (Song) item;
        List<Song> queue = new ArrayList<>();
        for (int i = 0; i < adapter.getCount(); i++) queue.add(adapter.getItem(i));
        int idx = queue.indexOf(song);
        if (idx >= 0) Player.get().play(this, queue, idx);
    }

    private void updateLibraryHeader() {
        int n = allSongs.size();
        ((TextView) findViewById(R.id.libraryCount)).setText(
                n == 1 ? "1 song" : n + " songs");
    }

    private View currentTabView;

    private void showTab(int tab) {
        View shown = tab == 0 ? homeView : tab == 1 ? searchView : libraryView;
        if (currentTabView == null) {
            homeView.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
            searchView.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
            libraryView.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        } else if (currentTabView != shown) {
            final View old = currentTabView;
            old.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(90)
                    .withEndAction(() -> {
                        old.setVisibility(View.GONE);
                        old.setAlpha(1f);
                        old.setScaleX(1f);
                        old.setScaleY(1f);
                    }).start();
            shown.setVisibility(View.VISIBLE);
            shown.setAlpha(0f);
            shown.setScaleX(0.96f);
            shown.setScaleY(0.96f);
            shown.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
                    .start();
        }
        currentTabView = shown;
        styleNav(navHomeIcon, navHomeLabel, tab == 0);
        styleNav(navSearchIcon, navSearchLabel, tab == 1);
        styleNav(navLibraryIcon, navLibraryLabel, tab == 2);
        navHomeIcon.setImageResource(tab == 0 ? R.drawable.ic_home_filled : R.drawable.ic_home_outline);
        navSearchIcon.setImageResource(tab == 1 ? R.drawable.ic_search_filled : R.drawable.ic_search);
        navLibraryIcon.setImageResource(tab == 2 ? R.drawable.ic_library_filled : R.drawable.ic_library);
    }

    private void styleNav(ImageView icon, TextView label, boolean selected) {
        int color = getColor(selected ? R.color.spotify_white : R.color.spotify_gray);
        icon.setColorFilter(color);
        label.setTextColor(color);
    }

    // ----- Player.Listener -----

    @Override
    public void onSongChanged(Song song) {
        runOnUiThread(() -> {
            if (song == null) {
                // full stop (mini-player swipe-down): everything winds down
                miniPlayer.setVisibility(View.GONE);
                if (nowPlaying.getVisibility() == View.VISIBLE) {
                    nowPlaying.setVisibility(View.GONE);
                }
                return;
            }
            if (panelView.getVisibility() == View.VISIBLE && panelMode == 2) {
                panelSongs = Player.get().getQueue();
                if (panelAdapter != null) panelAdapter.setSongs(panelSongs);
                panelSub.setText("Playing " + (Player.get().getIndex() + 1)
                        + " of " + panelSongs.size());
            }
            miniPlayer.setVisibility(View.VISIBLE);
            miniTitle.setText(song.title);
            miniArtist.setText(song.artist);
            bindMiniArt(song);
            refreshNowPlaying();
            updateNextUp();
            recordPlay(song);
            resetAb();
            stopPreview(false);
            buildForgotten();
            if (carView != null && carView.getVisibility() == View.VISIBLE) refreshCar();
            if (lyricsView != null && lyricsView.getVisibility() == View.VISIBLE) {
                loadLyricsFor(song);
            }
            notifyAdapters();
        });
    }

    @Override
    public void onPlayStateChanged(boolean playing) {
        runOnUiThread(() -> {
            int icon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
            swapPlayIcon(miniPlay, icon);
            swapPlayIcon(npPlayIcon, icon);
            if (carPlayIcon != null) carPlayIcon.setImageResource(icon);
            notifyAdapters();
        });
    }

    private void swapPlayIcon(ImageView v, int icon) {
        v.animate().cancel();
        v.animate().scaleX(0.7f).scaleY(0.7f).alpha(0.3f).setDuration(90)
                .withEndAction(() -> {
                    v.setImageResource(icon);
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start();
                }).start();
    }

    private void notifyAdapters() {
        if (homeAdapter != null) homeAdapter.notifyDataSetChanged();
        if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
        if (libraryAdapter != null) libraryAdapter.notifyDataSetChanged();
    }

    private void bindMiniArt(Song song) {
        miniArt.setTag(song.albumId);
        // v5.5: an explicitly fetched online cover always wins over album art.
        Bitmap online = null;
        try {
            online = CoverCache.load(this, song.id);
        } catch (Exception ignored) {}
        if (online != null) {
            miniArt.clearColorFilter();
            miniArt.setImageBitmap(online);
            applyMiniTint(song.albumId);
            return;
        }
        Bitmap cached = MusicLibrary.cachedArt(song.albumId);
        if (cached != null) {
            miniArt.clearColorFilter();
            miniArt.setImageBitmap(cached);
            applyMiniTint(song.albumId);
        } else {
            miniArt.setImageResource(R.drawable.ic_note);
            miniArt.setColorFilter(getColor(R.color.spotify_gray));
            MusicLibrary.loadArtAsync(this, song.albumId, (albumId, bmp) ->
                    runOnUiThread(() -> {
                        Song cur = Player.get().current();
                        if (cur != null && cur.albumId == albumId && bmp != null) {
                            miniArt.clearColorFilter();
                            miniArt.setImageBitmap(bmp);
                            applyMiniTint(albumId);
                            if (nowPlaying.getVisibility() == View.VISIBLE) refreshNowPlaying();
                        }
                    }));
        }
    }

    private void applyMiniTint(long albumId) {
        int tint = MusicLibrary.tintFor(albumId);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(tint);
        miniPlayer.setBackground(bg);
    }

    private void refreshNowPlaying() {
        Song s = Player.get().current();
        if (s == null) return;
        npTitle.setText(s.title);
        npArtist.setText(s.artist);
        npAlbum.setText(s.album.isEmpty() ? s.artist : s.album);
        npDur.setText(Song.formatTime(s.durationMs));
        loadQuality(s);
        updateSleepIcon();
        updatePanelShuffleIcon();
        npSeek.setMax((int) s.durationMs);
        requestWaveform(s);
        npPlayIcon.setImageResource(Player.get().isPlaying()
                ? R.drawable.ic_pause : R.drawable.ic_play);
        tintToggle(npShuffle, Player.get().isShuffle());
        tintToggle(npRepeat, Player.get().getRepeat() != 0);
        npRepeatBadge.setVisibility(Player.get().getRepeat() == 2 ? View.VISIBLE : View.GONE);
        updateLikeIcon(s);
        updateNpStars(s);

        // v5.5: an explicitly fetched online cover always wins over album art.
        Bitmap online = null;
        try {
            online = CoverCache.load(this, s.id);
        } catch (Exception ignored) {}
        if (online != null) {
            npArt.clearColorFilter();
            npArt.setImageBitmap(online);
            fadeInArt(npArt);
            applyNpGradient(s.albumId);
        } else {
            Bitmap cached = MusicLibrary.cachedArt(s.albumId);
            if (cached != null) {
            npArt.clearColorFilter();
            npArt.setImageBitmap(cached);
            fadeInArt(npArt);
            applyNpGradient(s.albumId);
        } else {
            npArt.setImageResource(R.drawable.ic_note);
            npArt.setColorFilter(getColor(R.color.spotify_gray));
            applyNpGradient(-1);
            MusicLibrary.loadArtAsync(this, s.albumId, (albumId, bmp) ->
                    runOnUiThread(() -> {
                        Song cur = Player.get().current();
                        if (cur != null && cur.albumId == albumId && bmp != null
                                && nowPlaying.getVisibility() == View.VISIBLE) {
                            npArt.clearColorFilter();
                            npArt.setImageBitmap(bmp);
                            fadeInArt(npArt);
                            applyNpGradient(albumId);
                        }
                    }));
        }
    }
    }

    private void applyNpGradient(long albumId) {
        int raw = albumId >= 0 ? MusicLibrary.tintFor(albumId) : getColor(R.color.spotify_card);
        // Spotify keeps the Now Playing backdrop dark even for white covers,
        // so the white controls and play button stay readable.
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{darken(raw, 0.45f), darken(raw, 0.22f), themeColor(R.attr.amoledBg)});
        npBackground.setBackground(g);
    }

    private int darken(int color, float f) {
        int r = Math.round(android.graphics.Color.red(color) * f);
        int gg = Math.round(android.graphics.Color.green(color) * f);
        int b = Math.round(android.graphics.Color.blue(color) * f);
        return android.graphics.Color.rgb(r, gg, b);
    }

    private void updateProgress() {
        tickLyrics();
        Song s = Player.get().current();
        if (s == null) return;
        int pos = Player.get().position();
        int dur = Player.get().duration();
        if (dur <= 0) dur = (int) s.durationMs;
        if (dur > 0) {
            miniProgress.setProgress(pos * 1000 / dur);
        }
        if (nowPlaying.getVisibility() == View.VISIBLE && !userSeeking) {
            if (npSeek.getMax() != dur && dur > 0) npSeek.setMax(dur);
            npSeek.setProgress(pos);
            if (npWave != null && npWave.getVisibility() == View.VISIBLE && dur > 0) {
                npWave.setProgress((float) pos / dur);
            }
            npPos.setText(Song.formatTime(pos));
            npDur.setText(Song.formatTime(dur));
        }
        boolean sa = Player.get().isSleepActive();
        if (sa != lastSleepActive) updateSleepIcon();
    }

    // ----- Animations -----

    /** Slow, soft press feedback: gentle shrink + a faint halo that fades in and out slowly. */
    private void softPress(View touch) {
        softPress(touch, touch, touch);
    }

    private void softPress(View touch, View scaleTarget, View bgView) {
        Drawable bg = bgView != null ? bgView.getBackground() : null;
        final Drawable halo = bg != null ? bg.mutate() : null;
        if (halo != null) {
            bgView.setBackground(halo);
            halo.setAlpha(0);
        }
        touch.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scaleTarget.animate().cancel();
                    scaleTarget.animate().scaleX(0.88f).scaleY(0.88f).setDuration(250)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                    if (halo != null) fadeHalo(halo, 46, 250);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scaleTarget.animate().scaleX(1f).scaleY(1f).setDuration(500)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                    if (halo != null) fadeHalo(halo, 0, 500);
                    break;
            }
            return false;
        });
    }

    private void fadeHalo(Drawable halo, int to, int ms) {
        ValueAnimator va = ValueAnimator.ofInt(halo.getAlpha(), to);
        va.setDuration(ms);
        va.addUpdateListener(a -> halo.setAlpha((int) a.getAnimatedValue()));
        va.start();
    }

    private void showNowPlaying() {
        refreshNowPlaying();
        nowPlaying.setVisibility(View.VISIBLE);
        nowPlaying.setAlpha(0f);
        nowPlaying.setTranslationY(dp(700));
        nowPlaying.animate().alpha(1f).translationY(0f).setDuration(500)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
        npArt.setScaleX(0.94f);
        npArt.setScaleY(0.94f);
        npArt.animate().scaleX(1f).scaleY(1f).setDuration(500)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
    }

    private void hideNowPlaying() {
        nowPlaying.animate().alpha(0f).translationY(dp(700)).setDuration(400)
                .setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f))
                .withEndAction(() -> {
                    nowPlaying.setVisibility(View.GONE);
                    nowPlaying.setAlpha(1f);
                    nowPlaying.setTranslationY(0f);
                })
                .start();
    }

    private void fadeInArt(ImageView v) {
        v.setAlpha(0.25f);
        v.animate().alpha(1f).setDuration(420).start();
    }

    // ----- Playlists / panels / sound -----

    private static final String PL_PREFS = "playlists";

    private JSONArray loadPlaylists() {
        String raw = getSharedPreferences(PL_PREFS, MODE_PRIVATE).getString("data", "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void savePlaylists(JSONArray arr) {
        getSharedPreferences(PL_PREFS, MODE_PRIVATE).edit().putString("data", arr.toString()).apply();
    }

    private Set<String> likedIdSet() {
        return prefs().getStringSet("liked_ids", new HashSet<>());
    }

    private List<Song> likedSongs() {
        Set<String> ids = likedIdSet();
        List<Song> out = new ArrayList<>();
        for (Song s : allSongs) {
            if (ids.contains(String.valueOf(s.id))) out.add(s);
        }
        return out;
    }

    private List<Song> songsByIds(JSONArray ids) {
        Map<Long, Song> byId = new HashMap<>();
        for (Song s : allSongs) byId.put(s.id, s);
        List<Song> out = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) {
            Song s = byId.get(ids.optLong(i, -1));
            if (s != null) out.add(s);
        }
        return out;
    }

    private void setupLibraryEntries() {
        findViewById(R.id.likedRow).setOnClickListener(v -> openPanel(0, -1));
        findViewById(R.id.newPlaylistRow).setOnClickListener(v -> showPlaylistNameDialog(null, -1));
        findViewById(R.id.eqChip).setOnClickListener(v -> openEq());
        findViewById(R.id.artistsRow).setOnClickListener(v -> openPanel(3, -1));
        findViewById(R.id.albumsRow).setOnClickListener(v -> openPanel(4, -1));
        findViewById(R.id.genresRow).setOnClickListener(v -> openPanel(9, -1));
        findViewById(R.id.recentRow).setOnClickListener(v ->
                openGroupPage("Recently Added", recentlyAddedSongs(), false));
        findViewById(R.id.topRatedRow).setOnClickListener(v ->
                openGroupPage("Top Rated", topRatedSongs(), false));
        findViewById(R.id.topRow).setOnClickListener(v -> openPanel(6, -1));
        findViewById(R.id.mixRow).setOnClickListener(v -> openPanel(7, -1));
        findViewById(R.id.statsRow).setOnClickListener(v -> openPanel(8, -1));
        updateLikedCount();
        renderPlaylistRows();
    }

    private void updateLikedCount() {
        int n = likedSongs().size();
        ((TextView) findViewById(R.id.likedCount)).setText(n == 1 ? "1 song" : n + " songs");
    }

    private void renderPlaylistRows() {
        LinearLayout box = findViewById(R.id.playlistRows);
        if (box == null) return;
        box.removeAllViews();
        JSONArray arr = loadPlaylists();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject pl = arr.optJSONObject(i);
            if (pl == null) continue;
            String name = pl.optString("name", "Playlist");
            JSONArray ids = pl.optJSONArray("ids");
            if (ids == null) ids = new JSONArray();
            List<Song> songs = songsByIds(ids);
            View row = buildEntryRow(songs.isEmpty() ? null : songs.get(0), name,
                    ids.length() == 1 ? "1 song" : ids.length() + " songs");
            final int idx = i;
            row.setOnClickListener(v -> openPanel(1, idx));
            row.setOnLongClickListener(v -> {
                showPlaylistOptions(idx);
                return true;
            });
            box.addView(row);
        }
    }

    private View buildEntryRow(Song artSong, String title, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) dp(16), (int) dp(8), (int) dp(16), (int) dp(8));
        row.setForeground(getDrawable(R.drawable.row_selector));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout artBox = new FrameLayout(this);
        artBox.setBackground(getDrawable(R.drawable.bg_art_rounded_sm));
        artBox.setLayoutParams(new LinearLayout.LayoutParams((int) dp(56), (int) dp(56)));
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        artBox.addView(art, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (artSong != null) {
            SongAdapter.bindArt(art, artSong);
        } else {
            art.setImageResource(R.drawable.ic_note);
            art.setColorFilter(getColor(R.color.spotify_gray));
        }
        row.addView(artBox);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart((int) dp(12));
        texts.setLayoutParams(tlp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(R.color.spotify_white));
        t.setTextSize(16);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sb = new TextView(this);
        sb.setText(sub);
        sb.setTextColor(getColor(R.color.spotify_gray));
        sb.setTextSize(13);
        texts.addView(t);
        texts.addView(sb);
        row.addView(texts);
        return row;
    }

    private void showPlaylistOptions(int idx) {
        JSONArray arr = loadPlaylists();
        JSONObject pl = arr.optJSONObject(idx);
        String name = pl != null ? pl.optString("name", "Playlist") : "Playlist";
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"Rename", "Delete"}, (d, w) -> {
                    if (w == 0) {
                        showPlaylistNameDialog(name, idx);
                    } else {
                        JSONArray a2 = loadPlaylists();
                        a2.remove(idx);
                        savePlaylists(a2);
                        renderPlaylistRows();
                        if (panelView.getVisibility() == View.VISIBLE
                                && panelMode == 1 && panelPlaylistIndex == idx) {
                            closePanel();
                        }
                    }
                })
                .show();
    }

    private void showPlaylistNameDialog(String currentName, int editIndex) {
        EditText input = new EditText(this);
        input.setHint("Playlist name");
        input.setTextColor(getColor(R.color.spotify_white));
        input.setHintTextColor(getColor(R.color.spotify_gray));
        if (currentName != null) input.setText(currentName);
        int pad = (int) dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this)
                .setTitle(editIndex >= 0 ? "Rename playlist" : "New playlist")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    JSONArray arr = loadPlaylists();
                    try {
                        if (editIndex >= 0) {
                            arr.getJSONObject(editIndex).put("name", name);
                        } else {
                            JSONObject o = new JSONObject();
                            o.put("name", name);
                            o.put("ids", new JSONArray());
                            arr.put(o);
                        }
                    } catch (Exception ignored) {}
                    savePlaylists(arr);
                    renderPlaylistRows();
                    if (pendingAddSong != null && editIndex < 0) {
                        addSongToPlaylist(arr.length() - 1, pendingAddSong);
                        pendingAddSong = null;
                    }
                    if (panelView.getVisibility() == View.VISIBLE && panelMode == 1
                            && panelPlaylistIndex == editIndex) {
                        refreshPanel();
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> pendingAddSong = null)
                .show();
    }

    private void showAddToPlaylistDialog(Song song) {
        JSONArray arr = loadPlaylists();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject pl = arr.optJSONObject(i);
            labels.add(pl != null ? pl.optString("name", "Playlist") : "Playlist");
        }
        labels.add("New playlist");
        new AlertDialog.Builder(this)
                .setTitle(song.title)
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    if (w == labels.size() - 1) {
                        pendingAddSong = song;
                        showPlaylistNameDialog(null, -1);
                    } else {
                        addSongToPlaylist(w, song);
                    }
                })
                .show();
    }

    private void addSongToPlaylist(int idx, Song song) {
        JSONArray arr = loadPlaylists();
        JSONObject pl = arr.optJSONObject(idx);
        if (pl == null) return;
        try {
            JSONArray ids = pl.optJSONArray("ids");
            if (ids == null) {
                ids = new JSONArray();
                pl.put("ids", ids);
            }
            for (int i = 0; i < ids.length(); i++) {
                if (ids.optLong(i, -1) == song.id) return; // already in
            }
            ids.put(song.id);
            savePlaylists(arr);
            renderPlaylistRows();
            if (panelView.getVisibility() == View.VISIBLE && panelMode == 1
                    && panelPlaylistIndex == idx) {
                refreshPanel();
            }
        } catch (Exception ignored) {}
    }

    // ----- generic list panel (liked / playlist / queue) -----

    private void openPanel(int mode, int playlistIndex) {
        panelMode = mode;
        panelPlaylistIndex = playlistIndex;
        refreshPanel();
        panelView.setVisibility(View.VISIBLE);
        panelView.setAlpha(0f);
        panelView.setTranslationY(dp(700));
        panelView.animate().alpha(1f).translationY(0f).setDuration(450)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
    }

    private void closePanel() {
        panelView.animate().alpha(0f).translationY(dp(700)).setDuration(380)
                .setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f))
                .withEndAction(() -> {
                    panelView.setVisibility(View.GONE);
                    panelView.setAlpha(1f);
                    panelView.setTranslationY(0f);
                })
                .start();
    }

    private void refreshPanel() {
        String emptyText;
        boolean groupMode = panelMode == 3 || panelMode == 4 || panelMode == 9;
        List<String> labels = null;
        if (panelMode == 0) {
            panelTitle.setText("Liked Songs");
            panelSongs = likedSongs();
            emptyText = "Songs you like will live here.";
        } else if (panelMode == 1) {
            JSONArray arr = loadPlaylists();
            JSONObject pl = arr.optJSONObject(panelPlaylistIndex);
            panelTitle.setText(pl != null ? pl.optString("name", "Playlist") : "Playlist");
            JSONArray ids = pl != null ? pl.optJSONArray("ids") : null;
            panelSongs = ids != null ? songsByIds(ids) : new ArrayList<>();
            emptyText = "Long-press any song to add it here.";
        } else if (groupMode) {
            Map<String, List<Song>> map;
            String page = panelSortPage();
            if (panelMode == 3) {
                panelTitle.setText("Artists");
                groupMapCache = groupMap(true);
                map = groupMapCache;
            } else if (panelMode == 4) {
                panelTitle.setText("Albums");
                groupMapCache = groupMap(false);
                map = groupMapCache;
            } else {
                panelTitle.setText("Genres");
                genreMapCache = genreMap();
                map = genreMapCache;
            }
            groupNames = sortGroupNames(new ArrayList<>(map.keySet()), map, currentSortKey(page));
            labels = new ArrayList<>();
            for (String n : groupNames) {
                int c = map.get(n).size();
                labels.add(n + "   ·   " + (c == 1 ? "1 song" : c + " songs"));
            }
            panelSongs = new ArrayList<>();
            panelSub.setText(groupNames.size() + (panelMode == 3 ? " artists"
                    : panelMode == 4 ? " albums" : " genres"));
            emptyText = panelMode == 3 ? "No artists found."
                    : panelMode == 4 ? "No albums found." : "No genres found.";
        } else if (panelMode == 5) {
            panelTitle.setText(groupTitle);
            List<Song> base = groupSongs != null ? groupSongs : new ArrayList<Song>();
            panelSongs = groupSortable ? sortSongs(base, currentSortKey("group"))
                    : new ArrayList<>(base);
            emptyText = "No songs.";
        } else if (panelMode == 6) {
            panelTitle.setText("Most Played");
            panelSongs = topPlayed(15);
            emptyText = "Play some songs and your top list builds itself here.";
        } else if (panelMode == 7) {
            panelTitle.setText("Daily Mix");
            panelSongs = dailyMix();
            emptyText = "Not enough listening yet. Play a few songs first.";
        } else if (panelMode == 8) {
            panelTitle.setText("Your Stats");
            panelSongs = new ArrayList<>();
            panelSub.setText("Your listening in numbers");
            emptyText = "";
        } else {
            panelTitle.setText("Queue");
            panelSongs = Player.get().getQueue();
            emptyText = "Nothing queued.";
        }
        if (panelMode == 2) {
            panelSub.setText(panelSongs.isEmpty() ? "" : "Playing "
                    + (Player.get().getIndex() + 1) + " of " + panelSongs.size());
        } else if (!groupMode && panelMode != 8) {
            panelSub.setText(panelSongs.size() == 1 ? "1 song" : panelSongs.size() + " songs");
        }
        boolean isEmpty = panelMode == 8 ? false
                : groupMode ? (labels == null || labels.isEmpty()) : panelSongs.isEmpty();
        findViewById(R.id.panelEmpty).setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.panelEmpty)).setText(emptyText);
        if (groupMode) {
            if (groupAdapter == null) {
                groupAdapter = new ArrayAdapter<>(this, R.layout.item_group_row,
                        R.id.groupTitle, new ArrayList<>());
            }
            groupAdapter.clear();
            if (labels != null) groupAdapter.addAll(labels);
            groupAdapter.notifyDataSetChanged();
            if (panelList.getAdapter() != groupAdapter) panelList.setAdapter(groupAdapter);
        } else if (panelMode == 8) {
            panelList.setAdapter(buildStatsAdapter());
        } else if (panelAdapter == null) {
            panelAdapter = new SongAdapter(this, new ArrayList<>(panelSongs));
            panelList.setAdapter(panelAdapter);
        } else {
            if (panelList.getAdapter() != panelAdapter) panelList.setAdapter(panelAdapter);
            panelAdapter.setSongs(panelSongs);
        }
        // panelPlayBtn is a circular FrameLayout with an icon (not a Button):
        // mode 8 (stats) shows the share icon, everything else the play icon.
        ImageView panelPlayIcon = findViewById(R.id.panelPlayIcon);
        if (panelPlayIcon != null) {
            panelPlayIcon.setImageResource(
                    panelMode == 8 ? R.drawable.ic_share : R.drawable.ic_play);
        }
        View panelSaveBtn = findViewById(R.id.panelSaveBtn);
        if (panelSaveBtn != null) {
            panelSaveBtn.setVisibility(panelMode == 2 ? View.VISIBLE : View.GONE);
        }
        TextView panelSortChip = findViewById(R.id.panelSortChip);
        if (panelSortChip != null) {
            boolean showSort = groupMode || (panelMode == 5 && groupSortable);
            panelSortChip.setVisibility(showSort ? View.VISIBLE : View.GONE);
            if (showSort) {
                String page = panelSortPage();
                panelSortChip.setText(sortLabel(page, currentSortKey(page)));
            }
        }
        updatePanelShuffleIcon();
    }

    private void updatePanelShuffleIcon() {
        if (panelShuffleIcon != null) {
            panelShuffleIcon.setColorFilter(getColor(Player.get().isShuffle()
                    ? R.color.spotify_green : R.color.spotify_gray));
        }
    }

    private void panelPlay() {
        if (panelMode == 8) {
            shareStatsCard();
            return;
        }
        if (panelMode == 2) {
            if (Player.get().current() != null) Player.get().toggle(this);
        } else if (!panelSongs.isEmpty()) {
            Player.get().play(this, panelSongs, 0);
        }
    }

    private void panelShuffle() {
        Player.get().setShuffle(true);
        updatePanelShuffleIcon();
        if (panelMode != 2 && !panelSongs.isEmpty()) {
            int start = new java.util.Random().nextInt(panelSongs.size());
            Player.get().play(this, panelSongs, start);
        }
    }

    private void panelItemClick(int position) {
        if (panelMode == 3 || panelMode == 4 || panelMode == 9) {
            if (groupNames == null || position < 0 || position >= groupNames.size()) return;
            String name = groupNames.get(position);
            Map<String, List<Song>> map = panelMode == 9 ? genreMapCache : groupMapCache;
            groupSongs = map != null ? map.get(name) : null;
            groupTitle = name;
            groupSortable = true;
            panelMode = 5;
            refreshPanel();
            return;
        }
        if (position < 0 || position >= panelSongs.size()) return;
        if (panelMode == 2) {
            Player.get().playAt(this, position);
        } else {
            Player.get().play(this, panelSongs, position);
        }
    }

    private boolean panelItemLongClick(int position) {
        if (position < 0 || position >= panelSongs.size()) return false;
        Song song = panelSongs.get(position);
        if (panelMode == 0) {
            Set<String> set = new HashSet<>(likedIdSet());
            set.remove(String.valueOf(song.id));
            prefs().edit().putStringSet("liked_ids", set).apply();
            updateLikedCount();
            Song cur = Player.get().current();
            if (cur != null && cur.id == song.id) updateLikeIcon(cur);
            refreshPanel();
        } else if (panelMode == 1) {
            JSONArray arr = loadPlaylists();
            JSONObject pl = arr.optJSONObject(panelPlaylistIndex);
            if (pl != null) {
                JSONArray ids = pl.optJSONArray("ids");
                if (ids != null) {
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < ids.length(); i++) {
                        if (ids.optLong(i, -1) != song.id) kept.put(ids.optLong(i));
                    }
                    try {
                        pl.put("ids", kept);
                    } catch (Exception ignored) {}
                    savePlaylists(arr);
                }
            }
            renderPlaylistRows();
            refreshPanel();
        } else if (panelMode == 2) {
            if (dragMoved) {
                dragMoved = false;
                return true;
            }
            cancelDrag();
            if (position != Player.get().getIndex()) {
                Player.get().removeAt(this, position);
                refreshPanel();
            }
        } else {
            showAddToPlaylistDialog(song);
        }
        return true;
    }

    // ----- sleep timer -----

    private void showSleepDialog() {
        String[] labels = {"Off", "5 minutes", "10 minutes", "15 minutes",
                "30 minutes", "45 minutes", "60 minutes", "End of track",
                "Sleep clock: " + (ClockAlarms.sleepOn(this)
                        ? fmtClock(ClockAlarms.sleepHour(this), ClockAlarms.sleepMinute(this)) + " (daily)" : "Off"),
                "Wake alarm: " + (ClockAlarms.wakeOn(this)
                        ? fmtClock(ClockAlarms.wakeHour(this), ClockAlarms.wakeMinute(this)) + " (daily)" : "Off")};
        int[] mins = {0, 5, 10, 15, 30, 45, 60, 0};
        int checked = 0;
        if (Player.get().isSleepAtTrackEnd()) {
            checked = 7;
        } else if (Player.get().sleepRemainingMs() > 0) {
            long remMin = Math.round(Player.get().sleepRemainingMs() / 60000.0);
            int best = 0;
            long bestDiff = Long.MAX_VALUE;
            for (int i = 1; i <= 6; i++) {
                long diff = Math.abs(mins[i] - remMin);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = i;
                }
            }
            checked = best;
        }
        new AlertDialog.Builder(this)
                .setTitle("Sleep timer")
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    if (w == 8) { d.dismiss(); showSleepClockDialog(); return; }
                    if (w == 9) { d.dismiss(); showWakeAlarmDialog(); return; }
                    if (w == 7) Player.get().setSleepAtTrackEnd();
                    else if (w == 0) Player.get().cancelSleep();
                    else Player.get().setSleepInMinutes(mins[w]);
                    updateSleepIcon();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ----- v5.6.6: fixed sleep clock + wake alarm -----

    private static String fmtClock(int h, int m) {
        return String.format(java.util.Locale.US, "%02d:%02d", h, m);
    }

    private void showSleepClockDialog() {
        if (!ClockAlarms.sleepOn(this)) { pickSleepClockTime(); return; }
        new AlertDialog.Builder(this)
                .setTitle("Sleep clock — " + fmtClock(ClockAlarms.sleepHour(this),
                        ClockAlarms.sleepMinute(this)) + " daily")
                .setItems(new String[]{"Change time", "Turn off"}, (d, w) -> {
                    if (w == 0) pickSleepClockTime();
                    else {
                        ClockAlarms.setSleep(this, false,
                                ClockAlarms.sleepHour(this), ClockAlarms.sleepMinute(this));
                        Toast.makeText(this, "Sleep clock off", Toast.LENGTH_SHORT).show();
                        updateSleepIcon();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickSleepClockTime() {
        new android.app.TimePickerDialog(this, (v, h, m) -> {
            ClockAlarms.setSleep(this, true, h, m);
            Toast.makeText(this, "Sleep clock set for " + fmtClock(h, m),
                    Toast.LENGTH_SHORT).show();
            updateSleepIcon();
            if (!ClockAlarms.canExact(this)) {
                Toast.makeText(this,
                        "Exact-alarm permission is off — the sleep clock may stop late. Allow it on the next screen.",
                        Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            }
        }, ClockAlarms.sleepHour(this), ClockAlarms.sleepMinute(this), true).show();
    }

    private void showWakeAlarmDialog() {
        if (!ClockAlarms.wakeOn(this)) { pickWakeAlarmTime(); return; }
        new AlertDialog.Builder(this)
                .setTitle("Wake alarm — " + fmtClock(ClockAlarms.wakeHour(this),
                        ClockAlarms.wakeMinute(this)) + " daily")
                .setItems(new String[]{"Change time", "Turn off"}, (d, w) -> {
                    if (w == 0) pickWakeAlarmTime();
                    else {
                        ClockAlarms.setWake(this, false,
                                ClockAlarms.wakeHour(this), ClockAlarms.wakeMinute(this));
                        Toast.makeText(this, "Wake alarm off", Toast.LENGTH_SHORT).show();
                        updateSleepIcon();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickWakeAlarmTime() {
        new android.app.TimePickerDialog(this, (v, h, m) -> {
            ClockAlarms.setWake(this, true, h, m);
            Toast.makeText(this, "Wake alarm set for " + fmtClock(h, m),
                    Toast.LENGTH_SHORT).show();
            updateSleepIcon();
        }, ClockAlarms.wakeHour(this), ClockAlarms.wakeMinute(this), true).show();
    }

    private void updateSleepIcon() {
        lastSleepActive = Player.get().isSleepActive() || ClockAlarms.anyOn(this);
        if (npSleepBtn != null) {
            npSleepBtn.setColorFilter(getColor(lastSleepActive
                    ? R.color.spotify_green : R.color.spotify_gray));
        }
    }

    // ----- per-song quality label -----

    private void loadQuality(Song s) {
        String cached = qualityCache.get(s.id);
        if (cached != null) {
            npQuality.setText(cached);
            return;
        }
        npQuality.setText("");
        new Thread(() -> {
            String label = "";
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(this, s.uri);
                String br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                String mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                String fmt = formatName(mime);
                if (br != null) {
                    int kbps = Math.round(Integer.parseInt(br) / 1000f);
                    label = fmt.isEmpty() ? kbps + " kbps" : fmt + " \u00b7 " + kbps + " kbps";
                } else {
                    label = fmt;
                }
            } catch (Exception ignored) {
            } finally {
                try { r.release(); } catch (Exception ignored) {}
            }
            String out = label;
            qualityCache.put(s.id, out);
            runOnUiThread(() -> {
                Song cur = Player.get().current();
                if (cur != null && cur.id == s.id) npQuality.setText(out);
            });
        }).start();
    }

    private String formatName(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case "audio/mpeg": return "MP3";
            case "audio/mp4": return "M4A";
            case "audio/aac": return "AAC";
            case "audio/flac": return "FLAC";
            case "audio/ogg": return "OGG";
            case "audio/opus": return "OPUS";
            case "audio/x-wav":
            case "audio/wav": return "WAV";
            default:
                return mime.startsWith("audio/")
                        ? mime.substring(6).toUpperCase() : mime;
        }
    }

    // ----- sound panel (EQ / loudness / bass / normalize / crossfade) -----

    private SharedPreferences fx() {
        return getSharedPreferences(Player.FX, MODE_PRIVATE);
    }

    private void openEq() {
        if (!eqBuilt) buildEq();
        syncEqUi();
        eqView.setVisibility(View.VISIBLE);
        eqView.setAlpha(0f);
        eqView.setTranslationY(dp(700));
        eqView.animate().alpha(1f).translationY(0f).setDuration(450)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
    }

    private void closeEq() {
        eqView.animate().alpha(0f).translationY(dp(700)).setDuration(380)
                .setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f))
                .withEndAction(() -> {
                    eqView.setVisibility(View.GONE);
                    eqView.setAlpha(1f);
                    eqView.setTranslationY(0f);
                    if (probeEq != null) {
                        try { probeEq.release(); } catch (Exception ignored) {}
                        probeEq = null;
                    }
                })
                .start();
    }

    private TextView makeChip(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setPadding((int) dp(14), (int) dp(6), (int) dp(14), (int) dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) dp(8));
        t.setLayoutParams(lp);
        styleChip(t, false);
        return t;
    }

    private void styleChip(TextView t, boolean selected) {
        t.setBackground(getDrawable(selected ? R.drawable.bg_green_btn : R.drawable.bg_sort_chip));
        t.setTextColor(getColor(selected ? R.color.spotify_black : R.color.spotify_white));
        t.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void buildEq() {
        eqBuilt = true;
        try {
            probeEq = new Equalizer(0, 0);
        } catch (Exception e) {
            probeEq = null;
        }
        Switch en = findViewById(R.id.eqEnable);
        en.setOnCheckedChangeListener((b, checked) -> {
            fx().edit().putBoolean(Player.K_EQ_ON, checked).apply();
            Player.get().refreshAudioFx();
        });

        Switch proEq = findViewById(R.id.eqProEqSwitch);
        proEq.setOnCheckedChangeListener((b, checked) -> {
            fx().edit().putBoolean(Player.K_PRO_EQ, checked).apply();
            buildBandRows(checked);
            Player.get().refreshAudioFx();
            syncEqUi();
        });

        Switch headroom = findViewById(R.id.eqHeadroomSwitch);
        headroom.setOnCheckedChangeListener((b, checked) -> {
            fx().edit().putBoolean(Player.K_HEADROOM, checked).apply();
            Player.get().refreshAudioFx();
        });

        SeekBar balance = findViewById(R.id.eqBalanceSeek);
        TextView balanceVal = findViewById(R.id.eqBalanceVal);
        balance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                balanceVal.setText(balanceLabel(progress - 50));
                if (fromUser) {
                    fx().edit().putInt(Player.K_BALANCE, progress - 50).apply();
                    Player.get().applyBalanceNow();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        balanceVal.setOnClickListener(v -> balance.setProgress(50)); // reset to center

        buildBandRows(fx().getBoolean(Player.K_PRO_EQ, true));

        View autoEqRow = findViewById(R.id.eqAutoEqRow);
        if (autoEqRow != null) {
            autoEqRow.setOnClickListener(v -> showAutoEqPicker());
        }
        updateAutoEqRow();

        SeekBar loud = findViewById(R.id.eqLoudSeek);
        TextView loudVal = findViewById(R.id.eqLoudVal);
        loud.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                loudVal.setText("+" + (progress / 10.0) + " dB");
                if (fromUser) {
                    fx().edit().putInt(Player.K_LOUD, progress * 10).apply();
                    Player.get().refreshAudioFx();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        SeekBar bass = findViewById(R.id.eqBassSeek);
        TextView bassVal = findViewById(R.id.eqBassVal);
        bass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                bassVal.setText(progress + "%");
                if (fromUser) {
                    fx().edit().putInt(Player.K_BASS, progress * 10).apply();
                    Player.get().refreshAudioFx();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        Switch norm = findViewById(R.id.eqNormSwitch);
        norm.setOnCheckedChangeListener((b, checked) -> {
            fx().edit().putBoolean(Player.K_NORM, checked).apply();
            Player.get().applyNormalizationNow();
        });

        Switch smart = findViewById(R.id.eqSmartSwitch);
        smart.setOnCheckedChangeListener((b, checked) ->
                fx().edit().putBoolean(Player.K_SMART, checked).apply());

        Switch silence = findViewById(R.id.eqSilenceSwitch);
        silence.setOnCheckedChangeListener((b, checked) -> {
            fx().edit().putBoolean(Player.K_SILENCE, checked).apply();
            Player.get().applySkipSilenceNow();
        });

        Switch shake = findViewById(R.id.eqShakeSwitch);
        shake.setOnCheckedChangeListener((b, checked) -> {
            fx().edit().putBoolean(Player.K_SHAKE, checked).apply();
            updateShake();
        });

        LinearLayout xfBox = findViewById(R.id.eqXfadeChips);
        String[] labs = {"Off", "2s", "4s", "6s"};
        for (int k = 0; k < labs.length; k++) {
            final int val = xfadeValues[k];
            TextView chip = makeChip(labs[k]);
            chip.setOnClickListener(v -> {
                fx().edit().putInt(Player.K_XFADE, val).apply();
                markXfadeChips();
            });
            xfBox.addView(chip);
            eqXfadeChips.add(chip);
        }

        // ----- Poweramp-engine rules: ReplayGain / gapless / crossfade mode -----
        LinearLayout eqRoot = (LinearLayout) xfBox.getParent();

        eqRoot.addView(sectionTitle("Crossfade mode"));
        LinearLayout xfModeBox = chipRow();
        String[] xfModes = {"All", "Shuffle", "Manual"};
        for (int k = 0; k < xfModes.length; k++) {
            final int m = k;
            TextView chip = makeChip(xfModes[k]);
            chip.setOnClickListener(v -> {
                fx().edit().putInt(Player.K_XF_MODE, m).apply();
                markXfadeModeChips();
            });
            xfModeBox.addView(chip);
            eqXfModeChips.add(chip);
        }
        eqRoot.addView(xfModeBox);

        LinearLayout gapRow = new LinearLayout(this);
        gapRow.setOrientation(LinearLayout.HORIZONTAL);
        gapRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gapLp.topMargin = (int) dp(20);
        gapRow.setLayoutParams(gapLp);
        LinearLayout gapText = new LinearLayout(this);
        gapText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gapTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        gapText.setLayoutParams(gapTextLp);
        TextView gapTitle = new TextView(this);
        gapTitle.setText("Gapless");
        gapTitle.setTextColor(getColor(R.color.spotify_white));
        gapTitle.setTextSize(16);
        TextView gapSub = new TextView(this);
        gapSub.setText("Preload the next track for zero-gap playback");
        gapSub.setTextColor(getColor(R.color.spotify_gray));
        gapSub.setTextSize(12);
        gapText.addView(gapTitle);
        gapText.addView(gapSub);
        eqGaplessSwitch = new Switch(this);
        eqGaplessSwitch.setChecked(fx().getBoolean(Player.K_GAPLESS, true));
        eqGaplessSwitch.setOnCheckedChangeListener((b, checked) ->
                Player.get().setGaplessEnabled(checked));
        gapRow.addView(gapText);
        gapRow.addView(eqGaplessSwitch);
        eqRoot.addView(gapRow);

        eqRoot.addView(sectionTitle("ReplayGain"));
        LinearLayout rgModeBox = chipRow();
        String[] rgModes = {"Off", "Apply", "Apply+Peak"};
        for (int k = 0; k < rgModes.length; k++) {
            final int m = k;
            TextView chip = makeChip(rgModes[k]);
            chip.setOnClickListener(v -> {
                fx().edit().putInt(Player.K_RG_MODE, m).apply();
                markRgModeChips();
                Player.get().applyRgNow();
            });
            rgModeBox.addView(chip);
            eqRgModeChips.add(chip);
        }
        eqRoot.addView(rgModeBox);

        eqRoot.addView(sectionTitle("RG source"));
        LinearLayout rgSrcBox = chipRow();
        String[] rgSrcs = {"Track", "Album"};
        for (int k = 0; k < rgSrcs.length; k++) {
            final int m = k;
            TextView chip = makeChip(rgSrcs[k]);
            chip.setOnClickListener(v -> {
                fx().edit().putInt(Player.K_RG_SRC, m).apply();
                markRgSrcChips();
                Player.get().applyRgNow();
            });
            rgSrcBox.addView(chip);
            eqRgSrcChips.add(chip);
        }
        eqRoot.addView(rgSrcBox);

        eqRoot.addView(sectionTitle("RG preamp"));
        LinearLayout rgPreBox = chipRow();
        rgPreChip = makeChip(dbIntLabel(fx().getInt(Player.K_RG_PRE, 0)));
        rgPreChip.setOnClickListener(v -> {
            int cur = fx().getInt(Player.K_RG_PRE, 0);
            int nv = cur >= 6 ? -6 : cur + 1;
            fx().edit().putInt(Player.K_RG_PRE, nv).apply();
            rgPreChip.setText(dbIntLabel(nv));
            Player.get().applyRgNow();
        });
        rgPreBox.addView(rgPreChip);
        eqRoot.addView(rgPreBox);

        eqRoot.addView(sectionTitle("Preamp without RG tags"));
        LinearLayout rgDefBox = chipRow();
        rgDefChip = makeChip(dbIntLabel(fx().getInt(Player.K_RG_DEF, 0)));
        rgDefChip.setOnClickListener(v -> {
            int cur = fx().getInt(Player.K_RG_DEF, 0);
            int nv = cur >= 6 ? -6 : cur + 1;
            fx().edit().putInt(Player.K_RG_DEF, nv).apply();
            rgDefChip.setText(dbIntLabel(nv));
            Player.get().applyRgNow();
        });
        rgDefBox.addView(rgDefChip);
        eqRoot.addView(rgDefBox);

        // ----- v5.4 listening pack -----
        eqRoot.addView(sectionTitle("ReplayGain scanner"));
        LinearLayout rgScanRow = new LinearLayout(this);
        rgScanRow.setOrientation(LinearLayout.HORIZONTAL);
        rgScanRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scanLp.topMargin = (int) dp(8);
        rgScanRow.setLayoutParams(scanLp);
        LinearLayout scanText = new LinearLayout(this);
        scanText.setOrientation(LinearLayout.VERTICAL);
        scanText.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView scanTitle = new TextView(this);
        scanTitle.setText("Measure songs without RG tags");
        scanTitle.setTextColor(getColor(R.color.spotify_white));
        scanTitle.setTextSize(14);
        rgScanStatus = new TextView(this);
        rgScanStatus.setText("Not scanned");
        rgScanStatus.setTextColor(getColor(R.color.spotify_gray));
        rgScanStatus.setTextSize(12);
        scanText.addView(scanTitle);
        scanText.addView(rgScanStatus);
        rgScanChip = makeChip("Scan");
        rgScanChip.setOnClickListener(v -> startRgLibraryScan());
        rgScanRow.addView(scanText);
        rgScanRow.addView(rgScanChip);
        eqRoot.addView(rgScanRow);

        eqRoot.addView(sectionTitle("Sound output"));
        eqRouteLabel = new TextView(this);
        eqRouteLabel.setText("Output: Speaker");
        eqRouteLabel.setTextColor(getColor(R.color.spotify_gray));
        eqRouteLabel.setTextSize(14);
        LinearLayout.LayoutParams routeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        routeLp.topMargin = (int) dp(8);
        eqRouteLabel.setLayoutParams(routeLp);
        eqRoot.addView(eqRouteLabel);
        Player.get().setOnEqRouteChanged(() -> runOnUiThread(this::updateEqRouteLabel));

        eqRoot.addView(sectionTitle("Skip short tracks"));
        LinearLayout skipBox = chipRow();
        String[] skipLabs = {"Off", "10s", "20s", "30s"};
        for (int k = 0; k < skipLabs.length; k++) {
            final int val = SKIP_SHORT_VALUES[k];
            TextView chip = makeChip(skipLabs[k]);
            chip.setOnClickListener(v -> {
                fx().edit().putInt(Player.K_SKIP_SHORT, val).apply();
                markSkipChips();
            });
            skipBox.addView(chip);
            eqSkipChips.add(chip);
        }
        eqRoot.addView(skipBox);

        eqRoot.addView(sectionTitle("Headset buttons"));
        TextView hookSub = new TextView(this);
        hookSub.setText("Single press: play/pause");
        hookSub.setTextColor(getColor(R.color.spotify_gray));
        hookSub.setTextSize(12);
        eqRoot.addView(hookSub);
        eqRoot.addView(hookRowLabel("Double press", Player.K_BTN_DOUBLE, eqDblChips));
        eqRoot.addView(hookRowLabel("Triple press", Player.K_BTN_TRIPLE, eqTrpChips));

        eqRoot.addView(sectionTitle("Bookmarks"));
        addSwitchRow(eqRoot, "Resume long tracks",
                "Continue 15+ min tracks where you stopped", Player.K_BM_LONG, true);

        // ----- v5.5 polish pack: appearance -----
        eqRoot.addView(sectionTitle("Appearance"));
        LinearLayout amoledRow = new LinearLayout(this);
        amoledRow.setOrientation(LinearLayout.HORIZONTAL);
        amoledRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams amoledLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        amoledLp.topMargin = (int) dp(8);
        amoledRow.setLayoutParams(amoledLp);
        LinearLayout amoledText = new LinearLayout(this);
        amoledText.setOrientation(LinearLayout.VERTICAL);
        amoledText.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView amoledTitle = new TextView(this);
        amoledTitle.setText("AMOLED black");
        amoledTitle.setTextColor(getColor(R.color.spotify_white));
        amoledTitle.setTextSize(15);
        TextView amoledSub = new TextView(this);
        amoledSub.setText("Pure black backgrounds for OLED screens");
        amoledSub.setTextColor(getColor(R.color.spotify_gray));
        amoledSub.setTextSize(12);
        amoledText.addView(amoledTitle);
        amoledText.addView(amoledSub);
        Switch amoledSw = new Switch(this);
        amoledSw.setChecked(uiPrefs().getBoolean("amoled", false));
        amoledSw.setOnCheckedChangeListener((b, checked) -> {
            uiPrefs().edit().putBoolean("amoled", checked).apply();
            recreate();
        });
        amoledRow.addView(amoledText);
        amoledRow.addView(amoledSw);
        eqRoot.addView(amoledRow);

        findViewById(R.id.eqClose).setOnClickListener(v -> closeEq());
    }


    /** (Re)build the preset chips and band sliders for Pro (10-band) or legacy (5-band) mode. */
    private void buildBandRows(boolean pro) {
        eqBandSeeks.clear();
        eqBandVals.clear();
        eqPresetChips.clear();
        LinearLayout presetBox = findViewById(R.id.eqPresets);
        presetBox.removeAllViews();
        LinearLayout bandBox = findViewById(R.id.eqBands);
        bandBox.removeAllViews();
        if (pro) {
            eqBandMin = -1500;
            for (int i = 0; i < PRO_PRESET_NAMES.length; i++) {
                final int idx = i;
                TextView chip = makeChip(PRO_PRESET_NAMES[i]);
                chip.setOnClickListener(v -> applyPresetPro(idx));
                presetBox.addView(chip);
                eqPresetChips.add(chip);
            }
            TextView custom = makeChip("Custom");
            custom.setOnClickListener(v -> applyPresetPro(-1));
            presetBox.addView(custom);
            eqPresetChips.add(custom);
            for (int b = 0; b < 10; b++) {
                addBandRow(bandBox, b, Player.K_PBAND + b, hzLabel(PRO_ISO_HZ[b]), -1500, 1500);
            }
        } else if (probeEq != null) {
            short np = probeEq.getNumberOfPresets();
            for (short i = 0; i < np; i++) {
                final short idx = i;
                TextView chip = makeChip(probeEq.getPresetName(i));
                chip.setOnClickListener(v -> applyPreset(idx));
                presetBox.addView(chip);
                eqPresetChips.add(chip);
            }
            TextView custom = makeChip("Custom");
            custom.setOnClickListener(v -> applyPreset((short) -1));
            presetBox.addView(custom);
            eqPresetChips.add(custom);
            short[] range = probeEq.getBandLevelRange();
            eqBandMin = range[0];
            int max = range[1];
            for (short b = 0; b < probeEq.getNumberOfBands(); b++) {
                addBandRow(bandBox, b, Player.K_BAND + b,
                        freqLabel(probeEq.getCenterFreq(b)), eqBandMin, max);
            }
        }
    }

    private void addBandRow(LinearLayout bandBox, final int band, final String prefKey,
                            String freqText, int minMb, int maxMb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) dp(3), 0, (int) dp(3));
        TextView freq = new TextView(this);
        freq.setText(freqText);
        freq.setTextColor(getColor(R.color.spotify_gray));
        freq.setTextSize(12);
        freq.setLayoutParams(new LinearLayout.LayoutParams(
                (int) dp(54), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar seek = new SeekBar(this);
        seek.setMax((maxMb - minMb) / 50);
        seek.setProgressTintList(ColorStateList.valueOf(getColor(R.color.spotify_green)));
        seek.setThumbTintList(ColorStateList.valueOf(getColor(R.color.spotify_white)));
        seek.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(this);
        val.setGravity(Gravity.END);
        val.setTextColor(getColor(R.color.spotify_gray));
        val.setTextSize(12);
        val.setLayoutParams(new LinearLayout.LayoutParams(
                (int) dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        final int fMin = minMb;
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int lvl = fMin + progress * 50;
                val.setText(dbLabel(lvl));
                if (fromUser) {
                    fx().edit()
                            .putInt(prefKey, lvl)
                            .putInt(Player.K_EQ_PRESET, -1)
                            .putInt(Player.K_PEQ_PRESET, -1)
                            .apply();
                    markPresetChips(-1);
                    if (prefKey.startsWith(Player.K_PBAND)) {
                        // User tweaked a Pro band by hand: profile is now custom
                        fx().edit().putString(Player.K_AUTOEQ_NAME, "").apply();
                        updateAutoEqRow();
                    }
                    Player.get().refreshAudioFx();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        eqBandSeeks.add(seek);
        eqBandVals.add(val);
        row.addView(freq);
        row.addView(seek);
        row.addView(val);
        bandBox.addView(row);
    }

    private void applyPresetPro(int idx) {
        SharedPreferences.Editor e = fx().edit().putInt(Player.K_PEQ_PRESET, idx);
        if (idx >= 0 && idx < PRO_PRESETS.length) {
            for (int b = 0; b < 10; b++) {
                e.putInt(Player.K_PBAND + b, (int) (PRO_PRESETS[idx][b] * 100));
            }
        }
        e.apply();
        Player.get().refreshAudioFx();
        syncEqUi();
    }

    // ----- AutoEq headphone profiles -----

    private static class AutoEqProfile {
        String name;
        int preampMb;      // millibels (<= 0)
        int[] gainsMb = new int[10]; // millibels at PRO_ISO_HZ centers
    }

    private static java.util.List<AutoEqProfile> autoEqProfiles;

    private java.util.List<AutoEqProfile> loadAutoEqProfiles() {
        if (autoEqProfiles != null) return autoEqProfiles;
        java.util.List<AutoEqProfile> out = new java.util.ArrayList<>();
        try {
            java.io.InputStream in = getAssets().open("autoeq.json");
            java.util.Scanner sc = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
            String json = sc.hasNext() ? sc.next() : "[]";
            sc.close();
            in.close();
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AutoEqProfile p = new AutoEqProfile();
                p.name = o.optString("name", "");
                p.preampMb = (int) Math.round(o.optDouble("preamp", 0) * 100);
                JSONArray g = o.optJSONArray("gains");
                if (p.name.isEmpty() || g == null || g.length() != 10) continue;
                for (int b = 0; b < 10; b++) {
                    p.gainsMb[b] = (int) Math.round(g.optDouble(b, 0) * 100);
                }
                out.add(p);
            }
        } catch (Exception ignored) {}
        autoEqProfiles = out;
        return out;
    }

    private AutoEqProfile findAutoEqProfile(String name) {
        if (name == null) return null;
        for (AutoEqProfile p : loadAutoEqProfiles()) {
            if (p.name.equals(name)) return p;
        }
        return null;
    }

    /** Row label: null/absent -> None, empty -> Custom (user edited bands), else profile name. */
    private void updateAutoEqRow() {
        TextView v = findViewById(R.id.eqAutoEqVal);
        if (v == null) return;
        String name = fx().getString(Player.K_AUTOEQ_NAME, null);
        if (name == null) v.setText("None");
        else if (name.isEmpty()) v.setText("Custom");
        else v.setText(name);
    }

    private void showAutoEqPicker() {
        java.util.List<AutoEqProfile> profiles = loadAutoEqProfiles();
        if (profiles.isEmpty()) {
            android.widget.Toast.makeText(this, "No AutoEq profiles", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.List<String> names = new java.util.ArrayList<>();
        names.add("None");
        for (AutoEqProfile p : profiles) names.add(p.name);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(16);
        root.setPadding(pad, pad, pad, 0);
        EditText filter = new EditText(this);
        filter.setHint("Search headphones");
        filter.setHintTextColor(getColor(R.color.spotify_gray));
        filter.setTextColor(getColor(R.color.spotify_white));
        filter.setSingleLine(true);
        ListView list = new ListView(this);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, names) {
            @Override
            public android.view.View getView(int position, android.view.View cv,
                                             android.view.ViewGroup parent) {
                android.view.View v = super.getView(position, cv, parent);
                TextView t = v.findViewById(android.R.id.text1);
                t.setTextColor(getColor(R.color.spotify_white));
                t.setBackgroundColor(themeColor(R.attr.amoledBg));
                return v;
            }
        };
        list.setAdapter(adapter);
        filter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.getFilter().filter(s);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        root.addView(filter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(360));
        list.setLayoutParams(lp);
        root.addView(list);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Headphone (AutoEq)")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .create();
        list.setOnItemClickListener((parent, v, position, id) -> {
            String sel = adapter.getItem(position);
            applyAutoEqProfile(sel == null || sel.equals("None") ? null : sel);
            dlg.dismiss();
        });
        dlg.show();
    }

    private void applyAutoEqProfile(String name) {
        SharedPreferences.Editor e = fx().edit();
        if (name == null) {
            // None: drop profile, zero its preamp term; bands untouched
            e.remove(Player.K_AUTOEQ_NAME);
            e.putInt(Player.K_AUTOEQ_PRE, 0);
            e.apply();
            Player.get().refreshAudioFx();
            syncEqUi();
            updateAutoEqRow();
            return;
        }
        AutoEqProfile p = findAutoEqProfile(name);
        if (p == null) return;
        e.putBoolean(Player.K_EQ_ON, true);
        e.putBoolean(Player.K_PRO_EQ, true);
        e.putInt(Player.K_PEQ_PRESET, -1);
        for (int b = 0; b < 10; b++) {
            e.putInt(Player.K_PBAND + b, p.gainsMb[b]);
        }
        e.putInt(Player.K_AUTOEQ_PRE, p.preampMb);
        e.putString(Player.K_AUTOEQ_NAME, p.name);
        e.apply();
        buildBandRows(true); // ensure the 10-band Pro UI is showing
        Player.get().refreshAudioFx();
        syncEqUi();
        updateAutoEqRow();
    }

    private String hzLabel(int hz) {
        if (hz >= 1000) {
            double k = hz / 1000.0;
            return (k == Math.floor(k) ? String.valueOf((int) k) : String.valueOf(k)) + " kHz";
        }
        return hz + " Hz";
    }

    private String balanceLabel(int b) {
        if (b == 0) return "Center";
        return (b < 0 ? "L" + (-b) : "R" + b);
    }

    private void syncEqUi() {
        if (probeEq == null) {
            try {
                probeEq = new Equalizer(0, 0);
            } catch (Exception ignored) {}
        }
        ((Switch) findViewById(R.id.eqEnable)).setChecked(fx().getBoolean(Player.K_EQ_ON, false));
        ((Switch) findViewById(R.id.eqProEqSwitch)).setChecked(fx().getBoolean(Player.K_PRO_EQ, true));
        ((Switch) findViewById(R.id.eqHeadroomSwitch)).setChecked(fx().getBoolean(Player.K_HEADROOM, true));
        ((Switch) findViewById(R.id.eqNormSwitch)).setChecked(fx().getBoolean(Player.K_NORM, false));
        ((Switch) findViewById(R.id.eqSmartSwitch)).setChecked(fx().getBoolean(Player.K_SMART, false));
        ((Switch) findViewById(R.id.eqSilenceSwitch)).setChecked(fx().getBoolean(Player.K_SILENCE, false));
        ((Switch) findViewById(R.id.eqShakeSwitch)).setChecked(fx().getBoolean(Player.K_SHAKE, false));
        ((SeekBar) findViewById(R.id.eqLoudSeek)).setProgress(fx().getInt(Player.K_LOUD, 0) / 10);
        ((SeekBar) findViewById(R.id.eqBassSeek)).setProgress(fx().getInt(Player.K_BASS, 0) / 10);
        int bal = fx().getInt(Player.K_BALANCE, 0);
        ((SeekBar) findViewById(R.id.eqBalanceSeek)).setProgress(bal + 50);
        ((TextView) findViewById(R.id.eqBalanceVal)).setText(balanceLabel(bal));
        boolean pro = fx().getBoolean(Player.K_PRO_EQ, true);
        int preset = pro ? fx().getInt(Player.K_PEQ_PRESET, -1) : fx().getInt(Player.K_EQ_PRESET, -1);
        markPresetChips(preset);
        if (!eqBandSeeks.isEmpty()) {
            boolean fromPreset = !pro && preset >= 0 && probeEq != null
                    && preset < probeEq.getNumberOfPresets();
            if (fromPreset) {
                try {
                    probeEq.usePreset((short) preset);
                } catch (Exception ignored) {}
            }
            for (int b = 0; b < eqBandSeeks.size(); b++) {
                int lvl;
                if (fromPreset) {
                    lvl = probeEq.getBandLevel((short) b);
                } else {
                    lvl = fx().getInt((pro ? Player.K_PBAND : Player.K_BAND) + b, 0);
                }
                eqBandSeeks.get(b).setProgress((lvl - eqBandMin) / 50);
                eqBandVals.get(b).setText(dbLabel(lvl));
            }
        }
        markXfadeChips();
        if (eqGaplessSwitch != null) {
            eqGaplessSwitch.setChecked(fx().getBoolean(Player.K_GAPLESS, true));
        }
        markRgModeChips();
        markRgSrcChips();
        markXfadeModeChips();
        if (rgPreChip != null) rgPreChip.setText(dbIntLabel(fx().getInt(Player.K_RG_PRE, 0)));
        if (rgDefChip != null) rgDefChip.setText(dbIntLabel(fx().getInt(Player.K_RG_DEF, 0)));
        markSkipChips();
        markHookChips(Player.K_BTN_DOUBLE);
        markHookChips(Player.K_BTN_TRIPLE);
        updateEqRouteLabel();
        updateAutoEqRow();
    }

    private void applyPreset(short idx) {
        fx().edit().putInt(Player.K_EQ_PRESET, idx).apply();
        if (idx >= 0 && probeEq != null) {
            try {
                probeEq.usePreset(idx);
                SharedPreferences.Editor e = fx().edit();
                for (short b = 0; b < probeEq.getNumberOfBands(); b++) {
                    e.putInt(Player.K_BAND + b, (int) probeEq.getBandLevel(b));
                }
                e.apply();
            } catch (Exception ignored) {}
        }
        Player.get().refreshAudioFx();
        syncEqUi();
    }

    private void markPresetChips(int preset) {
        for (int i = 0; i < eqPresetChips.size(); i++) {
            boolean isCustom = i == eqPresetChips.size() - 1;
            boolean selected = isCustom ? preset < 0 : preset == i;
            styleChip(eqPresetChips.get(i), selected);
        }
    }

    private void markXfadeChips() {
        int cur = fx().getInt(Player.K_XFADE, 0);
        for (int i = 0; i < eqXfadeChips.size(); i++) {
            styleChip(eqXfadeChips.get(i), xfadeValues[i] == cur);
        }
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(R.color.spotify_white));
        t.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(20);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout chipRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    private String dbIntLabel(int v) {
        return (v >= 0 ? "+" : "") + v + " dB";
    }

    private void markRgModeChips() {
        int cur = fx().getInt(Player.K_RG_MODE, 0);
        for (int i = 0; i < eqRgModeChips.size(); i++) {
            styleChip(eqRgModeChips.get(i), i == cur);
        }
    }

    private void markRgSrcChips() {
        int cur = fx().getInt(Player.K_RG_SRC, 0);
        for (int i = 0; i < eqRgSrcChips.size(); i++) {
            styleChip(eqRgSrcChips.get(i), i == cur);
        }
    }

    private void markXfadeModeChips() {
        int cur = fx().getInt(Player.K_XF_MODE, 0);
        for (int i = 0; i < eqXfModeChips.size(); i++) {
            styleChip(eqXfModeChips.get(i), i == cur);
        }
    }

    // ----- v5.4 listening pack: UI helpers -----

    /** Generic title+subtitle row with a right-side Switch bound to a boolean pref. */
    private Switch addSwitchRow(LinearLayout root, String title, String sub,
                                final String prefKey, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(8);
        row.setLayoutParams(lp);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(R.color.spotify_white));
        t.setTextSize(15);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(getColor(R.color.spotify_gray));
        s.setTextSize(12);
        text.addView(t);
        text.addView(s);
        Switch sw = new Switch(this);
        sw.setChecked(fx().getBoolean(prefKey, def));
        sw.setOnCheckedChangeListener((b, checked) ->
                fx().edit().putBoolean(prefKey, checked).apply());
        row.addView(text);
        row.addView(sw);
        root.addView(row);
        return sw;
    }

    /** Label + chip row for one headset-button action mapping. */
    private LinearLayout hookRowLabel(String label, final String prefKey,
                                      final List<TextView> chips) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(getColor(R.color.spotify_gray));
        lab.setTextSize(13);
        LinearLayout.LayoutParams labLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labLp.topMargin = (int) dp(10);
        lab.setLayoutParams(labLp);
        wrap.addView(lab);
        LinearLayout box = chipRow();
        String[] acts = {"Next", "Previous", "Nothing"};
        for (int k = 0; k < acts.length; k++) {
            final int a = k;
            TextView chip = makeChip(acts[k]);
            chip.setOnClickListener(v -> {
                fx().edit().putInt(prefKey, a).apply();
                markHookChips(prefKey);
            });
            box.addView(chip);
            chips.add(chip);
        }
        wrap.addView(box);
        return wrap;
    }

    private void markSkipChips() {
        int cur = fx().getInt(Player.K_SKIP_SHORT, 0);
        for (int i = 0; i < eqSkipChips.size(); i++) {
            styleChip(eqSkipChips.get(i), SKIP_SHORT_VALUES[i] == cur);
        }
    }

    private void markHookChips(String prefKey) {
        int def = Player.K_BTN_DOUBLE.equals(prefKey) ? 0 : 1;
        int cur = fx().getInt(prefKey, def);
        List<TextView> chips = Player.K_BTN_DOUBLE.equals(prefKey) ? eqDblChips : eqTrpChips;
        for (int i = 0; i < chips.size(); i++) {
            styleChip(chips.get(i), i == cur);
        }
    }

    private void updateEqRouteLabel() {
        if (eqRouteLabel != null) {
            eqRouteLabel.setText("Output: " + Player.get().routeLabel(this));
        }
    }

    /** Starts the full-library ReplayGain scan from the Sound panel. */
    private void startRgLibraryScan() {
        if (RgScanner.isRunning()) return;
        rgScanChip.setText("Scanning…");
        rgScanStatus.setText("Scanning 0/…");
        Player.get().startLibraryRgScan(new RgScanner.Progress() {
            @Override public void onProgress(int done, int total) {
                rgScanStatus.setText("Scanning " + done + "/" + total + "…");
            }
            @Override public void onDone(int scanned, int failed) {
                rgScanStatus.setText("Done: " + scanned + " measured, " + failed + " failed");
                rgScanChip.setText("Scan");
                Player.get().applyRgNow();
                Toast.makeText(MainActivity.this,
                        "RG scan finished: " + scanned + " measured, " + failed + " failed",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String freqLabel(int milliHz) {
        int hz = milliHz / 1000;
        if (hz >= 1000) {
            double k = hz / 1000.0;
            return (k == Math.floor(k) ? String.valueOf((int) k) : String.valueOf(k)) + " kHz";
        }
        return hz + " Hz";
    }

    private String dbLabel(int millibels) {
        double db = millibels / 100.0;
        return (db > 0 ? "+" : "") + db + " dB";
    }

    // ----- Likes (stored on-device only) -----

    private SharedPreferences prefs() {
        return getSharedPreferences("likes", Context.MODE_PRIVATE);
    }

    // ----- v5.5 polish pack helpers -----

    /** UI prefs (theme etc.). */
    private SharedPreferences uiPrefs() {
        return getSharedPreferences("ui", Context.MODE_PRIVATE);
    }

    /** Resolves a theme attribute color (used for AMOLED-swappable backgrounds). */
    private int themeColor(int attr) {
        android.util.TypedValue v = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, v, true);
        return v.data;
    }

    private boolean isLiked(Song s) {
        return prefs().getStringSet("liked_ids", new HashSet<>()).contains(String.valueOf(s.id));
    }

    private void toggleLike() {
        Song s = Player.get().current();
        if (s == null) return;
        Set<String> set = new HashSet<>(prefs().getStringSet("liked_ids", new HashSet<>()));
        String key = String.valueOf(s.id);
        if (set.contains(key)) set.remove(key); else set.add(key);
        prefs().edit().putStringSet("liked_ids", set).apply();
        updateLikeIcon(s);
    }

    private void updateLikeIcon(Song s) {
        if (isLiked(s)) {
            npLike.setImageResource(R.drawable.ic_heart_filled);
            npLike.setColorFilter(getColor(R.color.spotify_green));
        } else {
            npLike.setImageResource(R.drawable.ic_heart_outline);
            npLike.setColorFilter(getColor(R.color.spotify_gray));
        }
    }

    // ----- stats: play history + counts -----

    private SharedPreferences stats() {
        return getSharedPreferences("stats", MODE_PRIVATE);
    }

    private void recordPlay(Song s) {
        if (suppressRecordOnce) {
            suppressRecordOnce = false;
            return;
        }
        try {
            JSONObject counts = new JSONObject(stats().getString("counts", "{}"));
            String key = String.valueOf(s.id);
            counts.put(key, counts.optInt(key, 0) + 1);
            JSONArray hist = new JSONArray(stats().getString("history", "[]"));
            JSONArray nh = new JSONArray();
            nh.put(s.id);
            for (int i = 0; i < hist.length() && nh.length() < 30; i++) {
                long id = hist.optLong(i, -1);
                if (id != s.id) nh.put(id);
            }
            JSONObject last = new JSONObject(stats().getString("last", "{}"));
            last.put(key, System.currentTimeMillis());
            stats().edit().putString("counts", counts.toString())
                    .putString("history", nh.toString())
                    .putString("last", last.toString()).apply();
        } catch (Exception ignored) {}
    }

    private Map<Long, Integer> playCounts() {
        Map<Long, Integer> out = new HashMap<>();
        try {
            JSONObject counts = new JSONObject(stats().getString("counts", "{}"));
            java.util.Iterator<String> it = counts.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(Long.parseLong(k), counts.optInt(k, 0));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private List<Song> historySongs() {
        try {
            JSONArray hist = new JSONArray(stats().getString("history", "[]"));
            return songsByIds(hist);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private View makeShelfCard(Song s, boolean first) {
        final int idx = allSongs.indexOf(s);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                (int) dp(120), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!first) cp.setMarginStart((int) dp(12));
        card.setLayoutParams(cp);
        ImageView art = new ImageView(this);
        art.setLayoutParams(new LinearLayout.LayoutParams((int) dp(120), (int) dp(120)));
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundResource(R.drawable.bg_art_rounded);
        art.setClipToOutline(true);
        SongAdapter.bindArt(art, s);
        card.addView(art);
        TextView t = new TextView(this);
        t.setText(s.title);
        t.setTextColor(getColor(R.color.spotify_white));
        t.setTextSize(13);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                (int) dp(120), ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = (int) dp(8);
        t.setLayoutParams(tp);
        card.addView(t);
        TextView a = new TextView(this);
        a.setText(s.artist);
        a.setTextColor(getColor(R.color.spotify_gray));
        a.setTextSize(12);
        a.setMaxLines(1);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        a.setLayoutParams(new LinearLayout.LayoutParams(
                (int) dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(a);
        card.setOnClickListener(v -> Player.get().play(this, allSongs, idx));
        return card;
    }

    private void buildShelf() {
        if (shelfRow == null) return;
        shelfRow.removeAllViews();
        List<Song> src = historySongs();
        if (src.isEmpty()) src = allSongs;
        int n = Math.min(10, src.size());
        for (int i = 0; i < n; i++) shelfRow.addView(makeShelfCard(src.get(i), i == 0));
    }

    private void buildForgotten() {
        if (forgotRow == null) return;
        forgotRow.removeAllViews();
        List<Song> out = forgottenSongs();
        for (int i = 0; i < out.size(); i++) {
            forgotRow.addView(makeShelfCard(out.get(i), i == 0));
        }
        int vis = out.isEmpty() ? View.GONE : View.VISIBLE;
        forgotTitle.setVisibility(vis);
        forgotScroll.setVisibility(vis);
    }

    /** Played 3+ times, not played in the last 30 days, not in recent history. */
    private List<Song> forgottenSongs() {
        List<Song> out = new ArrayList<>();
        try {
            JSONObject counts = countsJson();
            JSONObject last = new JSONObject(stats().getString("last", "{}"));
            Set<String> recent = new HashSet<>();
            for (Song s : historySongs()) recent.add(String.valueOf(s.id));
            long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
            Map<String, Song> byId = byIdMap();
            List<Song> cand = new ArrayList<>();
            java.util.Iterator<String> keys = counts.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (counts.optInt(k, 0) < 3) continue;
                if (recent.contains(k)) continue;
                long l = last.optLong(k, 0);
                if (l != 0 && l > cutoff) continue;
                Song s = byId.get(k);
                if (s != null) cand.add(s);
            }
            cand.sort((a, b) -> counts.optInt(String.valueOf(b.id), 0)
                    - counts.optInt(String.valueOf(a.id), 0));
            for (int i = 0; i < Math.min(10, cand.size()); i++) out.add(cand.get(i));
        } catch (Exception ignored) {}
        return out;
    }

    private JSONObject countsJson() {
        try {
            return new JSONObject(stats().getString("counts", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private Map<String, Song> byIdMap() {
        Map<String, Song> m = new HashMap<>();
        for (Song s : allSongs) m.put(String.valueOf(s.id), s);
        return m;
    }

    private List<Song> topPlayed(int cap) {
        Map<Long, Integer> counts = playCounts();
        List<Song> sorted = new ArrayList<>();
        for (Song s : allSongs) {
            if (counts.getOrDefault(s.id, 0) > 0) sorted.add(s);
        }
        Collections.sort(sorted, (a, b) -> Integer.compare(
                counts.getOrDefault(b.id, 0), counts.getOrDefault(a.id, 0)));
        return sorted.size() > cap ? new ArrayList<>(sorted.subList(0, cap)) : sorted;
    }

    private List<Song> dailyMix() {
        Map<Long, Song> pool = new java.util.LinkedHashMap<>();
        for (Song s : likedSongs()) pool.put(s.id, s);
        for (Song s : topPlayed(15)) pool.put(s.id, s);
        List<Song> rest = new ArrayList<>(allSongs);
        Collections.shuffle(rest);
        for (Song s : rest) {
            if (pool.size() >= 25) break;
            pool.put(s.id, s);
        }
        List<Song> out = new ArrayList<>(pool.values());
        Collections.shuffle(out);
        return out.size() > 25 ? new ArrayList<>(out.subList(0, 25)) : out;
    }

    private void startRadio(Song seed) {
        List<Song> out = new ArrayList<>();
        out.add(seed);
        List<Song> sameArtist = new ArrayList<>();
        List<Song> sameAlbum = new ArrayList<>();
        List<Song> liked = new ArrayList<>();
        List<Song> rest = new ArrayList<>();
        for (Song s : allSongs) {
            if (s.id == seed.id) continue;
            if (s.artist.equals(seed.artist)) sameArtist.add(s);
            else if (!seed.album.isEmpty() && seed.album.equals(s.album)) sameAlbum.add(s);
            else if (isLiked(s)) liked.add(s);
            else rest.add(s);
        }
        Collections.shuffle(sameArtist);
        Collections.shuffle(sameAlbum);
        Collections.shuffle(liked);
        Collections.shuffle(rest);
        List<List<Song>> buckets = new ArrayList<>();
        buckets.add(sameArtist);
        buckets.add(sameAlbum);
        buckets.add(liked);
        buckets.add(rest);
        for (List<Song> bucket : buckets) {
            for (Song s : bucket) {
                if (out.size() >= 30) break;
                out.add(s);
            }
        }
        Player.get().play(this, out, 0);
        showNowPlaying();
    }

    private void restoreSessionIfAny() {
        if (Player.get().current() != null) return;
        try {
            SharedPreferences sp = getSharedPreferences("session", MODE_PRIVATE);
            JSONArray ids = new JSONArray(sp.getString("ids", "[]"));
            if (ids.length() == 0) return;
            List<Song> songs = songsByIds(ids);
            if (songs.isEmpty()) return;
            suppressRecordOnce = true;
            boolean ok = Player.get().restore(this, songs,
                    sp.getInt("index", 0), sp.getInt("pos", 0));
            if (!ok) suppressRecordOnce = false;
        } catch (Exception ignored) {}
    }

    private Map<String, List<Song>> groupMap(boolean artists) {
        Map<String, List<Song>> map = new java.util.LinkedHashMap<>();
        for (Song s : allSongs) {
            String key = artists ? s.artist
                    : (s.album == null || s.album.isEmpty() ? "Unknown album" : s.album);
            List<Song> l = map.get(key);
            if (l == null) {
                l = new ArrayList<>();
                map.put(key, l);
            }
            l.add(s);
        }
        return map;
    }

    private void updateGroupCounts() {
        TextView ac = findViewById(R.id.artistsCount);
        if (ac != null) ac.setText(groupMap(true).size() + " artists");
        TextView alc = findViewById(R.id.albumsCount);
        if (alc != null) alc.setText(groupMap(false).size() + " albums");
        TextView gc = findViewById(R.id.genresCount);
        if (gc != null) gc.setText(genreMap().size() + " genres");
        TextView rc = findViewById(R.id.recentCount);
        if (rc != null) {
            int n = recentlyAddedSongs().size();
            rc.setText(n == 1 ? "1 song" : n + " songs");
        }
        TextView tc = findViewById(R.id.topRatedCount);
        if (tc != null) {
            int n = topRatedSongs().size();
            tc.setText(n == 0 ? "Rate some songs" : n == 1 ? "1 song" : n + " songs");
        }
    }

    // ----- share -----

    private void showSongOptions(Song song) {
        new AlertDialog.Builder(this)
                .setTitle(song.title)
                .setItems(new String[]{"Add to playlist", "Play next", "Add to queue",
                        "Start radio", "Preview 15s", "Set as ringtone", "Share",
                        "Rate", "Edit tags", "Find cover art online",
                        "Delete from device"}, (d, w) -> {
                    if (w == 0) {
                        showAddToPlaylistDialog(song);
                    } else if (w == 1) {
                        Player.get().playNext(this, song);
                        android.widget.Toast.makeText(this, "Plays next",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } else if (w == 2) {
                        Player.get().addToQueue(song);
                        android.widget.Toast.makeText(this, "Added to queue",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } else if (w == 3) {
                        startRadio(song);
                    } else if (w == 4) {
                        previewSong(song);
                    } else if (w == 5) {
                        setRingtone(song);
                    } else if (w == 6) {
                        shareSong(song);
                    } else if (w == 7) {
                        showRateDialog(song);
                    } else if (w == 8) {
                        openTagEditor(song);
                    } else if (w == 9) {
                        findCoverOnline(song);
                    } else {
                        confirmDelete(song);
                    }
                })
                .show();
    }

    private void shareSong(Song s) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("audio/*");
            i.putExtra(Intent.EXTRA_STREAM, s.uri);
            i.putExtra(Intent.EXTRA_TITLE, s.title);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share song"));
        } catch (Exception ignored) {}
    }

    // ----- tag editor -----

    private static final int REQ_COVER = 4712;
    private static final int REQ_WRITE_TAGS = 4713;

    private Song tagEditSong = null;
    private byte[] tagEditCover = null;
    private String tagEditCoverMime = null;
    private boolean tagEditRemoveCover = false;
    private ImageView tagCoverView = null;
    private Song pendingTagSong = null;
    private String pendingTagTmp = null;
    private TagEditor.Tags pendingTags = null;

    private void toast(String s) {
        runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, s,
                android.widget.Toast.LENGTH_SHORT).show());
    }

    private void openTagEditor(Song song) {
        toast("Reading tags…");
        new Thread(() -> {
            try (java.io.InputStream in = getContentResolver().openInputStream(song.uri)) {
                if (in == null) { toast("Couldn't open the file"); return; }
                byte[] head = TagEditor.readCapped(in, 4 * 1024 * 1024);
                TagEditor.Format f = TagEditor.detect(head);
                if (!TagEditor.isEditable(f)) {
                    toast("Tag editing isn't supported for this format yet");
                    return;
                }
                TagEditor.Parsed p;
                try {
                    p = TagEditor.read(head);
                } catch (Exception e) {
                    toast("Couldn't read the tags");
                    return;
                }
                runOnUiThread(() -> showTagEditorDialog(song, p));
            } catch (Exception e) {
                toast("Couldn't read the tags");
            }
        }).start();
    }

    private TextView tagLabel(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(getColor(R.color.spotify_gray));
        v.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private EditText tagInput(String val, boolean multi) {
        EditText e = new EditText(this);
        e.setText(val == null ? "" : val);
        e.setTextColor(getColor(R.color.spotify_white));
        e.setHintTextColor(getColor(R.color.spotify_gray));
        e.setSingleLine(!multi);
        if (multi) {
            e.setMinLines(4);
            e.setGravity(Gravity.TOP);
        }
        return e;
    }

    private void addTagRow(LinearLayout root, java.util.Map<String, EditText> fields,
                           String key, String label, String val, boolean multi) {
        root.addView(tagLabel(label));
        EditText e = tagInput(val, multi);
        root.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fields.put(key, e);
    }

    private android.widget.Button tagButton(String s) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(s);
        b.setTextColor(getColor(R.color.spotify_white));
        b.setBackgroundColor(getColor(R.color.spotify_card));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = (int) dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void updateCoverPreview() {
        if (tagCoverView == null) return;
        if (tagEditCover != null) {
            try {
                Bitmap b = android.graphics.BitmapFactory.decodeByteArray(
                        tagEditCover, 0, tagEditCover.length);
                if (b != null) {
                    tagCoverView.setImageBitmap(b);
                    return;
                }
            } catch (Exception ignored) {}
        }
        tagCoverView.setImageResource(R.drawable.ic_note);
    }

    private void showTagEditorDialog(Song song, TagEditor.Parsed p) {
        tagEditSong = song;
        TagEditor.Tags t = p.tags;
        tagEditCover = t.cover;
        tagEditCoverMime = t.coverMime;
        tagEditRemoveCover = false;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(16);
        root.setPadding(pad, pad / 2, pad, pad / 2);

        final java.util.Map<String, EditText> fields = new java.util.LinkedHashMap<>();
        addTagRow(root, fields, "title", "Title", t.title, false);
        addTagRow(root, fields, "artist", "Artist", t.artist, false);
        addTagRow(root, fields, "album", "Album", t.album, false);
        addTagRow(root, fields, "albumArtist", "Album artist", t.albumArtist, false);
        addTagRow(root, fields, "genre", "Genre", t.genre, false);
        addTagRow(root, fields, "year", "Year", t.year, false);

        root.addView(tagLabel("Track"));
        LinearLayout trackRow = new LinearLayout(this);
        trackRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText trackIn = tagInput(t.track, false);
        trackIn.setHint("3");
        EditText totalIn = tagInput(t.trackTotal, false);
        totalIn.setHint("12");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        trackRow.addView(trackIn, tlp);
        TextView slash = new TextView(this);
        slash.setText(" / ");
        slash.setTextColor(getColor(R.color.spotify_gray));
        slash.setGravity(Gravity.CENTER_VERTICAL);
        trackRow.addView(slash);
        trackRow.addView(totalIn, tlp);
        root.addView(trackRow);
        fields.put("track", trackIn);
        fields.put("trackTotal", totalIn);

        addTagRow(root, fields, "lyrics", "Lyrics", t.lyrics, true);

        root.addView(tagLabel("Cover"));
        tagCoverView = new ImageView(this);
        int cs = (int) dp(160);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cs, cs);
        clp.topMargin = (int) dp(4);
        clp.bottomMargin = (int) dp(8);
        updateCoverPreview();
        root.addView(tagCoverView, clp);
        LinearLayout coverBtns = new LinearLayout(this);
        coverBtns.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button changeBtn = tagButton("Change cover");
        android.widget.Button removeBtn = tagButton("Remove");
        changeBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Choose cover"), REQ_COVER);
            } catch (Exception e) {
                toast("Couldn't open the image picker");
            }
        });
        removeBtn.setOnClickListener(v -> {
            tagEditCover = null;
            tagEditCoverMime = null;
            tagEditRemoveCover = true;
            updateCoverPreview();
        });
        coverBtns.addView(changeBtn);
        coverBtns.addView(removeBtn);
        root.addView(coverBtns);

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(root);
        new AlertDialog.Builder(this)
                .setTitle("Edit tags")
                .setView(sc)
                .setPositiveButton("Save", (d, w) -> collectAndSave(song, p, fields))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean tagsEqual(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        return x.equals(y);
    }

    private void collectAndSave(Song song, TagEditor.Parsed p,
                                java.util.Map<String, EditText> fields) {
        TagEditor.Tags t = new TagEditor.Tags();
        t.title = fields.get("title").getText().toString().trim();
        t.artist = fields.get("artist").getText().toString().trim();
        t.album = fields.get("album").getText().toString().trim();
        t.albumArtist = fields.get("albumArtist").getText().toString().trim();
        t.genre = fields.get("genre").getText().toString().trim();
        t.year = fields.get("year").getText().toString().trim();
        t.track = fields.get("track").getText().toString().trim();
        t.trackTotal = fields.get("trackTotal").getText().toString().trim();
        t.lyrics = fields.get("lyrics").getText().toString()
                .replaceAll("\\s+$", "");
        t.cover = tagEditCover;
        t.coverMime = tagEditCoverMime;
        t.removeCover = tagEditRemoveCover;

        TagEditor.Tags o = p.tags;
        boolean same = tagsEqual(o.title, t.title) && tagsEqual(o.artist, t.artist)
                && tagsEqual(o.album, t.album) && tagsEqual(o.albumArtist, t.albumArtist)
                && tagsEqual(o.genre, t.genre) && tagsEqual(o.year, t.year)
                && tagsEqual(o.track, t.track) && tagsEqual(o.trackTotal, t.trackTotal)
                && tagsEqual(o.lyrics, t.lyrics)
                && !t.removeCover && t.cover == o.cover; // same reference = untouched
        if (same) {
            toast("No changes");
            return;
        }
        saveTags(song, p, t);
    }

    private void saveTags(Song song, TagEditor.Parsed p, TagEditor.Tags t) {
        toast("Saving…");
        new Thread(() -> {
            try {
                byte[] tagBytes = p.format == TagEditor.Format.MP3_ID3
                        ? TagEditor.buildMp3Tag(p, t)
                        : TagEditor.buildFlacMetadata(p, t);
                java.io.File tmp = new java.io.File(getExternalCacheDir(),
                        "tagedit_" + song.id + ".tmp");
                try (java.io.InputStream in = getContentResolver().openInputStream(song.uri);
                     java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                    if (in == null) { toast("Couldn't open the file"); return; }
                    out.write(tagBytes);
                    TagEditor.skipFully(in, p.audioOffset);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                // verify by re-parsing the temp file before touching the original
                byte[] tmpHead;
                try (java.io.InputStream vin = new java.io.FileInputStream(tmp)) {
                    tmpHead = TagEditor.readCapped(vin, 4 * 1024 * 1024);
                }
                if (!TagEditor.verify(tmpHead, t)) {
                    tmp.delete();
                    toast("Verification failed — original file untouched");
                    return;
                }
                pendingTags = t;
                runOnUiThread(() -> requestTagWriteConsent(song, tmp));
            } catch (Exception e) {
                toast("Couldn't save tags");
            }
        }).start();
    }

    private void requestTagWriteConsent(Song song, java.io.File tmp) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                pendingTagSong = song;
                pendingTagTmp = tmp.getAbsolutePath();
                java.util.List<android.net.Uri> uris = new ArrayList<>();
                uris.add(song.uri);
                android.app.PendingIntent pi = android.provider.MediaStore
                        .createWriteRequest(getContentResolver(), uris);
                startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE_TAGS, null, 0, 0, 0);
            } catch (Exception e) {
                pendingTagSong = null;
                pendingTagTmp = null;
                toast("Couldn't request write access");
            }
        } else {
            // API < 30: try a direct write (may fail without WRITE_EXTERNAL_STORAGE)
            writeTagsNow(song, tmp.getAbsolutePath());
        }
    }

    private void writeTagsNow(Song song, String tmpPath) {
        toast("Writing…");
        new Thread(() -> {
            java.io.File tmp = new java.io.File(tmpPath);
            try {
                try (java.io.InputStream in = new java.io.FileInputStream(tmp);
                     java.io.OutputStream out = getContentResolver()
                             .openOutputStream(song.uri, "wt")) {
                    if (out == null) throw new java.io.IOException("no output stream");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                tmp.delete();
                String dataPath = queryPath(song.uri);
                if (dataPath != null) {
                    android.media.MediaScannerConnection.scanFile(this,
                            new String[]{dataPath}, null, null);
                }
                runOnUiThread(() -> afterTagEdit(song));
            } catch (Exception e) {
                tmp.delete();
                toast("Write failed — original file untouched");
            }
        }).start();
    }

    private void afterTagEdit(Song song) {
        TagEditor.Tags t = pendingTags;
        pendingTags = null;
        if (t != null) {
            Song updated = new Song(song.id,
                    t.title.isEmpty() ? "Unknown title" : t.title,
                    t.artist, t.album, song.albumId,
                    song.durationMs, song.dateAdded, song.uri, song.genre);
            Player.get().replaceSongById(updated);
        }
        // the lyrics cache would otherwise keep showing the old embedded lyrics
        getSharedPreferences("lyrics_cache", MODE_PRIVATE).edit()
                .remove("lrc_" + song.id).apply();
        initLibrary();
        Song cur = Player.get().current();
        if (cur != null && cur.id == song.id) refreshNowPlaying();
        toast("Tags saved");
    }

    private String queryPath(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.MediaStore.MediaColumns.DATA},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void handleCoverPicked(android.net.Uri uri) {
        new Thread(() -> {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) { toast("Couldn't read that image"); return; }
                byte[] raw = TagEditor.readCapped(in, 5 * 1024 * 1024);
                android.graphics.BitmapFactory.Options o =
                        new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1000) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                Bitmap b = android.graphics.BitmapFactory.decodeByteArray(
                        raw, 0, raw.length, o);
                if (b == null) { toast("Couldn't read that image"); return; }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                b.recycle();
                tagEditCover = bos.toByteArray();
                tagEditCoverMime = "image/jpeg";
                tagEditRemoveCover = false;
                runOnUiThread(this::updateCoverPreview);
            } catch (Exception e) {
                toast("Couldn't read that image");
            }
        }).start();
    }

    // ----- playback speed -----

    private void cycleSpeed() {
        float cur = Player.get().getSpeed();
        int idx = 0;
        for (int i = 0; i < speedSteps.length; i++) {
            if (Math.abs(speedSteps[i] - cur) < 0.01f) idx = i;
        }
        float next = speedSteps[(idx + 1) % speedSteps.length];
        Player.get().setSpeed(next);
        fx().edit().putFloat("speed", next).apply();
        updateSpeedLabel();
    }

    private void updateSpeedLabel() {
        if (npSpeed == null) return;
        float v = Player.get().getSpeed();
        String t = v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
        npSpeed.setText(t + "×");
    }

    // ----- lyrics -----

    private void openLyrics() {
        Song s = Player.get().current();
        if (s == null) return;
        lyricsView.setVisibility(View.VISIBLE);
        lyricsView.setAlpha(0f);
        lyricsView.setTranslationY(dp(700));
        lyricsView.animate().alpha(1f).translationY(0f).setDuration(450)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
        loadLyricsFor(s);
    }

    private void closeLyrics() {
        lyricsView.animate().alpha(0f).translationY(dp(700)).setDuration(380)
                .setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f))
                .withEndAction(() -> {
                    lyricsView.setVisibility(View.GONE);
                    lyricsView.setAlpha(1f);
                    lyricsView.setTranslationY(0f);
                })
                .start();
    }

    private void loadLyricsFor(Song s) {
        lyricSongId = s.id;
        lyricHighlight = -2;
        lyricLines = null;
        lyricsSong.setText(s.title + " — " + s.artist);
        lyricsText.setText("Loading…");
        new Thread(() -> {
            // v5.5: user-edited lyrics take precedence over every source.
            String edited = lyricEditFor(s.id);
            List<Lyrics.Line> lines =
                    edited != null ? Lyrics.parse(edited) : Lyrics.load(this, s);
            if (lines == null) {
                String cached = getSharedPreferences("lyrics_cache", MODE_PRIVATE)
                        .getString("lrc_" + s.id, null);
                if (cached != null) lines = Lyrics.parse(cached);
            }
            if (lines == null) {
                runOnUiThread(() -> {
                    if (lyricSongId == s.id) lyricsText.setText("Searching online…");
                });
                String raw = Lyrics.fetchOnline(s);
                if (raw != null) {
                    getSharedPreferences("lyrics_cache", MODE_PRIVATE).edit()
                            .putString("lrc_" + s.id, raw).apply();
                    lines = Lyrics.parse(raw);
                }
            }
            List<Lyrics.Line> result = lines;
            runOnUiThread(() -> {
                if (lyricSongId != s.id) return;
                lyricLines = result;
                if (result == null || result.isEmpty()) {
                    lyricsText.setText("No lyrics found for this song.\n\nTip: save a .lrc file "
                            + "with the same name next to the song file and it shows up here.");
                } else {
                    renderLyrics(-1);
                    lyricsScroll.scrollTo(0, 0);
                }
            });
        }).start();
    }

    private void renderLyrics(int current) {
        if (lyricLines == null) return;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int i = 0; i < lyricLines.size(); i++) {
            int start = sb.length();
            sb.append(lyricLines.get(i).text);
            if (i == current) {
                sb.setSpan(new android.text.style.ForegroundColorSpan(
                                getColor(R.color.spotify_green)),
                        start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i < lyricLines.size() - 1) sb.append("\n");
        }
        lyricsText.setText(sb);
    }

    private void tickLyrics() {
        if (lyricsView == null || lyricsView.getVisibility() != View.VISIBLE) return;
        if (lyricLines == null || lyricLines.isEmpty() || lyricLines.get(0).ms < 0) return;
        int pos = Player.get().position();
        int cur = -1;
        for (int i = 0; i < lyricLines.size(); i++) {
            if (lyricLines.get(i).ms <= pos) cur = i;
            else break;
        }
        if (cur != lyricHighlight) {
            lyricHighlight = cur;
            renderLyrics(cur);
            if (cur >= 0 && lyricsText.getLayout() != null) {
                int y = lyricsText.getLayout().getLineTop(cur) - (int) dp(120);
                lyricsScroll.smoothScrollTo(0, Math.max(0, y));
            }
        }
    }

    // ----- v5.5 polish pack -----

    // --- 1. waveform seekbar (SoundCloud model) ---

    private long waveSongId = -1;
    private float waveSeekFrac = 0f;

    private void initWaveform() {
        npWave = findViewById(R.id.npWave);
        npWave.setSeekListener(new WaveformView.SeekListener() {
            @Override public void onSeekStart() { userSeeking = true; }

            @Override public void onSeek(float f) {
                waveSeekFrac = f;
                Song s = Player.get().current();
                int dur = Player.get().duration();
                if (dur <= 0 && s != null) dur = (int) s.durationMs;
                if (dur > 0) npPos.setText(Song.formatTime((int) (f * dur)));
            }

            @Override public void onSeekEnd() {
                userSeeking = false;
                Song s = Player.get().current();
                int dur = Player.get().duration();
                if (dur <= 0 && s != null) dur = (int) s.durationMs;
                if (dur > 0) Player.get().seekTo((int) (waveSeekFrac * dur));
            }
        });
    }

    private void requestWaveform(Song s) {
        waveSongId = s.id;
        if (npWave == null) return;
        npWave.setPeaks(null);
        npWave.setVisibility(View.GONE);
        npSeek.setVisibility(View.VISIBLE);
        Waveform.requestPeaks(this, s, peaks -> {
            Song cur = Player.get().current();
            if (peaks != null && cur != null && cur.id == s.id
                    && nowPlaying.getVisibility() == View.VISIBLE) {
                npWave.setPeaks(peaks);
                npWave.setVisibility(View.VISIBLE);
                npSeek.setVisibility(View.GONE);
            }
        });
    }

    // --- 3. online cover fetch (opt-in, Deezer primary, MusicBrainz/CAA fallback) ---

    private void findCoverOnline(Song song) {
        toast("Searching for covers…");
        CoverFetcher.search(this, song.artist, song.title, covers -> {
            if (covers == null || covers.isEmpty()) {
                toast("No covers found");
                return;
            }
            showCoverPicker(song, covers);
        });
    }

    private void showCoverPicker(Song song, java.util.List<CoverFetcher.Candidate> covers) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(12);
        root.setPadding(pad, pad, pad, pad);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Choose a cover")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .create();
        for (CoverFetcher.Candidate c : covers) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = (int) dp(8);
            row.setLayoutParams(rlp);
            final ImageView thumb = new ImageView(this);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    (int) dp(64), (int) dp(64));
            tlp.setMarginEnd((int) dp(12));
            thumb.setLayoutParams(tlp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setImageResource(R.drawable.ic_note);
            TextView label = new TextView(this);
            label.setText(c.label);
            label.setTextColor(getColor(R.color.spotify_white));
            label.setTextSize(13);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView use = makeChip("Use");
            use.setOnClickListener(v -> {
                toast("Downloading cover…");
                CoverFetcher.download(c.imageUrl, data -> {
                    if (data == null || !CoverCache.save(this, song.id, data)) {
                        toast("Couldn't download that cover");
                        return;
                    }
                    toast("Cover saved");
                    Song cur = Player.get().current();
                    if (cur != null && cur.id == song.id) refreshNowPlaying();
                });
                dlg.dismiss();
            });
            row.addView(thumb);
            row.addView(label);
            row.addView(use);
            root.addView(row);
            CoverFetcher.download(c.imageUrl, data -> {
                if (data == null) return;
                android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp != null) thumb.setImageBitmap(bmp);
            });
        }
        dlg.show();
    }

    // --- 4. lyrics editing (user text wins over every source) ---

    private SharedPreferences lyricEditPrefs() {
        return getSharedPreferences("lyric_edits", Context.MODE_PRIVATE);
    }

    private String lyricEditFor(long id) {
        return lyricEditPrefs().getString("le_" + id, null);
    }

    private void openLyricsEditor() {
        Song s = Player.get().current();
        if (s == null) return;
        String prefill = lyricEditFor(s.id);
        if (prefill == null && lyricLines != null && !lyricLines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Lyrics.Line l : lyricLines) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(l.text);
            }
            prefill = sb.toString();
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(16);
        wrap.setPadding(pad, (int) dp(8), pad, 0);
        final EditText input = new EditText(this);
        input.setText(prefill == null ? "" : prefill);
        input.setTextColor(getColor(R.color.spotify_white));
        input.setHintTextColor(getColor(R.color.spotify_gray));
        input.setHint("Paste or type the lyrics…");
        input.setMinLines(8);
        input.setGravity(Gravity.TOP);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Edit lyrics")
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    String t = input.getText().toString();
                    if (t.trim().isEmpty()) {
                        lyricEditPrefs().edit().remove("le_" + s.id).apply();
                    } else {
                        lyricEditPrefs().edit().putString("le_" + s.id, t).apply();
                    }
                    loadLyricsFor(s);
                })
                .setNeutralButton("Clear", (d, w) -> {
                    lyricEditPrefs().edit().remove("le_" + s.id).apply();
                    toast("Lyrics restored");
                    loadLyricsFor(s);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- 5. duplicate finder (Musicolet-style grouping) ---

    private View dupView;
    private LinearLayout dupList;
    private TextView dupSub;

    private void initDuplicates() {
        dupView = findViewById(R.id.dupView);
        dupList = findViewById(R.id.dupList);
        dupSub = findViewById(R.id.dupSub);
        findViewById(R.id.dupClose).setOnClickListener(v -> closeDuplicates());
        findViewById(R.id.dupChip).setOnClickListener(v -> openDuplicates());
    }

    private void openDuplicates() {
        List<DupFinder.Entry> es = new ArrayList<>();
        for (int i = 0; i < allSongs.size(); i++) {
            Song s = allSongs.get(i);
            es.add(new DupFinder.Entry(s.title, s.artist, s.durationMs, i));
        }
        List<DupFinder.Group> groups = DupFinder.findDuplicates(es);
        if (groups.isEmpty()) {
            toast("No duplicates found");
            return;
        }
        dupList.removeAllViews();
        int total = 0;
        for (DupFinder.Group g : groups) {
            total += g.members.size();
            Song first = allSongs.get(g.members.get(0).index);
            TextView h = new TextView(this);
            h.setText(first.title + " — " + first.artist
                    + "   ·   " + g.members.size() + " copies");
            h.setTextColor(getColor(R.color.spotify_white));
            h.setTextSize(15);
            h.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = (int) dp(18);
            hlp.bottomMargin = (int) dp(4);
            h.setLayoutParams(hlp);
            dupList.addView(h);
            for (DupFinder.Entry e : g.members) {
                if (e.index >= 0 && e.index < allSongs.size()) {
                    dupList.addView(dupRow(allSongs.get(e.index)));
                }
            }
        }
        dupSub.setText(groups.size() + (groups.size() == 1 ? " group" : " groups")
                + " · " + total + " songs");
        dupView.setVisibility(View.VISIBLE);
        dupView.setAlpha(0f);
        dupView.setTranslationY(dp(700));
        dupView.animate().alpha(1f).translationY(0f).setDuration(450)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
    }

    private void closeDuplicates() {
        dupView.animate().alpha(0f).translationY(dp(700)).setDuration(380)
                .setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f))
                .withEndAction(() -> {
                    dupView.setVisibility(View.GONE);
                    dupView.setAlpha(1f);
                    dupView.setTranslationY(0f);
                })
                .start();
    }

    private LinearLayout dupRow(final Song s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) dp(4);
        rlp.bottomMargin = (int) dp(4);
        row.setLayoutParams(rlp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(this);
        title.setText(s.title);
        title.setTextColor(getColor(R.color.spotify_white));
        title.setTextSize(14);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView details = new TextView(this);
        details.setText(dupDetails(s));
        details.setTextColor(getColor(R.color.spotify_gray));
        details.setTextSize(11);
        details.setMaxLines(2);
        details.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        text.addView(details);

        TextView play = makeChip("Play");
        play.setOnClickListener(v -> {
            List<Song> q = new ArrayList<>();
            q.add(s);
            Player.get().play(this, q, 0);
            closeDuplicates();
        });
        TextView del = makeChip("Delete");
        del.setOnClickListener(v -> confirmDelete(s));

        row.addView(text);
        row.addView(play);
        row.addView(del);
        return row;
    }

    private String dupDetails(Song s) {
        String path = songFilePath(s);
        StringBuilder sb = new StringBuilder();
        if (path != null) {
            try {
                long len = new java.io.File(path).length();
                sb.append(String.format(java.util.Locale.US, "%.1f MB", len / 1048576.0));
            } catch (Exception ignored) {}
            try {
                android.media.MediaMetadataRetriever r =
                        new android.media.MediaMetadataRetriever();
                r.setDataSource(this, s.uri);
                String br = r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE);
                r.release();
                if (br != null) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(Integer.parseInt(br) / 1000).append(" kbps");
                }
            } catch (Exception ignored) {}
            if (sb.length() > 0) sb.append("\n");
            sb.append(path);
        } else {
            sb.append("path unavailable");
        }
        return sb.toString();
    }

    private String songFilePath(Song s) {
        try (android.database.Cursor c = getContentResolver().query(s.uri,
                new String[]{android.provider.MediaStore.MediaColumns.DATA},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ----- car mode -----

    private void openCar() {
        carView.setVisibility(View.VISIBLE);
        refreshCar();
    }

    private void closeCar() {
        carView.setVisibility(View.GONE);
    }

    private void refreshCar() {
        Song s = Player.get().current();
        if (s == null) return;
        carTitle.setText(s.title);
        carArtist.setText(s.artist);
        carPlayIcon.setImageResource(Player.get().isPlaying()
                ? R.drawable.ic_pause : R.drawable.ic_play);
        Bitmap cached = MusicLibrary.cachedArt(s.albumId);
        if (cached != null) {
            carArt.clearColorFilter();
            carArt.setImageBitmap(cached);
        } else {
            carArt.setImageResource(R.drawable.ic_note);
            carArt.setColorFilter(getColor(R.color.spotify_gray));
            MusicLibrary.loadArtAsync(this, s.albumId, (albumId, bmp) ->
                    runOnUiThread(() -> {
                        Song cur = Player.get().current();
                        if (cur != null && cur.albumId == albumId && bmp != null
                                && carView.getVisibility() == View.VISIBLE) {
                            carArt.clearColorFilter();
                            carArt.setImageBitmap(bmp);
                        }
                    }));
        }
    }

    // ----- next-up line -----

    private void updateNextUp() {
        if (npNextUp == null) return;
        Song cur = Player.get().current();
        int n = Player.get().peekNextIndex();
        if (cur == null || n == -1) {
            npNextUp.setText("");
            return;
        }
        if (n == -2) {
            npNextUp.setText("Next up: a surprise (shuffle)");
            return;
        }
        java.util.List<Song> q = Player.get().getQueue();
        if (n < 0 || n >= q.size()) {
            npNextUp.setText("");
            return;
        }
        Song next = q.get(n);
        npNextUp.setText(n == Player.get().getIndex()
                ? "Next: " + next.title + " (repeat one)"
                : "Next: " + next.title + " — " + next.artist);
    }

    // ----- listening card (shareable image) -----

    private void shareCard(Song s) {
        Bitmap art = MusicLibrary.cachedArt(s.albumId);
        if (art != null) {
            makeCard(s, art);
        } else {
            MusicLibrary.loadArtAsync(this, s.albumId, (id2, bmp) ->
                    runOnUiThread(() -> makeCard(s, bmp)));
        }
    }

    private void drawFitted(android.graphics.Canvas cv, String text, float cx, float y,
                            float maxW, android.graphics.Paint p, float startSize) {
        float size = startSize;
        p.setTextSize(size);
        while (p.measureText(text) > maxW && size > 24) {
            size -= 4;
            p.setTextSize(size);
        }
        cv.drawText(text, cx, y, p);
    }

    private void makeCard(Song s, Bitmap art) {
        new Thread(() -> {
            try {
                int W = 1080;
                int H = 1080;
                Bitmap card = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(card);
                int bg = s.albumId >= 0
                        ? darken(MusicLibrary.tintFor(s.albumId), 0.30f) : 0xFF181818;
                cv.drawColor(bg);
                android.graphics.Paint p = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                if (art != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(art, 780, 780, true);
                    cv.drawBitmap(scaled, (W - 780) / 2f, 90, p);
                } else {
                    p.setColor(0xFF282828);
                    cv.drawRoundRect(new android.graphics.RectF(150, 90, 930, 870),
                            24, 24, p);
                }
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                p.setColor(getColor(R.color.spotify_white));
                p.setTypeface(android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
                drawFitted(cv, s.title, W / 2f, 968, 940, p, 58);
                p.setTypeface(android.graphics.Typeface.DEFAULT);
                p.setColor(0xFFB3B3B3);
                drawFitted(cv, s.artist, W / 2f, 1022, 940, p, 40);
                p.setColor(0xFF8A8A8A);
                p.setTextSize(26);
                p.setLetterSpacing(0.35f);
                cv.drawText("SPOTPLAYER", W / 2f, 1062, p);

                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        "spotplayer_card_" + s.id + ".png");
                values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/SpotPlayer");
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return;
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) card.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("image/png");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.putExtra(Intent.EXTRA_TEXT, s.title + " — " + s.artist);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Share card"));
                });
            } catch (Exception ignored) {}
        }).start();
    }

    // ----- shake to skip -----

    private android.hardware.SensorManager sensorManager;
    private android.hardware.SensorEventListener shakeListener;
    private long lastShakeAt = 0;

    private void updateShake() {
        boolean on = fx().getBoolean(Player.K_SHAKE, false);
        if (sensorManager != null && shakeListener != null) {
            sensorManager.unregisterListener(shakeListener);
        }
        if (!on) return;
        if (sensorManager == null) {
            sensorManager = (android.hardware.SensorManager)
                    getSystemService(Context.SENSOR_SERVICE);
            shakeListener = new android.hardware.SensorEventListener() {
                @Override
                public void onSensorChanged(android.hardware.SensorEvent e) {
                    float x = e.values[0], y = e.values[1], z = e.values[2];
                    double mag = Math.sqrt(x * x + y * y + z * z) - 9.81;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (mag > 13 && now - lastShakeAt > 1200 && Player.get().isPlaying()) {
                        lastShakeAt = now;
                        Player.get().next(MainActivity.this);
                    }
                }

                @Override
                public void onAccuracyChanged(android.hardware.Sensor s, int a) {}
            };
        }
        if (sensorManager == null) return;
        android.hardware.Sensor acc = sensorManager.getDefaultSensor(
                android.hardware.Sensor.TYPE_ACCELEROMETER);
        if (acc != null) {
            sensorManager.registerListener(shakeListener, acc,
                    android.hardware.SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShake();
        // Coming back from system Settings with the permission granted
        if (permissionView != null && permissionView.getVisibility() == View.VISIBLE
                && hasAudioPermission()) {
            permissionView.setVisibility(View.GONE);
            mainContent.setVisibility(View.VISIBLE);
            initLibrary();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null && shakeListener != null) {
            sensorManager.unregisterListener(shakeListener);
        }
    }

    // ----- launcher shortcuts -----

    private String pendingShortcut = null;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShortcut(intent);
    }

    private void handleShortcut(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String target = intent.getData().getLastPathSegment();
        intent.setData(null);
        if (target == null) return;
        if (allSongs == null || allSongs.isEmpty()) {
            pendingShortcut = target;
            return;
        }
        routeShortcut(target);
    }

    private void routeShortcut(String target) {
        switch (target) {
            case "liked":
                openPanel(0, -1);
                break;
            case "mix":
                openPanel(7, -1);
                break;
            case "shuffle":
                Player.get().setShuffle(true);
                int start = new java.util.Random().nextInt(allSongs.size());
                Player.get().play(this, allSongs, start);
                break;
            default:
                break;
        }
    }

    // ----- stats page + card -----

    private List<String[]> statsRows() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Total listening time",
                formatListenTime(stats().getLong("totalSec", 0))});
        JSONObject counts = countsJson();
        Map<String, Song> byId = byIdMap();
        int played = 0;
        Song topSong = null;
        int topSongCount = 0;
        Map<String, Integer> artistCounts = new HashMap<>();
        java.util.Iterator<String> keys = counts.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            int c = counts.optInt(k, 0);
            played += c;
            Song s = byId.get(k);
            if (s != null) {
                if (c > topSongCount) {
                    topSongCount = c;
                    topSong = s;
                }
                Integer prev = artistCounts.get(s.artist);
                artistCounts.put(s.artist, (prev == null ? 0 : prev) + c);
            }
        }
        String topArtist = null;
        int topArtistCount = 0;
        for (Map.Entry<String, Integer> e : artistCounts.entrySet()) {
            if (e.getValue() > topArtistCount) {
                topArtistCount = e.getValue();
                topArtist = e.getKey();
            }
        }
        rows.add(new String[]{"Songs played", String.valueOf(played)});
        rows.add(new String[]{"Top song", topSong == null ? "—"
                : topSong.title + " · " + topSongCount + " plays"});
        rows.add(new String[]{"Top artist", topArtist == null ? "—"
                : topArtist + " · " + topArtistCount + " plays"});
        rows.add(new String[]{"Liked songs", likedIdSet().size() + " songs"});
        rows.add(new String[]{"In your library", allSongs.size() + " songs"});
        return rows;
    }

    private String formatListenTime(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        if (h > 0) return h + " h " + m + " min";
        if (m > 0) return m + " min";
        return sec + " sec";
    }

    private android.widget.BaseAdapter buildStatsAdapter() {
        final List<String[]> data = statsRows();
        return new android.widget.BaseAdapter() {
            @Override public int getCount() { return data.size(); }
            @Override public Object getItem(int i) { return data.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                if (cv == null) {
                    cv = getLayoutInflater().inflate(R.layout.item_group_row, parent, false);
                }
                ((TextView) cv).setText(data.get(i)[0] + "   ·   " + data.get(i)[1]);
                return cv;
            }
        };
    }

    private void shareStatsCard() {
        final List<String[]> rows = statsRows();
        new Thread(() -> {
            try {
                int W = 1080;
                int H = 1080;
                Bitmap card = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(card);
                cv.drawColor(0xFF121212);
                android.graphics.Paint p = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                p.setTextAlign(android.graphics.Paint.Align.LEFT);
                p.setColor(getColor(R.color.spotify_green));
                p.setTypeface(android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
                p.setTextSize(72);
                cv.drawText("Your Stats", 80, 170, p);
                float y = 320;
                for (String[] r : rows) {
                    p.setColor(0xFFB3B3B3);
                    p.setTypeface(android.graphics.Typeface.DEFAULT);
                    p.setTextSize(38);
                    cv.drawText(r[0], 80, y, p);
                    p.setColor(getColor(R.color.spotify_white));
                    p.setTypeface(android.graphics.Typeface.create(
                            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
                    drawFittedLeft(cv, r[1], 80, y + 62, 920, p, 52);
                    y += 140;
                }
                p.setColor(0xFF8A8A8A);
                p.setTypeface(android.graphics.Typeface.DEFAULT);
                p.setTextSize(26);
                p.setLetterSpacing(0.35f);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                cv.drawText("SPOTPLAYER", W / 2f, 1030, p);

                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        "spotplayer_stats.png");
                values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/SpotPlayer");
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return;
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) card.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("image/png");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Share stats"));
                });
            } catch (Exception ignored) {}
        }).start();
    }

    private void drawFittedLeft(android.graphics.Canvas cv, String text, float x, float y,
                                float maxW, android.graphics.Paint p, float startSize) {
        float size = startSize;
        p.setTextSize(size);
        while (p.measureText(text) > maxW && size > 22) {
            size -= 3;
            p.setTextSize(size);
        }
        cv.drawText(text, x, y, p);
    }

    // ----- listening time + A-B loop (200 ms tick) -----

    private float listenAcc = 0f;

    private void tickListeningAndAb() {
        if (Player.get().isPlaying()) {
            listenAcc += 0.2f;
            if (listenAcc >= 5f) {
                listenAcc = 0f;
                stats().edit().putLong("totalSec",
                        stats().getLong("totalSec", 0) + 5).apply();
            }
        }
        if (abA >= 0 && abB > abA && Player.get().position() >= abB) {
            Player.get().seekTo(abA);
        }
    }

    private void resetAb() {
        abA = -1;
        abB = -1;
        if (npAb != null) {
            npAb.setText("A–B");
            npAb.setTextColor(getColor(R.color.spotify_white));
        }
    }

    // ----- double-tap cover to like -----

    private void doubleTapLike() {
        Song s = Player.get().current();
        if (s == null) return;
        if (!isLiked(s)) {
            toggleLike();
            updateLikeIcon(s);
        }
        if (npBigHeart != null) {
            npBigHeart.setVisibility(View.VISIBLE);
            npBigHeart.setAlpha(0f);
            npBigHeart.setScaleX(0.5f);
            npBigHeart.setScaleY(0.5f);
            npBigHeart.animate().alpha(1f).scaleX(1.05f).scaleY(1.05f).setDuration(180)
                    .withEndAction(() -> npBigHeart.animate().alpha(0f)
                            .setStartDelay(320).setDuration(280)
                            .withEndAction(() -> npBigHeart.setVisibility(View.GONE))
                            .start())
                    .start();
        }
    }

    // ----- 15 s preview (own player, queue untouched) -----

    private androidx.media3.exoplayer.ExoPlayer previewExo;
    private boolean previewWasPlaying = false;

    private void previewSong(Song s) {
        stopPreview(false);
        previewWasPlaying = Player.get().isPlaying();
        if (previewWasPlaying) Player.get().pause(this);
        android.widget.Toast.makeText(this, "Preview: " + s.title,
                android.widget.Toast.LENGTH_SHORT).show();
        androidx.media3.exoplayer.ExoPlayer exo =
                new androidx.media3.exoplayer.ExoPlayer.Builder(this).build();
        previewExo = exo;
        try {
            exo.setMediaItem(androidx.media3.common.MediaItem.fromUri(s.uri));
            exo.addListener(new androidx.media3.common.Player.Listener() {
                private boolean ready = false;

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == androidx.media3.common.Player.STATE_READY
                            && !ready && exo == previewExo) {
                        ready = true;
                        try {
                            long dur = exo.getDuration();
                            if (dur != androidx.media3.common.C.TIME_UNSET && dur > 0) {
                                exo.seekTo((long) (dur * 0.35));
                            }
                            exo.play();
                        } catch (Exception ignored) {}
                        ticker.postDelayed(() -> stopPreview(true), 15000);
                    }
                }

                @Override
                public void onPlayerError(
                        androidx.media3.common.PlaybackException e) {
                    stopPreview(true);
                }
            });
            exo.prepare();
        } catch (Exception e) {
            stopPreview(true);
        }
    }

    private void stopPreview(boolean resume) {
        if (previewExo != null) {
            try {
                previewExo.stop();
            } catch (Exception ignored) {}
            try {
                previewExo.release();
            } catch (Exception ignored) {}
            previewExo = null;
        }
        if (resume && previewWasPlaying && Player.get().current() != null
                && !Player.get().isPlaying()) {
            Player.get().toggle(this);
        }
        previewWasPlaying = false;
    }

    // ----- ringtone -----

    private void setRingtone(Song s) {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.System.canWrite(this)) {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
            android.widget.Toast.makeText(this,
                    "Allow modifying settings, then try again",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        try {
            android.media.RingtoneManager.setActualDefaultRingtoneUri(this,
                    android.media.RingtoneManager.TYPE_RINGTONE, s.uri);
            android.widget.Toast.makeText(this, "Ringtone set",
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Could not set ringtone",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ----- delete from device -----

    private Song pendingDeleteSong = null;

    private void confirmDelete(Song s) {
        new AlertDialog.Builder(this)
                .setTitle("Delete song?")
                .setMessage("“" + s.title + "” will be removed from this phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> deleteSong(s))
                .show();
    }

    private void deleteSong(Song s) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                pendingDeleteSong = s;
                java.util.List<android.net.Uri> uris = new ArrayList<>();
                uris.add(s.uri);
                android.app.PendingIntent pi = android.provider.MediaStore
                        .createDeleteRequest(getContentResolver(), uris);
                startIntentSenderForResult(pi.getIntentSender(), 4711, null, 0, 0, 0);
            } catch (Exception e) {
                pendingDeleteSong = null;
                android.widget.Toast.makeText(this, "Delete failed",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        } else {
            try {
                int n = getContentResolver().delete(s.uri, null, null);
                if (n > 0) afterDelete(s);
                else android.widget.Toast.makeText(this, "Delete failed",
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "Delete failed",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void afterDelete(Song s) {
        java.util.List<Song> q = Player.get().getQueue();
        for (int i = 0; i < q.size(); i++) {
            if (q.get(i).id == s.id) {
                Player.get().removeAt(this, i);
                break;
            }
        }
        android.widget.Toast.makeText(this, "Deleted",
                android.widget.Toast.LENGTH_SHORT).show();
        initLibrary();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 4711 && resultCode == RESULT_OK && pendingDeleteSong != null) {
            Song s = pendingDeleteSong;
            pendingDeleteSong = null;
            afterDelete(s);
        }
        if (requestCode == REQ_COVER && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            handleCoverPicked(data.getData());
        }
        if (requestCode == REQ_WRITE_TAGS && pendingTagSong != null) {
            Song s = pendingTagSong;
            String path = pendingTagTmp;
            pendingTagSong = null;
            pendingTagTmp = null;
            if (resultCode == RESULT_OK) {
                writeTagsNow(s, path);
            } else {
                new java.io.File(path).delete();
                toast("Write cancelled");
            }
        }
    }

    // ----- queue drag-to-reorder -----

    private int dragPos = -1;
    private float dragStartY = 0;
    private boolean dragArmed = false;
    private boolean dragMoved = false;
    private final android.os.Handler dragHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable dragStarter = () -> dragArmed = true;

    private void cancelDrag() {
        dragHandler.removeCallbacks(dragStarter);
        dragArmed = false;
        dragPos = -1;
    }

    private void tintToggle(ImageView v, boolean on) {
        v.setColorFilter(getColor(on ? R.color.spotify_green : R.color.spotify_gray));
        // Spotify pattern: a small green dot under the icon marks the active toggle
        if (v == npShuffle && npShuffleDot != null) {
            npShuffleDot.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (v == npRepeat && npRepeatDot != null) {
            npRepeatDot.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    public void onBackPressed() {
        if (carView != null && carView.getVisibility() == View.VISIBLE) {
            closeCar();
        } else if (lyricsView != null && lyricsView.getVisibility() == View.VISIBLE) {
            closeLyrics();
        } else if (eqView != null && eqView.getVisibility() == View.VISIBLE) {
            closeEq();
        } else if (panelView != null && panelView.getVisibility() == View.VISIBLE) {
            closePanel();
        } else if (nowPlaying != null && nowPlaying.getVisibility() == View.VISIBLE) {
            hideNowPlaying();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ticker.removeCallbacks(tick);
        Player.get().setOnEqRouteChanged(null);
        if (probeEq != null) {
            try { probeEq.release(); } catch (Exception ignored) {}
            probeEq = null;
        }
    }
}
