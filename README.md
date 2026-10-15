# SpotPlayer

A Spotify-style Android music player for **your own local music library** — no accounts, no streaming, no fake tracks. SpotPlayer reads the audio files already on your device (via MediaStore) and plays them with a serious playback engine and a familiar dark UI.

> 🇮🇷 Persian README: [README.fa.md](README.fa.md)

> 📦 **Featherweight:** the entire app ships as a **~760 KB APK** — under 1 MB, with the full feature set below.

## Features

- **Library** — Songs, Artists, Albums, Genres, Recently Added, Top Rated, Most Played, Daily Mix, Liked Songs, playlists (plus *save queue as playlist*), grouped search, star ratings, duplicate finder
- **Playback engine** — Media3 / ExoPlayer with three engines for crossfade and gapless playback, smart (favorites-weighted) shuffle, repeat modes, speed control, sleep timer, A–B repeat, shake-to-skip, **trim silence** (v5.6), mini-player swipe gestures — drag up to expand, drag down to stop completely (v5.6.3)
- **Sound** — 10-band parametric EQ (DynamicsProcessing) with limiter and auto headroom, legacy EQ fallback, AutoEq headphone profiles, ReplayGain (tags first, built-in scanner second), loudness normalization, stereo balance, per-output EQ snapshots
- **Lyrics** — embedded tags and sidecar `.lrc` files, synced highlighting, online fallback via the free LRCLIB API with local caching, built-in lyrics editor
- **Extras** — waveform seekbar, AMOLED pure-black theme, listening stats, car mode, home-screen widget, headset button controls, bookmarks for long tracks, built-in crash reporter (v5.6.1)

## License

MIT — see [LICENSE](LICENSE).

## Screenshots

Install the APK and explore — the UI follows Spotify's layout and color language (dark `#121212`, accent green `#1ABC54`).

## Building

SpotPlayer is built **without Gradle**, with a manual pipeline:

```bash
export JAVA_HOME=/path/to/jdk21
bash build_manual.sh
```

The script runs aapt2 → javac (`--release 17`) → R8/D8 → zipalign → apksigner and produces `SpotPlayer.apk`.

Requirements: JDK 21, Android SDK platform 34 + build-tools 34.0.0. The Media3 AARs live in `libs-media3/`.

## Tech

- Plain Java (Android framework) + Media3 ExoPlayer 1.11.1 — no AndroidX app code, no Kotlin
- Package: `com.arman.spotplayer` · minSdk 26 · targetSdk 34
- Current version: **5.6.3** (versionCode 32)

## License

Personal project — source is published for learning and inspection.
