# SpotPlayer roadmap — approved by Arman, 2026-10-14

Method for EVERY item (Arman's standing rule): decompile/read the app that does it
best → learn its rules → implement ourselves per those rules. Open-source parts may be
bundled as-is. No component transplants from closed apps.

## Done (through v4.7)
- UI/motion — studied: Spotify → v3.x
- Engine rules — studied: Poweramp → v4.6 (gapless, ReplayGain, crossfade modes),
  v4.7 (parametric EQ + auto headroom + balance)

## Queue
1. v5.0 ExoPlayer engine — source: Google Media3 (open source, no decompile needed). BUILDING.
2. AutoEq profiles — source: AutoEq dataset (open) applied per Poweramp's AutoEq rules.
3. Tag editor — source: TagLib (open) + Musicolet's editor flow.
4. Library pack — genres + recently added + star ratings + save queue as playlist +
   stronger sort/search. Sources: Poweramp (ratings/lists), Spotify (library model),
   Musicolet (queue tricks).
5. Listening pack — ReplayGain scanner (ReplayGain spec + Poweramp's application rules),
   per-output EQ assignment (Poweramp), skip short tracks (Musicolet),
   headset button mapping (Musicolet/Poweramp), per-track position bookmark (AntennaPod, open source).
6. Polish pack — waveform seekbar (SoundCloud model; peak computation from decode),
   AMOLED true-black theme, online cover fetch (Cover Art Archive / Deezer APIs),
   lyrics editing, duplicate finder, Android Auto (Media3 session, Google samples).

## Honestly impossible on the current (non-native) engine
Hi-Res direct output, DVC, SoX resampler — Poweramp native territory.
