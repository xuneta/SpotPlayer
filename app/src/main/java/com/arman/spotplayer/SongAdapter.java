package com.arman.spotplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/** Vertical song list rows (ListView) — Spotify style. */
public class SongAdapter extends ArrayAdapter<Song> {

    private final LayoutInflater inflater;

    public SongAdapter(Context ctx, List<Song> songs) {
        super(ctx, 0, songs);
        inflater = LayoutInflater.from(ctx);
    }

    public void setSongs(List<Song> songs) {
        clear();
        addAll(songs);
        notifyDataSetChanged();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.item_song, parent, false);
        }
        bindRow(v, getItem(position));
        return v;
    }

    /** Rating source (MainActivity). Null = no ratings. */
    public interface RatingSource {
        int ratingFor(long songId);
    }

    public static RatingSource ratingSource;

    /** Binds a song into an item_song view. Shared with the grouped search adapter. */
    public static void bindRow(View v, Song s) {
        VH h;
        Object tag = v.getTag();
        if (tag instanceof VH) {
            h = (VH) tag;
        } else {
            h = new VH();
            h.art = v.findViewById(R.id.songArt);
            h.art.setClipToOutline(true);
            h.title = v.findViewById(R.id.songTitle);
            h.artist = v.findViewById(R.id.songArtist);
            h.duration = v.findViewById(R.id.songDuration);
            h.eq = v.findViewById(R.id.songEq);
            v.setTag(h);
        }
        Song cur = Player.get().current();
        boolean isCurrent = cur != null && cur.id == s.id;

        h.title.setText(s.title);
        h.title.setTextColor(v.getContext().getColor(
                isCurrent ? R.color.spotify_green : R.color.spotify_white));
        int r = ratingSource != null ? ratingSource.ratingFor(s.id) : 0;
        h.artist.setText(s.artist + (r > 0 ? "  ·  ★" + r : ""));
        h.duration.setText(Song.formatTime(s.durationMs));
        h.eq.setVisibility(isCurrent && Player.get().isPlaying() ? View.VISIBLE : View.GONE);
        bindArt(h.art, s);
    }

    static void bindArt(ImageView art, Song s) {
        art.setTag(s.albumId);
        Bitmap cached = MusicLibrary.cachedArt(s.albumId);
        if (cached != null) {
            art.clearColorFilter();
            art.setImageBitmap(cached);
        } else {
            art.setImageResource(R.drawable.ic_note);
            art.setColorFilter(art.getContext().getColor(R.color.spotify_gray));
            MusicLibrary.loadArtAsync(art.getContext(), s.albumId, (albumId, bmp) -> {
                if (bmp == null) return;
                art.post(() -> {
                    Object tag = art.getTag();
                    if (tag instanceof Long && (Long) tag == albumId) {
                        art.clearColorFilter();
                        art.setImageBitmap(bmp);
                    }
                });
            });
        }
    }

    static class VH {
        ImageView art;
        TextView title, artist, duration;
        ImageView eq;
    }
}
