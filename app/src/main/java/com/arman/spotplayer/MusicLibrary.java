package com.arman.spotplayer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.LruCache;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicLibrary {

    private static final Uri ALBUM_ART_URI = Uri.parse("content://media/external/audio/albumart");
    private static final LruCache<Long, Bitmap> artCache = new LruCache<>(48);
    private static final LruCache<Long, Integer> tintCache = new LruCache<>(48);
    private static final ExecutorService artExecutor = Executors.newFixedThreadPool(3);

    public interface ArtCallback {
        void onArt(long albumId, Bitmap bitmap);
    }

    /** Scans the device music library (MediaStore). Only real files on the phone — nothing bundled. */
    public static List<Song> loadSongs(Context ctx) {
        List<Song> out = new ArrayList<>();
        Map<Long, String> songGenres = loadSongGenres(ctx);
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
                + MediaStore.Audio.Media.DURATION + " > 0";
        try (Cursor c = ctx.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int iDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                while (c.moveToNext()) {
                    long id = c.getLong(iId);
                    Uri uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    out.add(new Song(id, c.getString(iTitle), c.getString(iArtist),
                            c.getString(iAlbum), c.getLong(iAlbumId), c.getLong(iDur),
                            c.getLong(iDate), uri, songGenres.get(id)));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** songId -> genre name, from MediaStore.Audio.Genres members. First genre wins. */
    public static Map<Long, String> loadSongGenres(Context ctx) {
        Map<Long, String> out = new java.util.HashMap<>();
        try {
            ContentResolver cr = ctx.getContentResolver();
            try (Cursor g = cr.query(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME},
                    null, null, null)) {
                if (g == null) return out;
                int gi = g.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID);
                int gn = g.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME);
                while (g.moveToNext()) {
                    long genreId = g.getLong(gi);
                    String name = g.getString(gn);
                    if (name == null || name.isEmpty()) continue;
                    Uri members = MediaStore.Audio.Genres.Members.getContentUri("external", genreId);
                    try (Cursor m = cr.query(members,
                            new String[]{MediaStore.Audio.Genres.Members.AUDIO_ID},
                            null, null, null)) {
                        if (m == null) continue;
                        int ma = m.getColumnIndexOrThrow(
                                MediaStore.Audio.Genres.Members.AUDIO_ID);
                        while (m.moveToNext()) {
                            long songId = m.getLong(ma);
                            if (!out.containsKey(songId)) out.put(songId, name);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Genre names with member counts, alphabetical. Built from the genre member map. */
    public static java.util.List<GenreEntry> loadGenreList(Context ctx) {
        Map<Long, String> sm = loadSongGenres(ctx);
        Map<String, Integer> counts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : sm.values()) {
            counts.put(name, counts.getOrDefault(name, 0) + 1);
        }
        java.util.List<GenreEntry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            out.add(new GenreEntry(e.getKey(), e.getValue()));
        }
        return out;
    }

    public static class GenreEntry {
        public final String name;
        public final int count;
        GenreEntry(String name, int count) { this.name = name; this.count = count; }
    }

    public static Bitmap cachedArt(long albumId) {
        return artCache.get(albumId);
    }

    public static void loadArtAsync(Context ctx, long albumId, ArtCallback cb) {
        Bitmap hit = artCache.get(albumId);
        if (hit != null) {
            cb.onArt(albumId, hit);
            return;
        }
        final Context app = ctx.getApplicationContext();
        artExecutor.execute(() -> {
            Bitmap bmp = decodeArt(app, albumId);
            if (bmp != null) {
                artCache.put(albumId, bmp);
                cb.onArt(albumId, bmp);
            }
        });
    }

    private static Bitmap decodeArt(Context ctx, long albumId) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri artUri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId);
            try (InputStream is = cr.openInputStream(artUri)) {
                if (is == null) return null;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Dark, muted dominant color of the album art — used for the Now Playing gradient (Spotify-style). */
    public static int tintFor(long albumId) {
        Integer cached = tintCache.get(albumId);
        if (cached != null) return cached;
        int fallback = Color.rgb(40, 40, 40);
        Bitmap bmp = artCache.get(albumId);
        if (bmp == null) return fallback;
        try {
            Bitmap small = Bitmap.createScaledBitmap(bmp, 12, 12, false);
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int x = 0; x < small.getWidth(); x++) {
                for (int y = 0; y < small.getHeight(); y++) {
                    int p = small.getPixel(x, y);
                    int pr = Color.red(p), pg = Color.green(p), pb = Color.blue(p);
                    int bright = Math.max(pr, Math.max(pg, pb));
                    if (bright < 24) continue;
                    r += pr; g += pg; b += pb; n++;
                }
            }
            if (n == 0) return fallback;
            int cr = (int) (r / n * 0.62f);
            int cg = (int) (g / n * 0.62f);
            int cb = (int) (b / n * 0.62f);
            int color = Color.rgb(cr, cg, cb);
            tintCache.put(albumId, color);
            return color;
        } catch (Exception e) {
            return fallback;
        }
    }
}
