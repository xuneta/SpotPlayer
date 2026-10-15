package com.arman.spotplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Foreground playback service: owns the notification (with shuffle /
 * previous / play-pause / next actions) and the media session used by
 * the lock screen and headset buttons. Playback itself lives in Player.
 */
public class PlaybackService extends Service implements Player.Listener {

    public static final String ACTION_TOGGLE = "com.arman.spotplayer.TOGGLE";
    public static final String ACTION_NEXT = "com.arman.spotplayer.NEXT";
    public static final String ACTION_PREV = "com.arman.spotplayer.PREV";
    public static final String ACTION_SHUFFLE = "com.arman.spotplayer.SHUFFLE";
    public static final String ACTION_STOP = "com.arman.spotplayer.STOP";
    // v5.6.6: fired by AlarmReceiver for the fixed sleep clock / wake alarm.
    public static final String ACTION_SLEEP_FIRE = "com.arman.spotplayer.SLEEP_FIRE";
    public static final String ACTION_WAKE_FIRE = "com.arman.spotplayer.WAKE_FIRE";

    private static final String CHANNEL_ID = "playback";
    private static final int NOTIF_ID = 1001;

    private MediaSession session;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Pause when headphones are unplugged / Bluetooth disconnects mid-play. */
    private final android.content.BroadcastReceiver noisyReceiver =
            new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent i) {
                    if (android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY
                            .equals(i.getAction()) && Player.get().isPlaying()) {
                        Player.get().pause(PlaybackService.this);
                    }
                }
            };
    private Bitmap currentArt;
    private long currentArtAlbum = -2;

    // ----- headset button click mapping -----
    // Wired headsets send KEYCODE_HEADSETHOOK once per press; we count clicks
    // inside a ~450 ms window. Single press is fixed = play/pause; double and
    // triple are user-mappable (audiofx prefs "btn_double"/"btn_triple":
    // 0=next, 1=previous, 2=nothing). If both are "nothing", we act instantly
    // with zero added latency (no window wait).
    // KNOWN OS-LEVEL LIMITS (can't be bypassed from here):
    // - Samsung and some other OEMs remap a fast double-press at the OS level
    //   into KEYCODE_MEDIA_NEXT, so it never reaches us as two hook events.
    // - Bluetooth remotes mostly send AVRCP NEXT/PREV/PLAY directly; those hit
    //   onSkipToNext/onSkipToPrevious as before. Only the hook button itself
    //   is remappable here.
    private int hookClicks = 0;
    private final Runnable hookRunner = new Runnable() {
        @Override public void run() {
            int c = hookClicks;
            hookClicks = 0;
            if (c == 1) {
                Player.get().toggle(PlaybackService.this);
            } else if (c == 2) {
                doHookAction(Player.K_BTN_DOUBLE, 0);
            } else if (c >= 3) {
                doHookAction(Player.K_BTN_TRIPLE, 1);
            }
        }
    };

    private void doHookAction(String key, int def) {
        int a = def;
        try {
            a = getSharedPreferences(Player.FX, MODE_PRIVATE).getInt(key, def);
        } catch (Exception ignored) {}
        if (a == 0) Player.get().next(PlaybackService.this);
        else if (a == 1) Player.get().prev(PlaybackService.this);
        // 2 = nothing
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Music playback controls");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        session = new MediaSession(this, "SpotPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                if (!Player.get().isPlaying()) Player.get().toggle(PlaybackService.this);
            }
            @Override public void onPause() {
                if (Player.get().isPlaying()) Player.get().toggle(PlaybackService.this);
            }
            @Override public void onSkipToNext() { Player.get().next(PlaybackService.this); }
            @Override public void onSkipToPrevious() { Player.get().prev(PlaybackService.this); }
            @Override public void onSeekTo(long pos) {
                Player.get().seekTo((int) pos);
                main.post(PlaybackService.this::updatePlaybackState);
            }
            @Override public void onStop() {
                if (Player.get().isPlaying()) Player.get().toggle(PlaybackService.this);
            }
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                try {
                    android.view.KeyEvent e = mediaButtonIntent
                            .getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (e != null && e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                        int kc = e.getKeyCode();
                        if (kc == android.view.KeyEvent.KEYCODE_HEADSETHOOK
                                || kc == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                            int d = 0, t = 1;
                            try {
                                android.content.SharedPreferences p = getSharedPreferences(
                                        Player.FX, MODE_PRIVATE);
                                d = p.getInt(Player.K_BTN_DOUBLE, 0);
                                t = p.getInt(Player.K_BTN_TRIPLE, 1);
                            } catch (Exception ignored) {}
                            if (d == 2 && t == 2) {
                                // Both disabled: instant toggle, no window delay.
                                Player.get().toggle(PlaybackService.this);
                                return true;
                            }
                            hookClicks++;
                            main.removeCallbacks(hookRunner);
                            main.postDelayed(hookRunner, 450);
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);
        try {
            registerReceiver(noisyReceiver, new android.content.IntentFilter(
                    android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (Exception ignored) {}
        Player.get().setServiceListener(this);
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            Player p = Player.get();
            switch (a) {
                case ACTION_TOGGLE:
                    if (p.current() != null) p.toggle(this);
                    break;
                case ACTION_NEXT:
                    p.next(this);
                    break;
                case ACTION_PREV:
                    p.prev(this);
                    break;
                case ACTION_SHUFFLE:
                    p.setShuffle(!p.isShuffle());
                    updateAll();
                    break;
                case ACTION_SLEEP_FIRE:
                    p.sleepNow();
                    break;
                case ACTION_WAKE_FIRE:
                    p.wakePlay(this);
                    break;
                case ACTION_STOP:
                    p.stopFully(this);
                    stopForeground(true);
                    stopSelf();
                    return START_NOT_STICKY;
                default:
                    break;
            }
        }
        if (Player.get().current() == null) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        Player p = Player.get();
        if (p.isPlaying()) p.toggle(this);
        p.setServiceListener(null);
        p.onServiceDestroyed();
        main.removeCallbacks(stateTicker);
        tickerOn = false;
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ----- Player.Listener -----

    @Override
    public void onSongChanged(Song song) {
        if (song == null) {
            // full stop (mini-player swipe-down): wind the service down
            main.post(() -> {
                stopForeground(true);
                stopSelf();
            });
            return;
        }
        main.post(() -> {
            currentArt = null;
            currentArtAlbum = -2;
            updateMetadata(song);
            updateAll();
            ensureTicker();
            Bitmap cached = MusicLibrary.cachedArt(song.albumId);
            if (cached != null) {
                currentArt = cached;
                currentArtAlbum = song.albumId;
                updateMetadata(song);
                updateAll();
            } else {
                MusicLibrary.loadArtAsync(this, song.albumId, (albumId, bmp) -> {
                    if (bmp == null) return;
                    main.post(() -> {
                        Song cur = Player.get().current();
                        if (cur != null && cur.albumId == albumId) {
                            currentArt = bmp;
                            currentArtAlbum = albumId;
                            updateMetadata(cur);
                            updateAll();
                        }
                    });
                });
            }
        });
    }

    /** Keeps the media session's position/duration fresh for the system media card. */
    private boolean tickerOn = false;

    private final Runnable stateTicker = new Runnable() {
        @Override public void run() {
            try {
                Song cur = Player.get().current();
                if (cur != null) {
                    updateMetadata(cur);
                    updatePlaybackState();
                    main.postDelayed(this, 2000);
                    return;
                }
            } catch (Exception ignored) {}
            tickerOn = false;
        }
    };

    private void ensureTicker() {
        if (tickerOn) return;
        tickerOn = true;
        main.removeCallbacks(stateTicker);
        main.postDelayed(stateTicker, 1200);
    }

    @Override
    public void onPlayStateChanged(boolean playing) {
        main.post(() -> {
            updateAll();
            ensureTicker();
        });
    }

    // ----- notification + session -----

    private void updateAll() {
        updatePlaybackState();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        Song cs = Player.get().current();
        android.graphics.Bitmap art = cs != null && currentArt != null
                && currentArtAlbum == cs.albumId ? currentArt : null;
        SpotWidget.update(this, cs, Player.get().isPlaying(), art);
    }

    private PendingIntent actionIntent(String action, int req) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Player p = Player.get();
        Song s = p.current();
        boolean playing = p.isPlaying();
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_s)
                .setContentTitle(s != null ? s.title : "SpotPlayer")
                .setContentText(s != null ? s.artist : "")
                .setContentIntent(content)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(playing)
                .setShowWhen(false);
        if (currentArt != null && s != null && currentArtAlbum == s.albumId) {
            b.setLargeIcon(currentArt);
        }
        if (!playing) {
            b.setDeleteIntent(actionIntent(ACTION_STOP, 9));
        }
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this,
                        p.isShuffle() ? R.drawable.ic_shuffle : R.drawable.ic_shuffle_dim),
                "Shuffle", actionIntent(ACTION_SHUFFLE, 1)).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_prev),
                "Previous", actionIntent(ACTION_PREV, 2)).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                playing ? "Pause" : "Play", actionIntent(ACTION_TOGGLE, 3)).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_next),
                "Next", actionIntent(ACTION_NEXT, 4)).build());
        if (session != null) {
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(1, 2, 3));
        }
        return b.build();
    }

    private void updateMetadata(Song s) {
        if (session == null || s == null) return;
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION,
                        Math.max(s.durationMs, Player.get().duration()));
        Bitmap art = MusicLibrary.cachedArt(s.albumId);
        if (art != null) {
            mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        }
        session.setMetadata(mb.build());
        updatePlaybackState();
    }

    private void updatePlaybackState() {
        if (session == null) return;
        Player p = Player.get();
        boolean playing = p.isPlaying();
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP;
        PlaybackState state = new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        p.position(), p.getSpeed())
                .setBufferedPosition(p.duration() > 0
                        ? p.duration() : PlaybackState.PLAYBACK_POSITION_UNKNOWN)
                .build();
        session.setPlaybackState(state);
    }
}
