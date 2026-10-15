# SpotPlayer motion spec (reverse-engineered from public design systems)

Sources: Google Material 3 motion tokens (public spec, m3.material.io) and
documented Spotify interaction patterns. Spotify/YouTube Music build on these
same published systems (Encore / Material), so this is how their feel is made.

## Duration tokens (Material 3, exact)
- short1-4: 50 / 100 / 150 / 200 ms  -> micro feedback, selection controls (200)
- medium1-4: 250 / 300 / 350 / 400 ms -> standard transitions, sheets (400)
- long1-4: 450 / 500 / 550 / 600 ms  -> card expanding to full screen (500)
- extra-long: 700-1000 ms -> ambient only

## Easing tokens
- emphasized:          cubic-bezier(0.2, 0, 0, 1)   (entrances, big moves)
- emphasized-decel:    cubic-bezier(0.05, 0.7, 0.1, 1)
- emphasized-accel:    cubic-bezier(0.3, 0, 1, 1)   (exits)
- standard:            cubic-bezier(0.2, 0, 0, 1)

## Springs (M3 motion physics, where the industry is moving)
- fast ~350 ms, medium ~500 ms, slow ~650 ms, damping ratio ~0.9

## Patterns taken from Spotify
- Mini player expands to full Now Playing (container transform, long2 = 500 ms)
- Swipe album art: left = next track, right = previous
- Pull/swipe down on Now Playing to dismiss back to the mini player
- Scrubber thumb grows while dragging
- Light haptic tick on transport controls
- Page/tab change: fade-through (old fades out + scales to 0.96, new fades in)

## Deliberate deviation
Press feedback runs slower/softer than stock tokens: 250 ms in, 500 ms out,
with a soft halo instead of the Android ripple.
