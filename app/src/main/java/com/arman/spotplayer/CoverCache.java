package com.arman.spotplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Online cover art cache, keyed by song id. Lives in app-private storage
 * (filesDir/covers/c&lt;songId&gt;.jpg) so it never touches the audio file —
 * embedded covers stay the tag editor's job. Explicit user picks only.
 */
public final class CoverCache {

    private CoverCache() {}

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "covers");
        d.mkdirs();
        return d;
    }

    public static File fileFor(Context ctx, long songId) {
        return new File(dir(ctx), "c" + songId + ".jpg");
    }

    public static boolean has(Context ctx, long songId) {
        File f = fileFor(ctx, songId);
        return f.exists() && f.length() > 0;
    }

    /** Stores raw image bytes (any format BitmapFactory reads). */
    public static boolean save(Context ctx, long songId, byte[] data) {
        if (data == null || data.length == 0) return false;
        try {
            // Normalize to JPEG so the cache is uniform and small.
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            File f = fileFor(ctx, songId);
            if (bmp != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos);
                byte[] jpg = bos.toByteArray();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(jpg);
                }
                return true;
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Bitmap load(Context ctx, long songId) {
        try (FileInputStream in = new FileInputStream(fileFor(ctx, songId))) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }
}
