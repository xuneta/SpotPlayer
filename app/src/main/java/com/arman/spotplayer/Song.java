package com.arman.spotplayer;

import android.net.Uri;

public class Song {
    public final long id;
    public final String title;
    public final String artist;
    public final String album;
    public final long albumId;
    public final long durationMs;
    public final long dateAdded;
    public final Uri uri;
    public final String genre;

    public Song(long id, String title, String artist, String album, long albumId,
                long durationMs, long dateAdded, Uri uri, String genre) {
        this.id = id;
        this.title = title == null || title.isEmpty() ? "Unknown title" : title;
        this.artist = artist == null || artist.isEmpty() || artist.equals("<unknown>")
                ? "Unknown artist" : artist;
        this.album = album == null ? "" : album;
        this.albumId = albumId;
        this.durationMs = durationMs;
        this.dateAdded = dateAdded;
        this.uri = uri;
        this.genre = genre == null ? "" : genre;
    }

    public static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return min + ":" + (sec < 10 ? "0" : "") + sec;
    }
}
