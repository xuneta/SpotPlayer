package com.arman.spotplayer;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/** Spotify home-screen quick-pick cards (GridView). */
public class QuickAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Song> songs;

    public QuickAdapter(Context ctx, List<Song> songs) {
        inflater = LayoutInflater.from(ctx);
        this.songs = songs;
    }

    public List<Song> getSongs() {
        return songs;
    }

    @Override
    public int getCount() {
        return songs == null ? 0 : songs.size();
    }

    @Override
    public Song getItem(int position) {
        return songs.get(position);
    }

    @Override
    public long getItemId(int position) {
        return songs.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        VH h;
        if (v == null) {
            v = inflater.inflate(R.layout.item_quick, parent, false);
            h = new VH();
            h.art = v.findViewById(R.id.quickArt);
            h.art.setClipToOutline(true);
            h.title = v.findViewById(R.id.quickTitle);
            v.setTag(h);
        } else {
            h = (VH) v.getTag();
        }
        Song s = songs.get(position);
        h.title.setText(s.title);
        SongAdapter.bindArt(h.art, s);
        return v;
    }

    static class VH {
        ImageView art;
        TextView title;
    }
}
