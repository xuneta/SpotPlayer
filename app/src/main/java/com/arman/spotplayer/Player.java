package com.arman.spotplayer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Random;

/**
 * Playback engine: queue, shuffle/repeat, crossfade, audio FX
 * (equalizer / loudness / bass), loudness normalization and sleep timer.
 * Audio settings live in SharedPreferences "audiofx" and are the single
 * source of truth shared with the EQ UI.
 */
public class Player {

    public interface Listener {
        void onSongChanged(Song song);
        void onPlayStateChanged(boolean playing);
    }

    private static Player instance;

    public static synchronized Player get() {
        if (instance == null) instance = new Player();
        return instance;
    }

    // ----- audiofx prefs keys -----
    public static final String FX = "audiofx";
    public static final String K_EQ_ON = "eq_on";
    public static final String K_EQ_PRESET = "eq_preset"; // -1 = custom
    public static final String K_BAND = "band_";          // + index -> int millibels
    public static final String K_LOUD = "loud_mb";        // user loudness boost, millibels
    public static final String K_BASS = "bass";           // 0..1000
    public static final String K_NORM = "normalize";      // loudness normalization on/off
    public static final String K_XFADE = "crossfade_ms";  // 0 = off
    public static final String K_SMART = "smart_shuffle"; // favorites-weighted shuffle
    public static final String K_SILENCE = "skip_silence"; // trim leading/trailing silence
    public static final String K_SHAKE = "shake_skip";    // shake phone to skip
    public static final String K_RG_MODE = "rg_mode";     // 0=off,1=apply,2=apply+prevent clipping
    public static final String K_RG_SRC = "rg_source";    // 0=track,1=album
    public static final String K_RG_PRE = "rg_preamp";    // dB added when RG tags exist
    public static final String K_RG_DEF = "rg_default";   // dB for songs without RG tags
    public static final String K_GAPLESS = "gapless";      // preload next track, no gap
    public static final String K_XF_MODE = "xfade_mode";   // 0=all,1=shuffle only,2=manual only
    public static final String K_PRO_EQ = "pro_eq";        // parametric 10-band EQ via DynamicsProcessing
    public static final String K_PBAND = "pband_";         // + index 0..9 -> int millibels
    public static final String K_PEQ_PRESET = "peq_preset";// -1 = custom
    public static final String K_HEADROOM = "auto_headroom"; // pull down by strongest EQ boost
    public static final String K_BALANCE = "balance";      // -50..50, 0 = center
    public static final String K_AUTOEQ_NAME = "autoeq_name"; // selected AutoEq profile name (String)
    public static final String K_AUTOEQ_PRE = "autoeq_preamp"; // AutoEq profile preamp, millibels
    public static final String K_SKIP_SHORT = "skip_short_sec"; // skip tracks shorter than this (0=off)
    public static final String K_BTN_DOUBLE = "btn_double";   // headset double-press: 0=next,1=prev,2=nothing
    public static final String K_BTN_TRIPLE = "btn_triple";   // headset triple-press: 0=next,1=prev,2=nothing
    public static final String K_BM_LONG = "bookmark_long";   // resume long tracks where they stopped
    public static final String K_EQ_ROUTE = "eq_route";       // spk | wired | bt (last seen route)

    // Engine: two ExoPlayer instances (current + fade/preload) replacing the
    // old framework MediaPlayers. All queue/fade/gapless logic is unchanged.
    private ExoPlayer mp;
    private ExoPlayer mpNext;
    private BalanceProcessor balCur, balNext, balGap; // per-player channel gains
    private List<Song> queue = new ArrayList<>();
    private List<Song> library = new ArrayList<>(); // full song list (for scans/album lookups)
    private int index = -1;
    private boolean shuffle = false;
    private int repeat = 0; // 0 = off, 1 = all, 2 = one
    private Listener listener;
    private Listener serviceListener;
    private final Random random = new Random();
    private Context appCtx;
    private boolean serviceStarted = false;

    // per-track position bookmarks (AntennaPod rule): long tracks only
    private boolean skipBookmarkOnce = false;
    private long lastBmId = -1;
    private long lastBmSavedAt = 0;

    // per-output EQ (Poweramp rule): speaker / wired / bluetooth snapshots
    private String curRoute = null;
    private AudioDeviceCallback devCb = null;
    private Runnable onRouteChanged = null;

    // gapless state (Poweramp rule: preload the next track)
    private ExoPlayer mpGapless;
    private int gapNextIndex = -1;
    private float volFactorGap = 1f;
    private boolean gapReady = false;

    // ----- ExoPlayer engine plumbing -----

    private int lastFxSession = -1; // audio session the live effects are attached to

    /** Bridges ExoPlayer callbacks back into our single-threaded engine logic. */
    private final class EngineListener implements androidx.media3.common.Player.Listener {
        private final ExoPlayer owner;
        private final Runnable onEnded;
        private final java.util.function.Consumer<
                androidx.media3.common.PlaybackException> onError;

        EngineListener(ExoPlayer owner, Runnable onEnded,
                       java.util.function.Consumer<
                               androidx.media3.common.PlaybackException> onError) {
            this.owner = owner;
            this.onEnded = onEnded;
            this.onError = onError;
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == androidx.media3.common.Player.STATE_ENDED && onEnded != null) {
                try { onEnded.run(); } catch (Exception ignored) {}
            }
        }

        @Override
        public void onPlayerError(androidx.media3.common.PlaybackException e) {
            if (onError != null) {
                try { onError.accept(e); } catch (Exception ignored) {}
            }
        }

        @Override
        public void onAudioSessionIdChanged(int audioSessionId) {
            // Effects live on the CURRENT player only; promotions attach explicitly.
            if (owner == mp) attachFxIfReady(owner);
        }
    }

    /**
     * Builds one engine instance. Slot: 0 = current, 1 = fade/next, 2 = gapless.
     * Wires a BalanceProcessor into the audio chain (ExoPlayer.setVolume is
     * mono, so stereo balance lives in the chain) and keeps OUR audio-focus
     * handling by disabling ExoPlayer's own.
     */
    private ExoPlayer makePlayer(int slot) {
        BalanceProcessor bp = new BalanceProcessor();
        bp.setGains(balL, balR);
        final BalanceProcessor proc = bp;
        RenderersFactory rf = new DefaultRenderersFactory(appCtx) {
            @Override
            protected AudioSink buildAudioSink(android.content.Context context,
                                               boolean enableFloatOutput,
                                               boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setAudioProcessors(new AudioProcessor[]{ proc })
                        .build();
            }
        };
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        ExoPlayer p = new ExoPlayer.Builder(appCtx)
                .setRenderersFactory(rf)
                .setAudioAttributes(aa, false)
                .build();
        if (slot == 0) balCur = bp;
        else if (slot == 1) balNext = bp;
        else balGap = bp;
        try {
            SharedPreferences sp = fxPrefs();
            if (sp != null) p.setSkipSilenceEnabled(sp.getBoolean(K_SILENCE, false));
        } catch (Exception ignored) {}
        return p;
    }

    /** Apply the trim-silence toggle to every live engine (mirrors applyNormalizationNow). */
    public void applySkipSilenceNow() {
        SharedPreferences sp = fxPrefs();
        boolean on = sp != null && sp.getBoolean(K_SILENCE, false);
        for (ExoPlayer e : new ExoPlayer[]{mp, mpNext, mpGapless}) {
            if (e != null) {
                try { e.setSkipSilenceEnabled(on); } catch (Exception ignored) {}
            }
        }
    }

    /** Attach FX to a player's audio session once the sink exists (id != 0). */
    private void attachFxIfReady(ExoPlayer p) {
        if (p == null) return;
        try {
            int sid = p.getAudioSessionId();
            if (sid != 0) attachEffects(sid);
        } catch (Exception ignored) {}
    }

    // crossfade state
    private boolean fading = false;
    private int fadeNextIndex = -1;
    private long fadeStartAt = 0;
    private final Handler fadeHandler = new Handler(Looper.getMainLooper());

    // audio fx
    private Equalizer equalizer;
    private LoudnessEnhancer loudness;
    private BassBoost bassBoost;
    private DynamicsProcessing dynProc; // pro parametric EQ (10 bands) + limiter
    private float balL = 1f, balR = 1f; // cached stereo-balance channel factors
    private float volFactor = 1f;      // attenuation from normalization (<= 1)
    private float volFactorNext = 1f;

    // audio focus / ducking
    private android.media.AudioManager audioManager;
    private android.media.AudioFocusRequest focusRequest;
    private boolean resumeOnGain = false;
    private boolean ducked = false;

    private float effVol() {
        return volFactor * (ducked ? 0.25f : 1f);
    }

    private void applyVolumeNow() {
        if (mp != null && !fading) {
            setPlayerVolume(mp, effVol());
        }
    }

    /** Refresh cached stereo-balance channel factors from K_BALANCE (-50..50). */
    private void refreshBalance() {
        SharedPreferences p = fxPrefs();
        int b = p == null ? 0 : p.getInt(K_BALANCE, 0);
        balL = 1f;
        balR = 1f;
        if (b < 0) balR = 1f + b / 50f;      // negative: attenuate right
        else if (b > 0) balL = 1f - b / 50f; // positive: attenuate left
    }

    /**
     * Apply a mono volume to a player. Stereo balance no longer lives here:
     * ExoPlayer.setVolume() is mono, so balance is baked into each player's
     * BalanceProcessor in the audio chain instead.
     */
    private void setPlayerVolume(ExoPlayer m, float v) {
        if (m == null) return;
        // v5.6.6: wakeFactor scales every engine volume during the wake-up ramp.
        try { m.setVolume(v * wakeFactor); } catch (Exception ignored) {}
    }

    /** v5.6.6: re-apply the standing volumes (used by the wake-up ramp). */
    private void reapplyVolumes() {
        if (mp != null) setPlayerVolume(mp, effVol());
        if (mpGapless != null && gapReady) setPlayerVolume(mpGapless, volFactorGap);
    }

    /** v5.6.6: any manual control cancels the wake-up volume ramp. */
    private void cancelWakeRamp() {
        if (wakeFactor >= 1f) return;
        wakeFactor = 1f;
        wakeHandler.removeCallbacks(wakeStep);
        reapplyVolumes();
    }

    /** Balance-only change: update channel gains, then re-apply mono volumes. */
    public void applyBalanceNow() {
        refreshBalance();
        if (balCur != null) balCur.setGains(balL, balR);
        if (balNext != null) balNext.setGains(balL, balR);
        if (balGap != null) balGap.setGains(balL, balR);
        if (fading) return; // fade timer owns mp/mpNext volumes until the transition ends
        if (mp != null) setPlayerVolume(mp, effVol());
        if (mpGapless != null && gapReady) setPlayerVolume(mpGapless, volFactorGap);
    }

    private final android.media.AudioManager.OnAudioFocusChangeListener focusListener =
            change -> {
                switch (change) {
                    case android.media.AudioManager.AUDIOFOCUS_LOSS:
                        resumeOnGain = false;
                        if (isPlaying() && appCtx != null) pause(appCtx);
                        break;
                    case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        resumeOnGain = isPlaying();
                        if (isPlaying() && appCtx != null) pause(appCtx);
                        break;
                    case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        ducked = true;
                        applyVolumeNow();
                        break;
                    case android.media.AudioManager.AUDIOFOCUS_GAIN:
                        if (ducked) {
                            ducked = false;
                            applyVolumeNow();
                        }
                        if (resumeOnGain) {
                            resumeOnGain = false;
                            if (mp != null && !isPlaying() && appCtx != null) toggle(appCtx);
                        }
                        break;
                    default:
                        break;
                }
            };

    private void ensureFocus() {
        if (appCtx == null) return;
        try {
            if (audioManager == null) {
                audioManager = (android.media.AudioManager)
                        appCtx.getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            if (focusRequest == null) {
                focusRequest = new android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener, fadeHandler)
                        .build();
            }
            audioManager.requestAudioFocus(focusRequest);
        } catch (Exception ignored) {}
    }

    // playback speed
    private float speed = 1f;

    public float getSpeed() { return speed; }

    public void setSpeed(float s) {
        speed = s;
        applySpeed(mp);
        applySpeed(mpNext);
        applySpeed(mpGapless);
    }

    private void applySpeed(ExoPlayer m) {
        if (m == null) return;
        try { m.setPlaybackSpeed(speed); } catch (Exception ignored) {}
    }

    // sleep timer
    private long sleepAtMs = 0;        // elapsedRealtime deadline, 0 = off
    private boolean sleepAtTrackEnd = false;
    private final Handler sleepHandler = new Handler(Looper.getMainLooper());
    // v5.6.6 wake-up alarm: playback starts near-silent, ramps to full volume.
    private float wakeFactor = 1f;
    private final Handler wakeHandler = new Handler(Looper.getMainLooper());
    private final Runnable wakeStep = new Runnable() {
        @Override public void run() {
            wakeFactor = Math.min(1f, wakeFactor + 0.025f);
            reapplyVolumes();
            if (wakeFactor < 1f) wakeHandler.postDelayed(this, 1000);
        }
    };

    public void setListener(Listener l) { this.listener = l; }
    public void setServiceListener(Listener l) { this.serviceListener = l; }

    private void fireSong(Song s) {
        if (listener != null) listener.onSongChanged(s);
        if (serviceListener != null) serviceListener.onSongChanged(s);
    }

    private void fireState(boolean playing) {
        if (listener != null) listener.onPlayStateChanged(playing);
        if (serviceListener != null) serviceListener.onPlayStateChanged(playing);
    }

    public Song current() {
        if (index >= 0 && index < queue.size()) return queue.get(index);
        return null;
    }

    public List<Song> getQueue() { return new ArrayList<>(queue); }
    public int getIndex() { return index; }

    /**
     * Swap a Song object in the queue by id without touching playback state.
     * Used after tag edits so Now Playing shows the new metadata immediately;
     * the player's media item keeps the same Uri, so playback is not disturbed.
     */
    public void replaceSongById(Song replacement) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id == replacement.id) queue.set(i, replacement);
        }
    }
    public boolean isPlaying() { return mp != null && mp.isPlaying(); }
    public boolean isShuffle() { return shuffle; }
    public int getRepeat() { return repeat; }
    public void setShuffle(boolean s) { shuffle = s; }

    // ----- full library (used by the RG scanner for album lookups) -----

    public void setLibrarySongs(List<Song> s) { library = s != null ? s : new ArrayList<>(); }
    public List<Song> getLibrary() { return library; }

    /** Entry point for the Sound panel's "Scan library" RG action. */
    public void startLibraryRgScan(RgScanner.Progress cb) {
        if (appCtx == null || cb == null) return;
        RgScanner.scanLibraryAsync(appCtx, library, cb);
    }

    // ----- per-output EQ (Poweramp rule: EQ snapshots per audio route) -----

    public void setOnEqRouteChanged(Runnable r) { onRouteChanged = r; }

    /** Human label of the current output route. */
    public String routeLabel(Context ctx) {
        String r = curRoute != null ? curRoute : detectRoute(ctx);
        switch (r) {
            case "bt": return "Bluetooth";
            case "wired": return "Wired headset";
            default: return "Speaker";
        }
    }

    /** Detects the current output route: BT A2DP wins, then wired, else speaker. */
    private String detectRoute(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "spk";
            AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo d : outs) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return "bt";
            }
            for (AudioDeviceInfo d : outs) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET
                        || t == AudioDeviceInfo.TYPE_USB_DEVICE) return "wired";
            }
        } catch (Exception ignored) {}
        return "spk";
    }

    /**
     * Called on audio-device changes: saves the EQ bands under the old route,
     * loads the new route's snapshot (or keeps current bands as its initial
     * state when never seen), and refreshes the live effects + UI.
     */
    public void applyOutputRoute(Context ctx) {
        String route = detectRoute(ctx);
        if (curRoute == null) {
            curRoute = route; // first run: nothing to save yet
            return;
        }
        if (curRoute.equals(route)) return;
        saveRouteSnapshot(curRoute);
        curRoute = route;
        loadRouteSnapshot(route);
        SharedPreferences p = fxPrefs();
        if (p != null) p.edit().putString(K_EQ_ROUTE, route).apply();
        refreshAudioFx();
        if (onRouteChanged != null) {
            try { onRouteChanged.run(); } catch (Exception ignored) {}
        }
    }

    private void saveRouteSnapshot(String route) {
        SharedPreferences p = fxPrefs();
        if (p == null) return;
        String pre = "out_" + route + "_";
        SharedPreferences.Editor e = p.edit()
                .putBoolean(pre + "saved", true);
        for (int i = 0; i < 10; i++) e.putInt(pre + "pband_" + i, p.getInt(K_PBAND + i, 0));
        for (int i = 0; i < 5; i++) e.putInt(pre + "band_" + i, p.getInt(K_BAND + i, 0));
        e.putString(pre + "autoeq_name", p.getString(K_AUTOEQ_NAME, null));
        e.putInt(pre + "autoeq_pre", p.getInt(K_AUTOEQ_PRE, 0));
        e.apply();
    }

    private void loadRouteSnapshot(String route) {
        SharedPreferences p = fxPrefs();
        if (p == null) return;
        String pre = "out_" + route + "_";
        if (!p.getBoolean(pre + "saved", false)) return; // never seen: keep current as initial
        SharedPreferences.Editor e = p.edit();
        for (int i = 0; i < 10; i++) e.putInt(K_PBAND + i, p.getInt(pre + "pband_" + i, 0));
        for (int i = 0; i < 5; i++) e.putInt(K_BAND + i, p.getInt(pre + "band_" + i, 0));
        String nm = p.getString(pre + "autoeq_name", null);
        if (nm == null) e.remove(K_AUTOEQ_NAME); else e.putString(K_AUTOEQ_NAME, nm);
        e.putInt(K_AUTOEQ_PRE, p.getInt(pre + "autoeq_pre", 0));
        e.apply();
    }

    private void registerRouteCallback() {
        try {
            if (appCtx == null || devCb != null) return;
            AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            final Context actx = appCtx;
            devCb = new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    applyOutputRoute(actx);
                }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    applyOutputRoute(actx);
                }
            };
            am.registerAudioDeviceCallback(devCb, null);
            applyOutputRoute(actx); // initialize curRoute
        } catch (Exception ignored) {}
    }

    private void unregisterRouteCallback() {
        try {
            if (devCb != null && appCtx != null) {
                AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.unregisterAudioDeviceCallback(devCb);
            }
        } catch (Exception ignored) {}
        devCb = null;
    }
    public int cycleRepeat() {
        repeat = (repeat + 1) % 3;
        return repeat;
    }

    public SharedPreferences fxPrefs() {
        return appCtx != null ? appCtx.getSharedPreferences(FX, Context.MODE_PRIVATE) : null;
    }

    public int crossfadeMs() {
        SharedPreferences p = fxPrefs();
        return p != null ? p.getInt(K_XFADE, 0) : 0;
    }

    // ----- transport -----

    public void play(Context ctx, List<Song> songs, int startIndex) {
        cancelWakeRamp();
        queue = new ArrayList<>(songs);
        index = startIndex;
        startCurrent(ctx);
    }

    public void playAt(Context ctx, int i) {
        if (i < 0 || i >= queue.size()) return;
        cancelFade();
        cancelGapless();
        if (tryManualFade(i)) return;
        index = i;
        startCurrent(ctx);
    }

    public void removeAt(Context ctx, int i) {
        if (i < 0 || i >= queue.size()) return;
        if (i == index) {
            cancelFade();
            queue.remove(i);
            if (queue.isEmpty()) {
                releasePlayer();
                index = -1;
                fireState(false);
                return;
            }
            if (index >= queue.size()) index = queue.size() - 1;
            startCurrent(ctx);
        } else {
            queue.remove(i);
            if (i < index) index--;
            if (gapNextIndex >= 0) {
                if (i == gapNextIndex) {
                    cancelGapless();
                    if (isPlaying()) maybePreloadGapless();
                } else if (i < gapNextIndex) {
                    gapNextIndex--;
                }
            }
        }
    }

    private void startCurrent(Context ctx) {
        Song s = current();
        if (s == null) return;
        appCtx = ctx.getApplicationContext();
        ensureService();
        ensureFocus();
        cancelFade();
        releasePlayer();
        volFactor = normAttenuation(s);
        final Context fctx = ctx;
        try {
            mp = makePlayer(0);
            mp.addListener(new EngineListener(mp,
                    () -> { if (fading) finalizeFade(); else onCompleted(fctx); },
                    null));
            mp.setMediaItem(MediaItem.fromUri(s.uri));
            applySpeed(mp);
            setPlayerVolume(mp, volFactor);
            mp.setPlayWhenReady(true);
            mp.prepare();
            if (skipBookmarkOnce) skipBookmarkOnce = false;
            else maybeResumeBookmark(ctx, s);
            attachFxIfReady(mp);
            maybePreloadGapless();
        } catch (Exception e) {
            releasePlayer();
            return;
        }
        analyzeCurrentSong();
        fireSong(s);
        fireState(true);
        scheduleFadeChecks();
        saveSession();
    }

    private void onCompleted(Context ctx) {
        if (sleepAtTrackEnd) {
            sleepAtTrackEnd = false;
            fireState(false);
            return;
        }
        if (gapNextIndex >= 0 && mpGapless != null) {
            promoteGapless(ctx);
            return;
        }
        if (repeat == 2) {
            skipBookmarkOnce = true; // repeating one: restart at 0, not at the bookmark
            startCurrent(ctx);
            return;
        }
        clearBookmark(current()); // completed (or past 95%): forget the resume point
        if (!queue.isEmpty() && (index < queue.size() - 1 || repeat == 1 || shuffle)) {
            autoAdvance(ctx);
        } else {
            fireState(false);
        }
    }

    /** Auto-advance at track end: never crossfades (gapless promotion or a plain cut). */
    private void autoAdvance(Context ctx) {
        if (queue.isEmpty()) return;
        cancelFade();
        cancelGapless();
        index = pickAutoIndex();
        startCurrent(ctx);
    }

    /**
     * Skip-aware next index for AUTOMATIC paths only (track-end advance,
     * gapless preload, crossfade target, "next up" display). Manual taps
     * (next/prev buttons, playNext) never skip. Never stalls or ends
     * playback early: if every candidate is short, the candidate plays anyway.
     */
    private int pickAutoIndex() {
        if (queue.isEmpty() || index < 0) return Math.max(index, 0);
        int thr = 0;
        SharedPreferences p = fxPrefs();
        if (p != null) thr = p.getInt(K_SKIP_SHORT, 0);
        if (thr <= 0) return logicalNextIndex();
        long thrMs = thr * 1000L;
        int n = queue.size();
        int pick = logicalNextIndex();
        for (int t = 0; t < 12; t++) {
            Song c = queue.get(pick);
            if (c.durationMs >= thrMs) return pick;
            pick = (shuffle && n > 1) ? pickRandomIndex() : (pick + 1) % n;
        }
        return pick; // everything short: play anyway
    }

    // ----- gapless: preloaded next player, zero-gap handoff -----

    private void maybePreloadGapless() {
        cancelGapless();
        SharedPreferences p = fxPrefs();
        if (p == null || !p.getBoolean(K_GAPLESS, true)) return;
        if (mp == null || sleepAtTrackEnd || repeat == 2) return;
        if (xfadeApplies()) return; // crossfade owns the transition instead
        if (!hasLogicalNext()) return;
        gapNextIndex = pickAutoIndex(); // must match what autoAdvance will play
        Song ns = queue.get(gapNextIndex);
        volFactorGap = combinedAttenuation(ns);
        final ExoPlayer g;
        try {
            g = makePlayer(2);
            final Context fctx = appCtx;
            g.addListener(new EngineListener(g,
                    () -> { if (fading) finalizeFade(); else onCompleted(fctx); },
                    e -> { if (g == mpGapless) cancelGapless(); }));
            g.setMediaItem(MediaItem.fromUri(ns.uri));
            applySpeed(g);
            setPlayerVolume(g, volFactorGap);
            g.prepare(); // stays paused (playWhenReady false): promoted on completion
            mpGapless = g;
            gapReady = true;
        } catch (Exception e) {
            mpGapless = null;
            balGap = null;
            gapNextIndex = -1;
        }
    }

    private void cancelGapless() {
        if (mpGapless != null) {
            try { mpGapless.release(); } catch (Exception ignored) {}
            mpGapless = null;
            balGap = null;
        }
        gapNextIndex = -1;
        gapReady = false;
    }

    private void promoteGapless(Context ctx) {
        ExoPlayer old = mp;
        ExoPlayer g = mpGapless;
        mpGapless = null;
        balCur = balGap;
        balGap = null;
        int newIndex = gapNextIndex;
        gapNextIndex = -1;
        gapReady = false;
        mp = g;
        index = newIndex;
        volFactor = volFactorGap;
        if (old != null) {
            try { old.release(); } catch (Exception ignored) {}
        }
        setPlayerVolume(mp, effVol());
        attachFxIfReady(mp);
        try { mp.setPlayWhenReady(true); } catch (Exception ignored) {}
        Song s = current();
        if (s != null) {
            if (skipBookmarkOnce) skipBookmarkOnce = false;
            else maybeResumeBookmark(appCtx, s);
            fireSong(s);
            fireState(true);
            analyzeCurrentSong();
            saveSession();
            maybePreloadGapless();
        }
    }

    public void toggle(Context ctx) {
        cancelWakeRamp();
        appCtx = ctx.getApplicationContext();
        if (mp == null) return;
        if (fading) {
            // crossfade owns the volumes: switch instantly
            try {
                if (mp.isPlaying()) {
                    mp.pause();
                    if (mpNext != null) mpNext.pause();
                    fireState(false);
                } else {
                    mp.play();
                    if (mpNext != null) mpNext.play();
                    fireState(true);
                    scheduleFadeChecks();
                }
            } catch (Exception ignored) {}
            saveSession();
            return;
        }
        if (mp.isPlaying()) rampPause();
        else rampResume(ctx);
    }

    // ----- volume ramp on pause / resume (350 ms, no hard cuts) -----

    private int rampToken = 0;

    private void rampPause() {
        final ExoPlayer target = mp;
        final float from = effVol();
        final long t0 = SystemClock.elapsedRealtime();
        final int token = ++rampToken;
        fadeHandler.post(new Runnable() {
            @Override public void run() {
                if (token != rampToken || mp != target) return;
                float t = (SystemClock.elapsedRealtime() - t0) / 350f;
                try {
                    if (t >= 1f) {
                        target.pause();
                        setPlayerVolume(target, from);
                        fireState(false);
                        saveSession();
                        return;
                    }
                    setPlayerVolume(target, from * (1f - t));
                } catch (Exception ignored) {
                    return;
                }
                fadeHandler.postDelayed(this, 30);
            }
        });
    }

    private void rampResume(Context ctx) {
        try {
            setPlayerVolume(mp, 0f);
            mp.play();
        } catch (Exception ignored) {}
        fireState(true);
        scheduleFadeChecks();
        maybePreloadGapless();
        final ExoPlayer target = mp;
        final float to = effVol();
        final long t0 = SystemClock.elapsedRealtime();
        final int token = ++rampToken;
        fadeHandler.post(new Runnable() {
            @Override public void run() {
                if (token != rampToken || mp != target) return;
                float t = (SystemClock.elapsedRealtime() - t0) / 350f;
                try {
                    if (t >= 1f) {
                        setPlayerVolume(target, to);
                        return;
                    }
                    setPlayerVolume(target, to * t);
                } catch (Exception ignored) {
                    return;
                }
                fadeHandler.postDelayed(this, 30);
            }
        });
    }

    public void pause(Context ctx) {
        if (mp != null && mp.isPlaying()) toggle(ctx);
    }

    /**
     * Full stop (mini-player swipe-down dismiss): playback ends completely —
     * engines released, queue cleared, saved session wiped, so nothing resumes
     * later. Listeners receive onSongChanged(null) + onPlayStateChanged(false).
     */
    public void stopFully(Context ctx) {
        appCtx = ctx.getApplicationContext();
        cancelFade();
        rampToken++;
        sleepAtMs = 0;
        sleepAtTrackEnd = false;
        try { sleepHandler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        releasePlayer();
        queue.clear();
        index = -1;
        fireState(false);
        fireSong(null);
        saveSession();
    }

    public void next(Context ctx) {
        if (queue.isEmpty()) return;
        cancelFade();
        cancelGapless();
        int target;
        if (shuffle && queue.size() > 1) {
            target = pickRandomIndex();
        } else {
            target = (index + 1) % queue.size();
        }
        if (tryManualFade(target)) return;
        index = target;
        startCurrent(ctx);
    }

    public void prev(Context ctx) {
        if (queue.isEmpty()) return;
        if (mp != null) {
            try {
                if (mp.getCurrentPosition() > 3000 && !fading) {
                    mp.seekTo(0);
                    return;
                }
            } catch (Exception ignored) {}
        }
        cancelFade();
        cancelGapless();
        int target = (index - 1 + queue.size()) % queue.size();
        if (tryManualFade(target)) return;
        index = target;
        startCurrent(ctx);
    }

    public int position() {
        try {
            if (mp == null) return 0;
            long p = mp.getCurrentPosition();
            return p < 0 ? 0 : (int) Math.min(p, Integer.MAX_VALUE);
        } catch (Exception e) { return 0; }
    }

    public int duration() {
        try {
            if (mp == null) return 0;
            long d = mp.getDuration();
            if (d == C.TIME_UNSET || d <= 0) return 0;
            return (int) Math.min(d, Integer.MAX_VALUE);
        } catch (Exception e) { return 0; }
    }

    public void seekTo(int ms) {
        if (mp == null) return;
        try {
            if (fading) {
                long dur = mp.getDuration();
                if (dur == C.TIME_UNSET || dur <= 0 || ms < dur - crossfadeMs()) cancelFade();
            }
            mp.seekTo(ms);
        } catch (Exception ignored) {}
        saveSession();
    }

    public void moveQueueItem(int from, int to) {
        if (fading) return;
        if (from < 0 || to < 0 || from >= queue.size() || to >= queue.size()
                || from == to) return;
        cancelGapless();
        Song s = queue.remove(from);
        queue.add(to, s);
        if (index == from) index = to;
        else if (from < index && to >= index) index--;
        else if (from > index && to <= index) index++;
        saveSession();
        if (isPlaying()) maybePreloadGapless();
    }

    // ----- queue editing -----

    public void playNext(Context ctx, Song s) {
        if (queue.isEmpty() || index < 0) {
            List<Song> one = new ArrayList<>();
            one.add(s);
            play(ctx, one, 0);
            return;
        }
        int at = index + 1;
        queue.add(at, s);
        if (fading && fadeNextIndex >= at) fadeNextIndex++;
        if (gapNextIndex >= at) gapNextIndex++;
    }

    public void addToQueue(Song s) {
        if (!queue.isEmpty()) queue.add(s);
    }

    // ----- session restore (queue + position survive app restarts) -----

    public void saveSession() {
        if (appCtx == null) return;
        try {
            JSONArray ids = new JSONArray();
            for (Song s : queue) ids.put(s.id);
            appCtx.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
                    .putString("ids", ids.toString())
                    .putInt("index", index)
                    .putInt("pos", position())
                    .apply();
        } catch (Exception ignored) {}
    }

    /** Loads a saved queue paused at the saved position. Returns false if unusable. */
    public boolean restore(Context ctx, List<Song> songs, int idx, int posMs) {
        if (songs == null || songs.isEmpty()) return false;
        appCtx = ctx.getApplicationContext();
        queue = new ArrayList<>(songs);
        index = Math.max(0, Math.min(idx, queue.size() - 1));
        Song s = current();
        if (s == null) return false;
        ensureService();
        ensureFocus();
        cancelFade();
        releasePlayer();
        volFactor = normAttenuation(s);
        final Context fctx = ctx;
        try {
            mp = makePlayer(0);
            mp.addListener(new EngineListener(mp,
                    () -> { if (fading) finalizeFade(); else onCompleted(fctx); },
                    null));
            mp.setMediaItem(MediaItem.fromUri(s.uri));
            applySpeed(mp);
            setPlayerVolume(mp, volFactor);
            if (posMs > 0) {
                try { mp.seekTo(posMs); } catch (Exception ignored) {}
            }
            mp.prepare(); // stays paused: playWhenReady defaults to false
            attachFxIfReady(mp);
        } catch (Exception e) {
            releasePlayer();
            return false;
        }
        fireSong(s);
        fireState(false);
        return true;
    }

    // ----- crossfade -----

    private void scheduleFadeChecks() {
        fadeHandler.removeCallbacksAndMessages(null);
        fadeHandler.postDelayed(this::fadeTick, 250);
    }

    private void fadeTick() {
        if (mp == null) return;
        try {
            if (fading) {
                runFadeRamp();
            } else if (mp.isPlaying()) {
                maybeBeginFade();
                maybeSaveBookmark();
            }
        } catch (Exception ignored) {}
        if (mp != null) fadeHandler.postDelayed(this::fadeTick, 200);
    }

    // ----- per-track bookmarks (AntennaPod rule): long tracks only -----

    /** Long-track threshold: only tracks above this get a resume bookmark. */
    private static final long BM_MIN_MS = 15 * 60 * 1000L;

    private void maybeResumeBookmark(Context ctx, Song s) {
        try {
            SharedPreferences fx = fxPrefs();
            if (fx == null || !fx.getBoolean(K_BM_LONG, true)) return;
            if (s == null || s.durationMs < BM_MIN_MS) return;
            int pos = appCtx.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
                    .getInt("b_" + s.id, 0);
            if (pos > 5000 && pos < s.durationMs * 0.95) {
                mp.seekTo(pos);
                final Context ac = ctx.getApplicationContext();
                final int fp = pos;
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        Toast.makeText(ac, "Resumed at " + Song.formatTime(fp),
                                Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    /** Saves the position every ~10 s while a long track plays; drops it past 95%. */
    private void maybeSaveBookmark() {
        try {
            if (mp == null || appCtx == null) return;
            SharedPreferences fx = fxPrefs();
            if (fx == null || !fx.getBoolean(K_BM_LONG, true)) return;
            Song s = current();
            if (s == null || s.durationMs < BM_MIN_MS) return;
            int pos = position();
            int dur = duration();
            SharedPreferences bm = appCtx.getSharedPreferences("bookmarks", Context.MODE_PRIVATE);
            if (dur > 0 && pos > dur * 0.95) {
                if (bm.contains("b_" + s.id)) bm.edit().remove("b_" + s.id).apply();
                lastBmId = -1;
                return;
            }
            if (pos < 5000) return;
            long now = SystemClock.elapsedRealtime();
            if (s.id != lastBmId || now - lastBmSavedAt > 10000) {
                bm.edit().putInt("b_" + s.id, pos).apply();
                lastBmId = s.id;
                lastBmSavedAt = now;
            }
        } catch (Exception ignored) {}
    }

    private void clearBookmark(Song s) {
        try {
            if (s != null && appCtx != null) {
                appCtx.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
                        .edit().remove("b_" + s.id).apply();
            }
        } catch (Exception ignored) {}
    }

    private boolean hasLogicalNext() {
        if (queue.size() <= 1) return false;
        return shuffle || index < queue.size() - 1 || repeat == 1;
    }

    private int logicalNextIndex() {
        if (shuffle && queue.size() > 1) {
            return pickRandomIndex();
        }
        return (index + 1) % queue.size();
    }

    /**
     * Random queue index != current. With Smart shuffle on, liked songs and
     * songs played 3+ times get picked with ~55% probability.
     */
    private int pickRandomIndex() {
        if (queue.size() <= 1) return index;
        SharedPreferences p = fxPrefs();
        if (p != null && p.getBoolean(K_SMART, false) && random.nextFloat() < 0.55f) {
            Set<String> liked = appCtx != null
                    ? appCtx.getSharedPreferences("likes", Context.MODE_PRIVATE)
                            .getStringSet("liked_ids", new HashSet<>())
                    : new HashSet<>();
            JSONObject counts = null;
            try {
                String raw = appCtx != null ? appCtx.getSharedPreferences("stats",
                        Context.MODE_PRIVATE).getString("counts", "{}") : "{}";
                counts = new JSONObject(raw);
            } catch (Exception ignored) {}
            List<Integer> fav = new ArrayList<>();
            for (int i = 0; i < queue.size(); i++) {
                if (i == index) continue;
                Song s = queue.get(i);
                boolean f = liked.contains(String.valueOf(s.id));
                if (!f && counts != null) f = counts.optInt(String.valueOf(s.id), 0) >= 3;
                if (f) fav.add(i);
            }
            if (!fav.isEmpty()) return fav.get(random.nextInt(fav.size()));
        }
        int n;
        do { n = random.nextInt(queue.size()); } while (n == index);
        return n;
    }

    /** -1 = no next, -2 = shuffle surprise, otherwise the queue index that plays next. */
    public int peekNextIndex() {
        if (queue.isEmpty() || index < 0) return -1;
        if (repeat == 2) return index;
        if (shuffle) return -2;
        if (index + 1 < queue.size()) return pickAutoIndex(); // skip-aware like autoAdvance
        if (repeat == 1) return 0;
        return -1;
    }

    private void maybeBeginFade() {
        if (!xfadeApplies() || repeat == 2 || sleepAtTrackEnd) return;
        if (!hasLogicalNext()) return;
        long dur = mp.getDuration();
        long pos = mp.getCurrentPosition();
        if (dur == C.TIME_UNSET || dur <= 0 || dur - pos > crossfadeMs()) return;
        beginFadeAt(pickAutoIndex());
    }

    /** Poweramp's crossfade apply modes: 0=all songs, 1=shuffled only, 2=manual only. */
    private boolean xfadeApplies() {
        int xf = crossfadeMs();
        if (xf <= 0) return false;
        SharedPreferences p = fxPrefs();
        int mode = p != null ? p.getInt(K_XF_MODE, 0) : 0;
        if (mode == 0) return true;
        if (mode == 1) return shuffle;
        return false; // mode 2: auto-advance uses the gapless path instead
    }

    /** Manual track change with crossfade. Returns true if the fade started.
     * Mode 2 (manual only) always fades on manual changes; modes 0/1 follow
     * the auto rule (mode 1: only when shuffle is on). */
    private boolean tryManualFade(int targetIndex) {
        if (crossfadeMs() <= 0) return false;
        if (targetIndex < 0 || targetIndex >= queue.size() || targetIndex == index) return false;
        if (mp == null || fading) return false;
        SharedPreferences p = fxPrefs();
        int mode = p != null ? p.getInt(K_XF_MODE, 0) : 0;
        if (mode != 2 && !xfadeApplies()) return false;
        return beginFadeAt(targetIndex);
    }

    private boolean beginFadeAt(int targetIndex) {
        if (mp == null || fading) return false;
        cancelGapless(); // the crossfade owns this transition
        fadeNextIndex = targetIndex;
        Song nextSong = queue.get(fadeNextIndex);
        volFactorNext = combinedAttenuation(nextSong);
        try {
            mpNext = makePlayer(1);
            final Context fctx = appCtx;
            mpNext.addListener(new EngineListener(mpNext,
                    () -> onCompleted(fctx), null));
            mpNext.setMediaItem(MediaItem.fromUri(nextSong.uri));
            applySpeed(mpNext);
            setPlayerVolume(mpNext, 0f);
            mpNext.setPlayWhenReady(true);
            mpNext.prepare();
        } catch (Exception e) {
            releaseNext();
            return false;
        }
        fading = true;
        fadeStartAt = SystemClock.elapsedRealtime();
        return true;
    }

    private void runFadeRamp() {
        int xf = crossfadeMs();
        if (xf <= 0 || mp == null || mpNext == null) {
            cancelFade();
            return;
        }
        float t = (SystemClock.elapsedRealtime() - fadeStartAt) / (float) xf;
        if (t >= 1f) {
            finalizeFade();
            return;
        }
        t = Math.max(0f, t);
        setPlayerVolume(mp, (1f - t) * volFactor);
        setPlayerVolume(mpNext, t * volFactorNext);
    }

    private void finalizeFade() {
        if (mpNext == null) {
            fading = false;
            return;
        }
        ExoPlayer old = mp;
        mp = mpNext;
        mpNext = null;
        balCur = balNext;
        balNext = null;
        index = fadeNextIndex;
        volFactor = volFactorNext;
        fading = false;
        if (old != null) {
            try { old.release(); } catch (Exception ignored) {}
        }
        setPlayerVolume(mp, effVol());
        attachFxIfReady(mp);
        Song s = current();
        if (s != null) {
            if (skipBookmarkOnce) skipBookmarkOnce = false;
            else maybeResumeBookmark(appCtx, s);
            fireSong(s);
            fireState(true);
            analyzeCurrentSong();
            saveSession();
            maybePreloadGapless();
        }
    }

    private void cancelFade() {
        if (mpNext != null) releaseNext();
        fading = false;
        if (mp != null) {
            setPlayerVolume(mp, effVol());
        }
    }

    private void releaseNext() {
        if (mpNext != null) {
            try { mpNext.release(); } catch (Exception ignored) {}
            mpNext = null;
            balNext = null;
        }
    }

    // ----- audio effects -----

    // ISO centers (Hz) of the Pro EQ's 10 peaking bands
    private static final int[] PRO_ISO = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    private void attachEffects(int sessionId) {
        if (sessionId == 0 || sessionId == lastFxSession) return;
        lastFxSession = sessionId;
        releaseEffects();
        if (appCtx == null) return;
        SharedPreferences p = fxPrefs();
        if (p == null) return;
        boolean eqOn = p.getBoolean(K_EQ_ON, false);
        if (p.getBoolean(K_PRO_EQ, true)) {
            try {
                dynProc = buildDynamics(sessionId, p);
                dynProc.setEnabled(eqOn);
            } catch (Exception e) {
                dynProc = null; // fall back to the legacy equalizer silently
            }
        }
        if (dynProc == null) {
            try {
                equalizer = new Equalizer(0, sessionId);
                int preset = p.getInt(K_EQ_PRESET, -1);
                if (preset >= 0 && preset < equalizer.getNumberOfPresets()) {
                    equalizer.usePreset((short) preset);
                } else {
                    for (short b = 0; b < equalizer.getNumberOfBands(); b++) {
                        int lvl = p.getInt(K_BAND + b, Integer.MIN_VALUE);
                        if (lvl != Integer.MIN_VALUE) {
                            equalizer.setBandLevel(b, (short) lvl);
                        }
                    }
                }
                equalizer.setEnabled(eqOn);
            } catch (Exception e) {
                equalizer = null;
            }
        }
        try {
            loudness = new LoudnessEnhancer(sessionId);
            loudness.setTargetGain(effectiveBoostMb());
            loudness.setEnabled(eqOn);
        } catch (Exception e) {
            loudness = null;
        }
        try {
            bassBoost = new BassBoost(0, sessionId);
            bassBoost.setStrength((short) p.getInt(K_BASS, 0));
            bassBoost.setEnabled(eqOn && p.getInt(K_BASS, 0) > 0);
        } catch (Exception e) {
            bassBoost = null;
        }
    }

    /**
     * Parametric 10-band EQ + limiter safety net, driven by pband_0..9 prefs.
     * Uses the platform's DynamicsProcessing API shape; throws on any problem
     * so the caller can fall back to the legacy EQ.
     */
    private DynamicsProcessing buildDynamics(int sessionId, SharedPreferences p) throws Exception {
        // NOTE: VARIANT_ALL_CHANNELS (-1) is absent from this build's android.jar
        // stubs; -1 is the documented AOSP value and applies the config to all channels.
        // The EqBand/Limiter API shape also differs between releases (API 28 vs 29+),
        // so the newer shape is probed reflectively first.
        DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
                -1,
                10, true,   // 10 pre-EQ bands, stage in use
                0, false,   // multiband EQ off
                0, false,   // post-EQ off
                0, false);  // multiband compressor off
        boolean newApi = false;
        try {
            DynamicsProcessing.EqBand.class.getConstructor(
                    boolean.class, float.class, float.class, float.class);
            newApi = true;
        } catch (NoSuchMethodException ignored) {}
        if (newApi) {
            b.getClass().getMethod("setLimiterEnabled", boolean.class).invoke(b, true);
        } else {
            b.setPreEqAllChannelsTo(new DynamicsProcessing.Eq(true, true, 10));
            b.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(
                    true, true, 0, 1f, 100f, 10f, -1f, 0f));
        }
        DynamicsProcessing dp = new DynamicsProcessing(0, sessionId, b.build());
        if (newApi) {
            Object limB = Class
                    .forName("android.media.audiofx.DynamicsProcessing$LimiterBase$Config$Builder")
                    .getConstructor().newInstance();
            Class<?> lb = limB.getClass();
            lb.getMethod("setEnabled", boolean.class).invoke(limB, true);
            lb.getMethod("setAttackTime", float.class).invoke(limB, 1f);
            lb.getMethod("setReleaseTime", float.class).invoke(limB, 100f);
            lb.getMethod("setRatio", float.class).invoke(limB, 10f);
            lb.getMethod("setThreshold", float.class).invoke(limB, -1f);
            lb.getMethod("setPostGain", float.class).invoke(limB, 0f);
            Object limCfg = lb.getMethod("build").invoke(limB);
            DynamicsProcessing.class.getMethod("setLimiter", int.class, limCfg.getClass())
                    .invoke(dp, 0, limCfg);
        }
        for (int i = 0; i < 10; i++) {
            float gdb = p.getInt(K_PBAND + i, 0) / 100f;
            dp.setPreEqBandAllChannelsTo(i, makeEqBand(newApi, true, PRO_ISO[i], gdb));
        }
        return dp;
    }

    private static DynamicsProcessing.EqBand makeEqBand(
            boolean newApi, boolean enabled, float cutoff, float gainDb) throws Exception {
        if (newApi) {
            return (DynamicsProcessing.EqBand) DynamicsProcessing.EqBand.class
                    .getConstructor(boolean.class, float.class, float.class, float.class)
                    .newInstance(enabled, cutoff, 1.0f, gainDb);
        }
        return new DynamicsProcessing.EqBand(enabled, cutoff, gainDb);
    }

    /** Positive loudness gain (millibels) from user boost + RG/normalizer boost part. */
    private int effectiveBoostMb() {
        SharedPreferences p = fxPrefs();
        if (p == null) return 0;
        int gain = p.getInt(K_LOUD, 0);
        Song s = current();
        if (s != null && appCtx != null) {
            if (p.getInt(K_RG_MODE, 0) > 0) {
                float rg = ReplayGain.applyDb(appCtx, p, s.id);
                if (!Float.isNaN(rg) && rg > 0) gain += (int) (rg * 100);
            } else if (p.getBoolean(K_NORM, false)) {
                float g = Normalizer.cachedGainDb(appCtx, s.id);
                if (g > 0) gain += (int) (g * 100);
            }
        }
        return Math.max(0, Math.min(2400, gain));
    }

    /** Volume attenuation (<=1) for evening-out: ReplayGain when enabled, else the RMS normalizer. */
    private float normAttenuation(Song s) {
        return combinedAttenuation(s);
    }

    private float combinedAttenuation(Song s) {
        SharedPreferences p = fxPrefs();
        if (p == null || s == null || appCtx == null) return 1f;
        int rgm = p.getInt(K_RG_MODE, 0);
        float db;
        if (rgm > 0) {
            float rg = ReplayGain.applyDb(appCtx, p, s.id);
            db = Float.isNaN(rg) ? p.getInt(K_RG_DEF, 0) : rg;
        } else if (p.getBoolean(K_NORM, false)) {
            float g = Normalizer.cachedGainDb(appCtx, s.id);
            db = g >= 0 ? 0 : g; // boosts go through the LoudnessEnhancer instead
        } else {
            db = 0;
        }
        // Auto headroom (Poweramp rule): pull down by the strongest positive EQ
        // boost so boosted EQ can't push the signal into clipping. Never adds gain.
        if (p.getBoolean(K_HEADROOM, true)) {
            int hb = maxEqBoostMb(p);
            if (hb > 0) db -= hb / 100f;
        }
        // AutoEq profile preamp (always <= 0 in the AutoEq dataset): additive dB term.
        int aep = p.getInt(K_AUTOEQ_PRE, 0);
        if (aep != 0) db += aep / 100f;
        if (db >= 0) return 1f;
        return (float) Math.pow(10.0, db / 20.0);
    }

    /** Largest positive EQ band gain in millibels (0 when EQ off or nothing boosted). */
    private int maxEqBoostMb(SharedPreferences p) {
        if (!p.getBoolean(K_EQ_ON, false)) return 0;
        boolean pro = p.getBoolean(K_PRO_EQ, true);
        String key = pro ? K_PBAND : K_BAND;
        int n = pro ? 10 : 5;
        int max = 0;
        for (int i = 0; i < n; i++) max = Math.max(max, p.getInt(key + i, 0));
        return max;
    }

    /** Re-apply saved FX settings to the live effects (called by the EQ UI). */
    public void refreshAudioFx() {
        refreshBalance();
        if (mp == null) return;
        attachFxIfReady(mp);
        // volume may change with normalization toggles/boosts
        Song s = current();
        if (!fading && s != null) {
            volFactor = normAttenuation(s);
            setPlayerVolume(mp, effVol());
        }
    }

    private void releaseEffects() {
        try { if (dynProc != null) dynProc.release(); } catch (Exception ignored) {}
        try { if (equalizer != null) equalizer.release(); } catch (Exception ignored) {}
        try { if (loudness != null) loudness.release(); } catch (Exception ignored) {}
        try { if (bassBoost != null) bassBoost.release(); } catch (Exception ignored) {}
        dynProc = null;
        equalizer = null;
        loudness = null;
        bassBoost = null;
        lastFxSession = -1;
    }

    private void analyzeCurrentSong() {
        SharedPreferences p = fxPrefs();
        if (p == null || appCtx == null) return;
        Song s = current();
        if (s == null) return;
        boolean doRg = p.getInt(K_RG_MODE, 0) > 0;
        boolean doNorm = p.getBoolean(K_NORM, false);
        if (!doRg && !doNorm) return;
        if (doRg) {
            ReplayGain.ensureAsync(appCtx, s, () -> {
                Song cur = current();
                if (cur == null || cur.id != s.id) return;
                if (!ReplayGain.hasAnyValue(appCtx, s.id)) {
                    // No tags: measure the file's loudness once, then apply
                    // exactly like tag values.
                    RgScanner.scanSongAsync(appCtx, s, library, () -> {
                        Song c2 = current();
                        if (c2 == null || c2.id != s.id) return;
                        applyCombinedGainLive(c2);
                    });
                    return;
                }
                applyCombinedGainLive(cur);
                if (gapNextIndex >= 0 && gapNextIndex < queue.size()
                        && queue.get(gapNextIndex).id == s.id && mpGapless != null) {
                    volFactorGap = combinedAttenuation(s);
                    setPlayerVolume(mpGapless, volFactorGap);
                }
            });
        }
        if (doNorm) {
            Normalizer.analyzeAsync(appCtx, s, gainDb -> {
                // if the measured song is still playing, apply the new gain live
                Song cur = current();
                if (cur != null && cur.id == s.id) applyCombinedGainLive(cur);
            });
        }
    }

    private void applyCombinedGainLive(Song cur) {
        if (mp != null && !fading) {
            volFactor = combinedAttenuation(cur);
            setPlayerVolume(mp, effVol());
            if (loudness != null) {
                try {
                    loudness.setTargetGain(effectiveBoostMb());
                } catch (Exception ignored) {}
            }
        }
    }

    /** Called when ReplayGain settings change: re-parse is cached, just re-apply. */
    public void applyRgNow() {
        refreshAudioFx();
        analyzeCurrentSong();
    }

    /** Gapless toggle from the Sound panel: drop the preloaded player, or arm one now. */
    public void setGaplessEnabled(boolean on) {
        SharedPreferences p = fxPrefs();
        if (p != null) p.edit().putBoolean(K_GAPLESS, on).apply();
        if (on) {
            if (mp != null && mp.isPlaying()) maybePreloadGapless();
        } else {
            cancelGapless();
        }
    }

    /** Called when normalization is toggled mid-song: analyze + apply now. */
    public void applyNormalizationNow() {
        refreshAudioFx();
        analyzeCurrentSong();
    }

    // ----- sleep timer -----

    public void setSleepInMinutes(int minutes) {
        sleepAtTrackEnd = false;
        sleepHandler.removeCallbacksAndMessages(null);
        if (minutes <= 0) {
            sleepAtMs = 0;
            return;
        }
        sleepAtMs = SystemClock.elapsedRealtime() + minutes * 60_000L;
        sleepHandler.postDelayed(() -> {
            sleepAtMs = 0;
            if (mp != null && mp.isPlaying() && appCtx != null) toggle(appCtx);
            else fireState(false);
        }, minutes * 60_000L);
    }

    public void setSleepAtTrackEnd() {
        sleepHandler.removeCallbacksAndMessages(null);
        sleepAtMs = 0;
        sleepAtTrackEnd = true;
    }

    public void cancelSleep() {
        sleepHandler.removeCallbacksAndMessages(null);
        sleepAtMs = 0;
        sleepAtTrackEnd = false;
    }

    /** v5.6.6: the fixed sleep clock fired — same end state as the countdown. */
    public void sleepNow() {
        if (mp != null && mp.isPlaying() && appCtx != null) toggle(appCtx);
        else fireState(false);
    }

    /** v5.6.6: the wake-up alarm fired — shuffle the whole library in,
     *  starting near-silent and ramping to full volume over ~40 s. */
    public void wakePlay(Context ctx) {
        try {
            List<Song> all = MusicLibrary.loadSongs(ctx);
            if (all == null || all.isEmpty()) return;
            List<Song> q = new ArrayList<>(all);
            java.util.Collections.shuffle(q);
            play(ctx, q, 0);
            wakeFactor = 0.05f;
            wakeHandler.removeCallbacks(wakeStep);
            reapplyVolumes();
            wakeHandler.postDelayed(wakeStep, 1000);
        } catch (Exception ignored) {}
    }

    public boolean isSleepActive() { return sleepAtMs > 0 || sleepAtTrackEnd; }
    public boolean isSleepAtTrackEnd() { return sleepAtTrackEnd; }

    public long sleepRemainingMs() {
        return sleepAtMs > 0 ? Math.max(0, sleepAtMs - SystemClock.elapsedRealtime()) : 0;
    }

    // ----- service -----

    private void ensureService() {
        if (serviceStarted || appCtx == null) return;
        try {
            Intent i = new Intent(appCtx, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) appCtx.startForegroundService(i);
            else appCtx.startService(i);
            serviceStarted = true;
            registerRouteCallback();
        } catch (Exception ignored) {
            // background-start restrictions etc.: playback continues without the service
        }
    }

    public void onServiceDestroyed() {
        unregisterRouteCallback();
        serviceStarted = false;
    }

    private void releasePlayer() {
        cancelGapless();
        releaseNext();
        fading = false;
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
            balCur = null;
        }
        releaseEffects();
    }
}
