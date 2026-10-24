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
    private Map<String, ?> fxSettingsSnapshot;
    private int fxBoostSnapshot = Integer.MIN_VALUE;

    /** Bridges ExoPlayer callbacks back into our single-threaded engine logic. */
    private final class EngineListener implements androidx.media3.common.Player.Listener {
        private final ExoPlayer owner;
        private final Runnable onEnded;
        private final java.util.function.Consumer<
                androidx.media3.common.PlaybackException> onError;
        private final Runnable onReady;

        EngineListener(ExoPlayer owner, Runnable onEnded,
                       java.util.function.Consumer<
                               androidx.media3.common.PlaybackException> onError) {
            this(owner, onEnded, onError, null);
        }

        EngineListener(ExoPlayer owner, Runnable onEnded,
                       java.util.function.Consumer<
                               androidx.media3.common.PlaybackException> onError,
                       Runnable onReady) {
            this.owner = owner;
            this.onEnded = onEnded;
            this.onError = onError;
            this.onReady = onReady;
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (owner != mp && owner != mpNext && owner != mpGapless) return;

            if (fading && (owner == mp || owner == mpNext)) {
                advanceFadeProgress();

                if (owner == mpNext) {
                    fadeAwaitReady = state
                            != androidx.media3.common.Player.STATE_READY
                            || !owner.isPlaying();
                }

                if (state != androidx.media3.common.Player.STATE_READY) {
                    fadeClockRunning = false;
                }
            }

            if (state == androidx.media3.common.Player.STATE_ENDED) {
                if (owner == mpGapless) {
                    cancelGapless();
                    return;
                }

                if (owner == mp && fading) {
                    if (sleepAtTrackEnd) {
                        onCompleted(appCtx);
                        return;
                    }

                    // Keep the incoming engine in its fade role until it can play.
                    boolean incomingReady = mpNext != null
                            && mpNext.getPlaybackState()
                            == androidx.media3.common.Player.STATE_READY
                            && mpNext.isPlaying();

                    if (owner.getPlayWhenReady()
                            && mpNext != null
                            && mpNext.getPlayWhenReady()
                            && incomingReady) {
                        finalizeFade();
                    } else {
                        runFadeRamp();
                        scheduleFadeChecks();
                    }
                    return;
                }

                if (onEnded != null) {
                    try {
                        onEnded.run();
                    } catch (Exception ignored) {
                    }
                }
                return;
            }

            if (state == androidx.media3.common.Player.STATE_READY && onReady != null) {
                try {
                    onReady.run();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (owner != mp && owner != mpNext && owner != mpGapless) return;

            if (fading && (owner == mp || owner == mpNext)) {
                advanceFadeProgress();

                if (owner == mpNext) {
                    fadeAwaitReady = !isPlaying
                            || owner.getPlaybackState()
                            != androidx.media3.common.Player.STATE_READY;
                }

                if (!isPlaying) fadeClockRunning = false;

                runFadeRamp();
            }

            if (owner == mp) {
                try {
                    fireState(owner.getPlayWhenReady());
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void onPlayerError(androidx.media3.common.PlaybackException e) {
            if (owner != mp && owner != mpNext && owner != mpGapless) return;

            if (onError != null) {
                try {
                    onError.accept(e);
                } catch (Exception ignored) {
                }
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
                .setWakeMode(C.WAKE_MODE_LOCAL) // screen-off playback keeps the CPU awake
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
    private double fadeProgress = 0d;
    private long fadeDurationMs = 1L;
    private boolean fadeClockRunning = false;
    private final Handler fadeHandler = new Handler(Looper.getMainLooper());

    // audio fx
    private Equalizer equalizer;
    private LoudnessEnhancer loudness;
    private BassBoost bassBoost;
    private DynamicsProcessing dynProc; // pro parametric EQ (10 bands) + limiter
    private float balL = 1f, balR = 1f; // cached stereo-balance channel factors
    private float volFactor = 1f;      // attenuation from normalization (<= 1)
    private float volFactorNext = 1f;
    private float currentVolume = 1f;
    private float transportLevel = 1f;

    // audio focus / ducking
    private android.media.AudioManager audioManager;
    private android.media.AudioFocusRequest focusRequest;
    private boolean resumeOnGain = false;
    private boolean ducked = false;
    private boolean pendingFocusPlay = false; // start blocked by a focus denial; play on GAIN

    // transport-intent state (v5.6.13 review fixes)
    private boolean pauseRamp = false;        // a pause ramp is currently running
    private boolean enginesReleased = false;  // service died: engines released, queue kept
    private int savedReleasePos = 0;          // position to rebuild at after engine release
    private long lastFadeTickAt = 0;          // last fadeTick time (fade clock freezes paused)
    private boolean fadeAwaitReady = false;   // fade clock starts when the incoming engine is ready
    private final Handler tickHandler = new Handler(Looper.getMainLooper()); // fade ticker only

    private float effVol() {
        return volFactor * (ducked ? 0.25f : 1f);
    }

    private void applyVolumeNow() {
        if (mp == null) return;

        if (fading && mpNext != null) {
            runFadeRamp();
        } else {
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
    private float currentTransportLevel() {
        float fullVolume = effVol() * wakeFactor;

        if (!Float.isFinite(fullVolume) || fullVolume <= 0f) {
            return Math.max(0f, Math.min(1f, transportLevel));
        }

        return Math.max(0f, Math.min(1f, currentVolume / fullVolume));
    }

    private void setPlayerVolume(ExoPlayer m, float v) {
        if (m == null) return;

        float volume = v * wakeFactor;

        if (m == mp && !fading) {
            volume *= transportLevel;
        }

        if (!Float.isFinite(volume)) volume = 0f;
        volume = Math.max(0f, Math.min(1f, volume));

        try {
            m.setVolume(volume);
            if (m == mp) currentVolume = volume;
        } catch (Exception ignored) {
        }
    }

    /** v5.6.6: re-apply the standing volumes (used by the wake-up ramp). */
    private void reapplyVolumes() {
        applyVolumeNow();

        if (mpGapless != null && gapReady) {
            setPlayerVolume(
                    mpGapless,
                    volFactorGap * (ducked ? 0.25f : 1f));
        }
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
                        if (wantsPlay() && appCtx != null) pause(appCtx);
                        break;
                    case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        resumeOnGain = wantsPlay();
                        if (wantsPlay() && appCtx != null) pause(appCtx);
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
                        if (pendingFocusPlay) {
                            pendingFocusPlay = false;
                            if (mp != null && !mp.getPlayWhenReady() && appCtx != null) {
                                rampResume(appCtx);
                            }
                        }
                        if (resumeOnGain) {
                            resumeOnGain = false;
                            // resume() also reverses a pause ramp still in flight
                            if (appCtx != null) resume(appCtx);
                        }
                        break;
                    default:
                        break;
                }
            };

    private boolean ensureFocus() {
        if (appCtx == null) return false;

        try {
            if (audioManager == null) {
                audioManager = (AudioManager)
                        appCtx.getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return false;

            if (focusRequest == null) {
                focusRequest = new android.media.AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(
                                        android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(
                                this::handleAudioFocusChange, fadeHandler)
                        .build();
            }

            return audioManager.requestAudioFocus(focusRequest)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void handleAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS: {
                pause(appCtx);
                resumeOnGain = false;
                pendingFocusPlay = false;
                break;
            }

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: {
                boolean shouldResume = wantsPlay() && !pauseRamp;
                pause(appCtx);
                resumeOnGain = shouldResume;
                break;
            }

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: {
                ducked = true;
                applyVolumeNow();
                break;
            }

            case AudioManager.AUDIOFOCUS_GAIN: {
                ducked = false;
                applyVolumeNow();

                boolean shouldResume = resumeOnGain;
                resumeOnGain = false;

                // Failed requests are not delayed requests. Only an actual
                // transient-loss recovery automatically resumes playback.
                if (shouldResume && appCtx != null) resume(appCtx);
                break;
            }

            default:
                break;
        }
    }

    /** Gives audio focus back (full-stop paths only; track changes keep it). */
    private void abandonFocus() {
        try {
            if (audioManager != null && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
        } catch (Exception ignored) {}
        resumeOnGain = false;
        pendingFocusPlay = false;
        ducked = false;
    }

    // playback speed
    private float speed = 1f;

    public float getSpeed() { return speed; }

    public void setSpeed(float s) {
        if (!Float.isFinite(s) || s <= 0f) return;

        // Account for the preceding interval before installing the new speed.
        advanceFadeProgress();

        speed = s;
        applySpeed(mp);
        applySpeed(mpNext);
        applySpeed(mpGapless);

        lastFadeTickAt = SystemClock.elapsedRealtime();
        if (fading) runFadeRamp();
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

    /** Playback INTENT (playWhenReady): true while playing OR buffering to play.
     *  Transport decisions use this — isPlaying() is false during buffering,
     *  which used to make pause/focus-loss/noisy events no-ops mid-buffer. */
    public boolean wantsPlay() { return mp != null && mp.getPlayWhenReady(); }
    public boolean isShuffle() { return shuffle; }
    public int getRepeat() { return repeat; }
    public void setShuffle(boolean s) {
        if (shuffle == s) return;
        shuffle = s;
        // A gapless preload chosen under the old shuffle state is no longer
        // the right "next" — rebuild it.
        cancelGapless();
        if (mp != null && isPlaying()) maybePreloadGapless();
    }

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
            AudioDeviceInfo[] outs = null;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Prefer the devices actually ROUTED for media playback
                // (a merely-connected BT device must not steal the EQ route).
                try {
                    android.media.AudioAttributes aa = new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
                    Object res = AudioManager.class
                            .getMethod("getDevicesForAttributes", android.media.AudioAttributes.class)
                            .invoke(am, aa);
                    if (res instanceof java.util.List) {
                        java.util.List<?> list = (java.util.List<?>) res;
                        if (!list.isEmpty()) {
                            outs = list.toArray(new AudioDeviceInfo[0]);
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (outs == null) outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
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
        // Same rule as setShuffle: a stale preload must not override the
        // repeat mode the user just chose (repeat-one included).
        cancelGapless();
        if (mp != null && isPlaying()) maybePreloadGapless();
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

    private int errorStreak = 0;

    /** A file that fails to decode used to fake-play in silence (null handler).
     *  Now: tell the user and skip on — giving up after 3 failures in a row.
     *  The skip is identity-checked: if the user already moved to another
     *  track, the stale skip must not fire. A paused (restored) engine that
     *  fails never auto-advances. */
    private void onEngineError(Context ctx) {
        final ExoPlayer failedEngine = mp;
        final Song failedSong = current();

        if (failedEngine == null || failedSong == null) return;

        final Context context = ctx != null
                ? ctx.getApplicationContext() : appCtx;
        final long failedSongId = failedSong.id;
        final int failedRampToken = rampToken;

        if (!failedEngine.getPlayWhenReady() || pauseRamp) {
            finishPlayback();
            return;
        }

        errorStreak++;

        boolean canSkip = errorStreak <= 3 && queue.size() > 1;

        if (context != null) {
            Toast.makeText(
                    context,
                    canSkip
                            ? "Couldn't play that file — skipping"
                            : "Couldn't play that file",
                    Toast.LENGTH_SHORT).show();
        }

        if (!canSkip) {
            finishPlayback();
            return;
        }

        fadeHandler.post(() -> {
            Song currentSong = current();

            if (mp != failedEngine
                    || currentSong == null
                    || currentSong.id != failedSongId
                    || rampToken != failedRampToken) {
                return;
            }

            if (!failedEngine.getPlayWhenReady() || pauseRamp) {
                finishPlayback();
                return;
            }

            if (context == null) {
                finishPlayback();
                return;
            }

            // An errored engine must not become the outgoing side of a manual fade.
            cancelFade();
            cancelGapless();
            failedEngine.pause();

            next(context);
        });
    }

    public void play(Context ctx, List<Song> songs, int startIndex) {
        errorStreak = 0;
        cancelWakeRamp();
        enginesReleased = false;
        if (songs == null || songs.isEmpty()) {
            // An empty/invalid play request must not leave the OLD audio
            // playing under a queue that no longer describes it.
            stopFully(ctx);
            return;
        }
        queue = new ArrayList<>(songs);
        index = Math.max(0, Math.min(startIndex, queue.size() - 1));
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
        if (fading && i == index) {
            // Removing the OUTGOING track mid-fade: promote the incoming
            // engine now so the queue index matches what is audible.
            queue.remove(i);
            if (fadeNextIndex > i) fadeNextIndex--;
            finalizeFade();
            return;
        }
        if (i == index) {
            cancelFade();
            queue.remove(i);
            if (queue.isEmpty()) {
                releasePlayer();
                index = -1;
                fireState(false);
                fireSong(null); // service must wind down like a full stop
                saveSession();
                return;
            }
            if (index >= queue.size()) index = queue.size() - 1;
            startCurrent(ctx);
            return;
        }
        queue.remove(i);
        if (i < index) index--;
        if (fading) {
            // Removing the fade TARGET kills the fade; removing an earlier
            // item just shifts the target's index.
            if (i == fadeNextIndex) cancelFade();
            else if (i < fadeNextIndex) fadeNextIndex--;
        }
        if (gapNextIndex >= 0) {
            if (i == gapNextIndex) {
                cancelGapless();
                if (isPlaying()) maybePreloadGapless();
            } else if (i < gapNextIndex) {
                gapNextIndex--;
            }
        }
        saveSession();
    }

    private void startCurrent(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();

        Song song = current();
        if (song == null || appCtx == null) {
            releasePlayer();
            abandonFocus();
            fireState(false);
            return;
        }

        enginesReleased = false;
        pendingFocusPlay = false;
        resumeOnGain = false;

        ensureService();
        cancelFade();
        releasePlayer();

        boolean focusGranted = ensureFocus();
        pendingFocusPlay = !focusGranted;
        volFactor = combinedAttenuation(song);

        final Context playbackContext = appCtx;

        try {
            ExoPlayer player = makePlayer(0);
            mp = player;

            player.addListener(new EngineListener(
                    player,
                    () -> {
                        if (mp != player) return;
                        if (fading) finalizeFade();
                        else onCompleted(playbackContext);
                    },
                    error -> {
                        if (mp == player) onEngineError(playbackContext);
                    }));

            player.setMediaItem(MediaItem.fromUri(song.uri));
            applySpeed(player);
            setPlayerVolume(player, effVol());
            player.setPlayWhenReady(focusGranted);
            player.prepare();

            if (skipBookmarkOnce) {
                skipBookmarkOnce = false;
            } else {
                maybeResumeBookmark(playbackContext, song);
            }

            attachFxIfReady(player);
            maybePreloadGapless();
        } catch (Exception e) {
            releasePlayer();
            abandonFocus();
            fireSong(song);
            fireState(false);
            saveSession();
            return;
        }

        analyzeCurrentSong();
        fireSong(song);
        fireState(focusGranted);
        scheduleFadeChecks();
        saveSession();
    }

    private void finishPlayback() {
        rampToken++;
        pauseRamp = false;
        pendingFocusPlay = false;
        resumeOnGain = false;

        cancelFade();
        cancelGapless();

        if (mp != null) mp.pause();

        transportLevel = 1f;
        fadeClockRunning = false;
        tickHandler.removeCallbacksAndMessages(null);

        abandonFocus();
        applyVolumeNow();
        saveSession();
        fireState(false);
    }

    private void onCompleted(Context ctx) {
        if (sleepAtTrackEnd) {
            sleepAtTrackEnd = false;
            clearBookmark(current());
            finishPlayback();
            return;
        }

        errorStreak = 0;
        clearBookmark(current());

        if (repeat == 2) {
            cancelGapless();
            skipBookmarkOnce = true;
            startCurrent(ctx);
            return;
        }

        if (gapNextIndex >= 0 && gapNextIndex < queue.size()
                && mpGapless != null) {
            promoteGapless(ctx);
            return;
        }

        if (!queue.isEmpty()
                && (index < queue.size() - 1 || repeat == 1 || shuffle)) {
            autoAdvance(ctx);
        } else {
            finishPlayback();
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
        int lastCand = pick;
        for (int t = 0; t < 12; t++) {
            Song c = queue.get(pick);
            lastCand = pick;
            if (c.durationMs >= thrMs) return pick;
            if (shuffle && n > 1) {
                pick = pickRandomIndex();
            } else if (repeat == 1) {
                pick = (pick + 1) % n;
            } else {
                // Sequential with repeat OFF: never wrap past the queue end
                // (wrapping used to restart the queue from the top).
                if (pick + 1 >= n) break;
                pick = pick + 1;
            }
        }
        return lastCand; // everything short: play the last candidate anyway
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
        ExoPlayer g = null;
        try {
            g = makePlayer(2);
            final Context fctx = appCtx;
            final ExoPlayer fg = g;
            g.addListener(new EngineListener(g,
                    () -> { if (fading) finalizeFade(); else onCompleted(fctx); },
                    e -> {
                        if (fg == mpGapless) cancelGapless();
                        else if (fg == mp) onEngineError(fctx); // promoted player keeps error handling
                    }));
            g.setMediaItem(MediaItem.fromUri(ns.uri));
            applySpeed(g);
            setPlayerVolume(g, volFactorGap);
            g.prepare(); // stays paused (playWhenReady false): promoted on completion
            mpGapless = g;
            gapReady = true;
        } catch (Exception e) {
            if (g != null) { try { g.release(); } catch (Exception ignored) {} }
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
        if (mpGapless == null
                || gapNextIndex < 0
                || gapNextIndex >= queue.size()) {
            cancelGapless();
            return;
        }

        if (sleepAtTrackEnd) {
            sleepAtTrackEnd = false;
            finishPlayback();
            return;
        }

        ExoPlayer outgoing = mp;
        ExoPlayer incoming = mpGapless;
        int incomingIndex = gapNextIndex;

        mpGapless = null;
        gapNextIndex = -1;
        gapReady = false;

        mp = incoming;
        index = incomingIndex;
        balCur = balGap;
        balGap = null;

        rampToken++;
        pauseRamp = false;
        transportLevel = 1f;
        errorStreak = 0;

        Song song = current();
        volFactor = combinedAttenuation(song);

        if (outgoing != null) {
            try {
                outgoing.release();
            } catch (Exception ignored) {
            }
        }

        setPlayerVolume(mp, effVol());
        attachFxIfReady(mp);
        mp.setPlayWhenReady(true);

        if (song != null) {
            if (skipBookmarkOnce) {
                skipBookmarkOnce = false;
            } else {
                maybeResumeBookmark(appCtx, song);
            }

            fireSong(song);
            fireState(mp.getPlayWhenReady());
            analyzeCurrentSong();
            saveSession();
            scheduleFadeChecks();
            maybePreloadGapless();
        }
    }

    private boolean needsEngineRebuild(ExoPlayer player) {
        if (player == null) return true;

        return player.getPlayerError() != null
                || player.getPlaybackState()
                == androidx.media3.common.Player.STATE_ENDED
                || player.getPlaybackState()
                == androidx.media3.common.Player.STATE_IDLE;
    }

    public void toggle(Context ctx) {
        if (ctx == null) return;

        appCtx = ctx.getApplicationContext();

        if (pauseRamp || pendingFocusPlay || mp == null
                || (!fading && needsEngineRebuild(mp))
                || !mp.getPlayWhenReady()) {
            resume(ctx);
        } else {
            pause(ctx);
        }
    }

    public void resume(Context ctx) {
        if (ctx == null) return;

        appCtx = ctx.getApplicationContext();

        if (current() == null) return;

        cancelWakeRamp();
        ensureService();

        if (mp == null) {
            int resumePosition = enginesReleased ? savedReleasePos : 0;
            List<Song> savedQueue = new ArrayList<>(queue);
            int savedIndex = index;

            if (!restore(ctx, savedQueue, savedIndex, resumePosition)) return;

            enginesReleased = false;
        }

        boolean incomingPaused = fading
                && mpNext != null
                && !mpNext.getPlayWhenReady();

        if (pauseRamp || pendingFocusPlay || incomingPaused
                || !mp.getPlayWhenReady()
                || (!fading && needsEngineRebuild(mp))) {
            rampResume(ctx);
        }
    }

    // ----- volume ramp on pause / resume (350 ms, no hard cuts) -----

    private int rampToken = 0;

    private void rampPause() {
        if (mp == null || pauseRamp || !mp.getPlayWhenReady()) return;

        pendingFocusPlay = false;
        pauseRamp = true;

        final ExoPlayer target = mp;
        final float from = currentTransportLevel();
        final long startedAt = SystemClock.elapsedRealtime();
        final int token = ++rampToken;

        transportLevel = from;

        fadeHandler.post(new Runnable() {
            @Override
            public void run() {
                if (token != rampToken || mp != target || fading) return;

                float progress = Math.min(
                        1f,
                        (SystemClock.elapsedRealtime() - startedAt) / 350f);

                transportLevel = from * (1f - progress);

                // The current attenuation and duck multiplier are read each tick.
                setPlayerVolume(target, effVol());

                if (progress >= 1f) {
                    target.pause();
                    pauseRamp = false;
                    fireState(false);
                    saveSession();
                    return;
                }

                fadeHandler.postDelayed(this, 30);
            }
        });
    }

    private void rampResume(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
        if (mp == null || appCtx == null) return;

        ensureService();

        if (!fading && needsEngineRebuild(mp)) {
            skipBookmarkOnce = true;
            startCurrent(appCtx);
            return;
        }

        if (!ensureFocus()) {
            advanceFadeProgress();

            rampToken++;
            pauseRamp = false;
            resumeOnGain = false;
            pendingFocusPlay = true;

            mp.pause();
            if (mpNext != null) mpNext.pause();

            fadeClockRunning = false;
            lastFadeTickAt = SystemClock.elapsedRealtime();
            fireState(false);
            saveSession();
            return;
        }

        pendingFocusPlay = false;
        resumeOnGain = false;
        rampToken++;
        pauseRamp = false;

        if (fading && mpNext != null) {
            transportLevel = 1f;
            lastFadeTickAt = SystemClock.elapsedRealtime();
            fadeClockRunning = false;

            mp.play();
            mpNext.play();

            applyVolumeNow();
            scheduleFadeChecks();
            fireState(true);
            saveSession();
            return;
        }

        final ExoPlayer target = mp;
        final float from = currentTransportLevel();
        final long startedAt = SystemClock.elapsedRealtime();
        final int token = ++rampToken;

        transportLevel = from;
        applyVolumeNow();
        target.play();

        fireState(true);
        scheduleFadeChecks();
        maybePreloadGapless();
        saveSession();

        fadeHandler.post(new Runnable() {
            @Override
            public void run() {
                if (token != rampToken || mp != target || fading) return;

                float progress = Math.min(
                        1f,
                        (SystemClock.elapsedRealtime() - startedAt) / 350f);

                transportLevel = from + (1f - from) * progress;

                // effVol() is evaluated here, rather than captured at ramp start.
                setPlayerVolume(target, effVol());

                if (progress < 1f) {
                    fadeHandler.postDelayed(this, 30);
                }
            }
        });
    }

    public void pause(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();

        pendingFocusPlay = false;
        resumeOnGain = false;

        if (mp == null || pauseRamp) return;

        if (fading) {
            advanceFadeProgress();
            rampToken++;
            pauseRamp = false;

            mp.pause();
            if (mpNext != null) mpNext.pause();

            fadeClockRunning = false;
            lastFadeTickAt = SystemClock.elapsedRealtime();
            applyVolumeNow();
            fireState(false);
            saveSession();
            return;
        }

        if (!mp.getPlayWhenReady()) return;

        rampPause();
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
        pauseRamp = false;
        enginesReleased = false;
        pendingFocusPlay = false;
        sleepAtMs = 0;
        sleepAtTrackEnd = false;
        try { sleepHandler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        releasePlayer();
        abandonFocus();
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
        if (s == null) return;

        if (queue.isEmpty() || index < 0) {
            List<Song> songs = new ArrayList<>();
            songs.add(s);
            play(ctx, songs, 0);
            return;
        }

        // Wrapped and shuffled preloads are invalid too, regardless of their index.
        cancelGapless();

        int insertionIndex = index + 1;
        queue.add(insertionIndex, s);

        if (fading && fadeNextIndex >= insertionIndex) {
            fadeNextIndex++;
        }

        saveSession();

        if (mp != null && isPlaying() && !fading) {
            maybePreloadGapless();
        }
    }

    public void addToQueue(Song s) {
        if (s == null) return;

        boolean wasEmpty = queue.isEmpty();
        cancelGapless();
        queue.add(s);

        if (wasEmpty || index < 0) {
            index = 0;
            savedReleasePos = 0;
            enginesReleased = false;
        }

        saveSession();

        if (mp != null && isPlaying() && !fading) {
            maybePreloadGapless();
        }
    }

    // ----- session restore (queue + position survive app restarts) -----

    private void persistSession(int positionMs, boolean synchronous) {
        if (appCtx == null) return;

        try {
            JSONArray ids = new JSONArray();
            for (Song song : queue) ids.put(song.id);

            SharedPreferences.Editor editor = appCtx
                    .getSharedPreferences("session", Context.MODE_PRIVATE)
                    .edit()
                    .putString("ids", ids.toString())
                    .putInt("index", index)
                    .putInt("pos", Math.max(0, positionMs));

            if (synchronous) {
                editor.commit();
            } else {
                editor.apply();
            }
        } catch (Exception ignored) {
        }
    }

    public void saveSession() {
        int savedPosition = mp != null
                ? position()
                : (enginesReleased && current() != null ? savedReleasePos : 0);

        persistSession(savedPosition, false);
    }

    /** Loads a saved queue paused at the saved position. Returns false if unusable. */
    public boolean restore(Context ctx, List<Song> songs, int idx, int posMs) {
        if (ctx == null || songs == null || songs.isEmpty()) return false;

        appCtx = ctx.getApplicationContext();
        queue = new ArrayList<>(songs);
        index = Math.max(0, Math.min(idx, queue.size() - 1));

        Song song = current();
        if (song == null) return false;

        ensureService();
        cancelFade();
        releasePlayer();
        abandonFocus();

        enginesReleased = false;
        savedReleasePos = Math.max(0, posMs);
        volFactor = combinedAttenuation(song);

        final Context playbackContext = appCtx;

        try {
            ExoPlayer player = makePlayer(0);
            mp = player;

            player.addListener(new EngineListener(
                    player,
                    () -> {
                        if (mp != player) return;
                        if (fading) finalizeFade();
                        else onCompleted(playbackContext);
                    },
                    error -> {
                        if (mp == player) onEngineError(playbackContext);
                    }));

            player.setMediaItem(MediaItem.fromUri(song.uri));
            applySpeed(player);
            setPlayerVolume(player, effVol());
            player.setPlayWhenReady(false);
            player.seekTo(savedReleasePos);
            player.prepare();
            attachFxIfReady(player);
        } catch (Exception e) {
            releasePlayer();
            abandonFocus();
            return false;
        }

        fireSong(song);
        fireState(false);
        saveSession();
        return true;
    }

    // ----- crossfade -----

    private void advanceFadeProgress() {
        long now = SystemClock.elapsedRealtime();
        long elapsed = lastFadeTickAt > 0
                ? Math.max(0L, now - lastFadeTickAt) : 0L;
        lastFadeTickAt = now;

        if (!fading || mp == null || mpNext == null) {
            fadeClockRunning = false;
            return;
        }

        fadeAwaitReady = mpNext.getPlaybackState()
                != androidx.media3.common.Player.STATE_READY
                || !mpNext.isPlaying();

        boolean canAdvance = !pauseRamp
                && mp.getPlayWhenReady()
                && mpNext.getPlayWhenReady()
                && mp.isPlaying()
                && !fadeAwaitReady;

        if (fadeClockRunning && canAdvance && elapsed > 0L) {
            float intervalSpeed = mp.getPlaybackParameters().speed;
            if (!Float.isFinite(intervalSpeed) || intervalSpeed <= 0f) {
                intervalSpeed = 1f;
            }

            fadeProgress = Math.min(
                    1d,
                    fadeProgress
                            + elapsed * (double) intervalSpeed
                            / Math.max(1L, fadeDurationMs));
        }

        fadeClockRunning = canAdvance;
    }

    private void scheduleFadeChecks() {
        // Dedicated ticker handler: wiping fadeHandler here used to also
        // kill the pause/resume ramp runnables that share it.
        tickHandler.removeCallbacksAndMessages(null);
        tickHandler.postDelayed(this::fadeTick, 250);
    }

    private void fadeTick() {
        if (mp == null) return;

        try {
            if (fading) {
                advanceFadeProgress();

                boolean playingIntent = mp.getPlayWhenReady()
                        && mpNext != null
                        && mpNext.getPlayWhenReady()
                        && !pauseRamp;

                boolean incomingReady = mpNext != null
                        && mpNext.getPlaybackState()
                        == androidx.media3.common.Player.STATE_READY
                        && mpNext.isPlaying();

                boolean outgoingEnded = mp.getPlaybackState()
                        == androidx.media3.common.Player.STATE_ENDED;

                if (playingIntent && incomingReady
                        && (fadeProgress >= 1d || outgoingEnded)) {
                    finalizeFade();
                } else {
                    runFadeRamp();
                }
            } else if (mp.isPlaying() && !pauseRamp) {
                maybeBeginFade();
                maybeSaveBookmark();
            }
        } catch (Exception ignored) {
        }

        if (mp != null) tickHandler.postDelayed(this::fadeTick, 200);
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
        // Paused (incl. buffering-paused): a fade would start the next track
        // and could leave it running at zero volume. Fall through to a plain
        // startCurrent, exactly like the non-fade manual-next path.
        if (!mp.getPlayWhenReady()) return false;
        SharedPreferences p = fxPrefs();
        int mode = p != null ? p.getInt(K_XF_MODE, 0) : 0;
        if (mode != 2 && !xfadeApplies()) return false;
        return beginFadeAt(targetIndex);
    }

    private boolean beginFadeAt(int targetIndex) {
        if (mp == null || fading || pauseRamp || !mp.getPlayWhenReady()) {
            return false;
        }
        if (targetIndex < 0 || targetIndex >= queue.size()
                || targetIndex == index || sleepAtTrackEnd) {
            return false;
        }

        cancelGapless();

        // Crossfade volumes must not compete with a resume-ramp runnable.
        rampToken++;
        pauseRamp = false;
        transportLevel = 1f;

        fadeNextIndex = targetIndex;
        Song nextSong = queue.get(targetIndex);
        volFactorNext = combinedAttenuation(nextSong);
        fadeDurationMs = Math.max(1, crossfadeMs());
        fadeProgress = 0d;
        fadeClockRunning = false;
        fadeAwaitReady = true;
        lastFadeTickAt = SystemClock.elapsedRealtime();
        fadeStartAt = lastFadeTickAt;

        try {
            ExoPlayer incoming = makePlayer(1);
            mpNext = incoming;
            fading = true;

            incoming.addListener(new EngineListener(
                    incoming,
                    () -> {
                        if (incoming == mpNext && fading) {
                            finalizeFade();
                            if (mp == incoming) onCompleted(appCtx);
                        } else if (incoming == mp) {
                            onCompleted(appCtx);
                        }
                    },
                    error -> {
                        if (incoming == mpNext) {
                            cancelFade();
                            if (mp != null
                                    && mp.getPlaybackState()
                                    == androidx.media3.common.Player.STATE_ENDED) {
                                finishPlayback();
                            }
                        } else if (incoming == mp) {
                            onEngineError(appCtx);
                        }
                    }));

            incoming.setMediaItem(MediaItem.fromUri(nextSong.uri));
            applySpeed(incoming);
            setPlayerVolume(incoming, 0f);
            incoming.setPlayWhenReady(true);
            incoming.prepare();

            scheduleFadeChecks();
            return true;
        } catch (Exception e) {
            cancelFade();
            return false;
        }
    }

    private void runFadeRamp() {
        if (!fading || mp == null || mpNext == null) return;

        // Rendering volumes never changes progress and never promotes an engine.
        float progress = (float) Math.max(0d, Math.min(1d, fadeProgress));
        float duck = ducked ? 0.25f : 1f;

        boolean incomingUnavailable = mpNext.getPlaybackState()
                != androidx.media3.common.Player.STATE_READY;

        fadeAwaitReady = incomingUnavailable
                || (mpNext.getPlayWhenReady() && !mpNext.isPlaying());

        if (incomingUnavailable) {
            setPlayerVolume(mp, volFactor * duck);
            setPlayerVolume(mpNext, 0f);
            return;
        }

        setPlayerVolume(mp, (1f - progress) * volFactor * duck);
        setPlayerVolume(mpNext, progress * volFactorNext * duck);
    }

    private void finalizeFade() {
        if (!fading || mpNext == null) return;

        if (sleepAtTrackEnd) {
            cancelFade();

            if (mp != null
                    && mp.getPlaybackState()
                    == androidx.media3.common.Player.STATE_ENDED) {
                sleepAtTrackEnd = false;
                finishPlayback();
            }
            return;
        }

        if (fadeNextIndex < 0 || fadeNextIndex >= queue.size()) {
            cancelFade();
            return;
        }

        ExoPlayer outgoing = mp;
        ExoPlayer incoming = mpNext;
        int incomingIndex = fadeNextIndex;
        boolean playIntent = incoming.getPlayWhenReady();

        mp = incoming;
        mpNext = null;
        balCur = balNext;
        balNext = null;

        index = incomingIndex;
        fadeNextIndex = -1;
        fading = false;
        fadeAwaitReady = false;
        fadeClockRunning = false;
        fadeProgress = 0d;
        lastFadeTickAt = 0L;

        rampToken++;
        pauseRamp = false;
        transportLevel = 1f;
        errorStreak = 0;

        Song song = current();
        volFactor = combinedAttenuation(song);

        if (outgoing != null) {
            try {
                outgoing.release();
            } catch (Exception ignored) {
            }
        }

        setPlayerVolume(mp, effVol());
        attachFxIfReady(mp);

        if (song != null) {
            skipBookmarkOnce = false;
            fireSong(song);
            fireState(playIntent);
            analyzeCurrentSong();
            saveSession();

            if (playIntent
                    && mp.getPlaybackState()
                    != androidx.media3.common.Player.STATE_ENDED) {
                maybePreloadGapless();
            }
        }
    }

    private void cancelFade() {
        ExoPlayer incoming = mpNext;

        mpNext = null;
        balNext = null;
        fading = false;
        fadeNextIndex = -1;
        fadeAwaitReady = false;
        fadeClockRunning = false;
        fadeProgress = 0d;
        lastFadeTickAt = 0L;

        if (incoming != null) {
            try {
                incoming.release();
            } catch (Exception ignored) {
            }
        }

        applyVolumeNow();
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
        if (sessionId <= 0 || appCtx == null) return;

        SharedPreferences p = fxPrefs();
        if (p == null) return;

        Map<String, ?> settings = new java.util.HashMap<>(p.getAll());
        int boost = effectiveBoostMb();

        if (sessionId == lastFxSession
                && settings.equals(fxSettingsSnapshot)
                && boost == fxBoostSnapshot) {
            return;
        }

        releaseEffects();

        boolean eqOn = p.getBoolean(K_EQ_ON, false);

        if (p.getBoolean(K_PRO_EQ, true)) {
            DynamicsProcessing candidate = null;
            try {
                candidate = buildDynamics(sessionId, p);
                candidate.setEnabled(eqOn);
                dynProc = candidate;
            } catch (Exception e) {
                if (candidate != null) {
                    try {
                        candidate.release();
                    } catch (Exception ignored) {
                    }
                }
                dynProc = null;
            }
        }

        if (dynProc == null) {
            Equalizer candidate = null;
            try {
                candidate = new Equalizer(0, sessionId);
                int preset = p.getInt(K_EQ_PRESET, -1);

                if (preset >= 0 && preset < candidate.getNumberOfPresets()) {
                    candidate.usePreset((short) preset);
                } else {
                    short[] range = candidate.getBandLevelRange();
                    for (short band = 0; band < candidate.getNumberOfBands(); band++) {
                        int level = p.getInt(K_BAND + band, 0);
                        level = Math.max(range[0], Math.min(range[1], level));
                        candidate.setBandLevel(band, (short) level);
                    }
                }

                candidate.setEnabled(eqOn);
                equalizer = candidate;
            } catch (Exception e) {
                if (candidate != null) {
                    try {
                        candidate.release();
                    } catch (Exception ignored) {
                    }
                }
                equalizer = null;
            }
        }

        LoudnessEnhancer loudnessCandidate = null;
        try {
            loudnessCandidate = new LoudnessEnhancer(sessionId);
            loudnessCandidate.setTargetGain(boost);
            loudnessCandidate.setEnabled(boost > 0);
            loudness = loudnessCandidate;
        } catch (Exception e) {
            if (loudnessCandidate != null) {
                try {
                    loudnessCandidate.release();
                } catch (Exception ignored) {
                }
            }
            loudness = null;
        }

        BassBoost bassCandidate = null;
        try {
            bassCandidate = new BassBoost(0, sessionId);
            int strength = Math.max(0, Math.min(1000, p.getInt(K_BASS, 0)));
            bassCandidate.setStrength((short) strength);
            bassCandidate.setEnabled(eqOn && strength > 0);
            bassBoost = bassCandidate;
        } catch (Exception e) {
            if (bassCandidate != null) {
                try {
                    bassCandidate.release();
                } catch (Exception ignored) {
                }
            }
            bassBoost = null;
        }

        lastFxSession = sessionId;
        fxSettingsSnapshot = settings;
        fxBoostSnapshot = boost;
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
        try {
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
        } catch (Exception e) {
            // A half-configured DynamicsProcessing must not leak: release it
            // before the caller falls back to the legacy EQ.
            try { dp.release(); } catch (Exception ignored) {}
            throw e;
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
        // AutoEq profile preamp (always <= 0 in the AutoEq dataset): additive
        // dB term — applied only while the EQ is on, like the headroom above.
        int aep = p.getInt(K_AUTOEQ_PRE, 0);
        if (aep != 0 && p.getBoolean(K_EQ_ON, false)) db += aep / 100f;
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

        if (balCur != null) balCur.setGains(balL, balR);
        if (balNext != null) balNext.setGains(balL, balR);
        if (balGap != null) balGap.setGains(balL, balR);

        Song song = current();
        if (song != null) {
            volFactor = combinedAttenuation(song);
        }

        if (mpNext != null
                && fadeNextIndex >= 0
                && fadeNextIndex < queue.size()) {
            volFactorNext = combinedAttenuation(queue.get(fadeNextIndex));
        }

        if (mpGapless != null
                && gapNextIndex >= 0
                && gapNextIndex < queue.size()) {
            volFactorGap = combinedAttenuation(queue.get(gapNextIndex));
            setPlayerVolume(
                    mpGapless,
                    volFactorGap * (ducked ? 0.25f : 1f));
        }

        // attachEffects compares the settings snapshot before rebuilding.
        attachFxIfReady(mp);
        applyVolumeNow();
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
        Song currentSong = current();
        if (cur == null || currentSong == null || cur.id != currentSong.id) return;

        refreshAudioFx();
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
            if (mp != null && wantsPlay() && appCtx != null) toggle(appCtx);
            else fireState(false);
        }, minutes * 60_000L);
    }

    public void setSleepAtTrackEnd() {
        sleepHandler.removeCallbacksAndMessages(null);
        sleepAtMs = 0;
        sleepAtTrackEnd = true;

        cancelFade();
        cancelGapless();

        if (mp != null
                && mp.getPlaybackState()
                == androidx.media3.common.Player.STATE_ENDED) {
            sleepAtTrackEnd = false;
            finishPlayback();
        }
    }

    public void cancelSleep() {
        sleepHandler.removeCallbacksAndMessages(null);
        sleepAtMs = 0;
        sleepAtTrackEnd = false;
    }

    /** v5.6.6: the fixed sleep clock fired — same end state as the countdown. */
    public void sleepNow() {
        if (mp != null && wantsPlay() && appCtx != null) toggle(appCtx);
        else fireState(false);
    }

    /** v5.6.6: the wake-up alarm fired — shuffle the whole library in,
     *  starting near-silent and ramping to full volume over ~40 s. */
    public void wakePlay(Context ctx) {
        try {
            List<Song> all = MusicLibrary.loadSongs(ctx);
            if (all == null || all.isEmpty()) {
                // No music to wake up to: fall back to the system alarm tone
                // instead of silently doing nothing.
                soundDefaultAlarm(ctx);
                return;
            }
            List<Song> q = new ArrayList<>(all);
            java.util.Collections.shuffle(q);
            play(ctx, q, 0);
            wakeFactor = 0.05f;
            wakeHandler.removeCallbacks(wakeStep);
            reapplyVolumes();
            wakeHandler.postDelayed(wakeStep, 1000);
        } catch (Exception ignored) {}
    }

    private void soundDefaultAlarm(Context ctx) {
        final android.media.MediaPlayer rp = new android.media.MediaPlayer();
        try {
            android.net.Uri uri = android.media.RingtoneManager
                    .getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = android.media.RingtoneManager
                    .getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
            if (uri == null) { rp.release(); return; }
            rp.setDataSource(ctx, uri);
            rp.setAudioStreamType(android.media.AudioManager.STREAM_ALARM);
            rp.setLooping(true);
            rp.prepare();
            rp.start();
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        try { rp.stop(); } catch (Exception ignored) {}
                        try { rp.release(); } catch (Exception ignored) {}
                    }, 60_000);
        } catch (Exception e) {
            try { rp.release(); } catch (Exception ignored) {}
        }
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

        if (mp != null) {
            savedReleasePos = position();
            enginesReleased = current() != null;
        }

        // Commit while the captured position and queue still describe this session.
        persistSession(savedReleasePos, true);

        rampToken++;
        pauseRamp = false;

        tickHandler.removeCallbacksAndMessages(null);
        wakeHandler.removeCallbacks(wakeStep);

        releasePlayer();
        abandonFocus();
        fireState(false);
    }

    private void releasePlayer() {
        rampToken++;
        pauseRamp = false;

        // Clear roles before release so callbacks cannot act on released engines.
        ExoPlayer current = mp;
        ExoPlayer incoming = mpNext;
        ExoPlayer gapless = mpGapless;

        mp = null;
        mpNext = null;
        mpGapless = null;

        balCur = null;
        balNext = null;
        balGap = null;

        gapNextIndex = -1;
        gapReady = false;
        fadeNextIndex = -1;
        fading = false;
        fadeAwaitReady = false;
        fadeClockRunning = false;
        fadeProgress = 0d;
        lastFadeTickAt = 0L;

        transportLevel = 1f;
        currentVolume = 0f;

        tickHandler.removeCallbacksAndMessages(null);

        for (ExoPlayer player : new ExoPlayer[]{current, incoming, gapless}) {
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignored) {
                }
            }
        }

        releaseEffects();
    }
}
