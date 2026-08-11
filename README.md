# Deep Sky Camera

A camera app for photographing the night sky with an Android phone. You pick how
long you want to gather light for — 10 seconds, 30 seconds, or keep going until
you stop it — and the app works out every camera setting for you.

## The one thing worth understanding

Phone camera hardware will not open the shutter for thirty seconds. On the Galaxy
A52s this app was built for, the public camera API caps a single exposure at
**0.45 s** on the main camera and **0.67 s** on the ultra-wide. (Samsung's own
Pro mode reaches 10–30 s through private vendor tags that third-party apps cannot
reliably use.)

So the modes in this app are **total integration time**, not shutter speed.
Choosing "30 sec" takes 67 exposures of 0.45 s each and adds them together into
one photograph.

This is not a workaround. It is how astrophotography has always been done, and it
is better than one long exposure would be:

- Adding N frames grows the star signal by N but the random sensor noise only by
  √N, so the picture gets **√N times cleaner** — about 8× less noise over 30 s.
- Each frame stays short, so stars stay **points instead of streaks** as the sky
  rotates.
- Between frames the app tracks the drift and shifts each one back into place, so
  a capture can run for minutes without smearing.

The app reads your hardware's real limits at startup rather than assuming them.
On a phone that does honour longer exposures, it will use them automatically, and
fall back to the star-trail limit (the NPF rule) so it never streaks the stars
just because the sensor would allow it.

## What it does for you automatically

You choose a duration. The app decides:

| Setting | How it is chosen |
| --- | --- |
| Shutter per frame | The longest the sensor allows, or the longest before stars trail — whichever is shorter |
| ISO | Metered off the actual sky, then rebalanced for the shutter length it settled on |
| Frame count | However many it takes to reach the duration you asked for |
| Focus | Fixed at infinity — autofocus cannot lock onto a star and will ruin the frame |
| White balance | Locked, so colour cannot drift between frames being added together |
| Stabilisation | Off — it hunts on a tripod and shifts the field between frames |
| Noise reduction, sharpening | Off — both erase faint stars, which look exactly like the noise they remove |
| Black level | Locked, so the floor cannot move underneath a stack summing it 67 times |

Everything it decided is shown on screen before you shoot, in plain terms.

## Using it

1. Prop the phone against something solid, or use a tripod. Any movement during a
   capture is the one thing stacking cannot fix.
2. Pick a camera (Main or Ultra-wide) and a duration.
3. Check focus — it starts at infinity, which is usually right. The slider is
   there for the times it isn't.
4. Tap **SHOOT**. For Indefinite, tap **STOP** when you have had enough; the
   frames already gathered are a finished photograph, nothing is discarded.

Photos are saved to `Pictures/DeepSkyCamera` and appear in your gallery. The
filename records the recipe — `DSC_20260812_224310_30s_67f_ISO3200.jpg`.

**Auto stretch** is on by default. A stacked night sky occupies a narrow, dark
band of the histogram and looks like a black rectangle until it is pulled apart.
Turn it off in Settings if you would rather do that yourself later.

## Installing and updating

There is no app store involved.

**First install:** open the [latest release](https://github.com/Scottys3DPrints/Deep-Sky-Camera/releases/latest)
on the phone, tap the `.apk`, and allow your browser to install unknown apps.

**Afterwards:** open the app, go to **Settings → Check for updates**, and tap
install. It replaces itself in place and keeps your settings — you never
uninstall and never visit GitHub again.

That works because every release is signed with the same key, so Android treats a
new build as an upgrade rather than a different app. The app polls a small JSON
manifest published with each release at a `releases/latest/download/` address,
which never changes no matter how many versions ship. The download is checked
against a SHA-256 in that manifest and refused if it does not match.

## Building it yourself

```bash
./gradlew :app:assembleRelease
```

Signing comes from `keystore.properties` beside this file locally, or from the
`DSC_KEYSTORE_*` secrets in CI. Both must resolve to the same key.

**Back up `deepsky-release.jks` and `keystore.properties` somewhere safe.**
Without them you can never update an installed copy of the app in place — the
only way forward would be uninstalling it.

`update.bat` builds and installs straight to a phone over USB.
`release.bat 0.2.0` tags a version and pushes it, which is what tells CI to build
and publish a release the app can then find on its own.

## Honest limits

- Alignment corrects **translation only**. It cannot correct the field rotation
  that shows up over very long captures, so an hour-long indefinite stack will
  still smear at the frame edges.
- It aligns on the centroid of everything bright. A frame with no stars in it —
  cloud, or a lens cap — is stacked unshifted rather than guessed at.
- Output is JPEG. The frames are stacked at full precision internally, but the
  result is 8-bit; there is no DNG export yet.
- The sky is not tracked mechanically. Without a star tracker, sub-exposures are
  short by necessity, which is exactly why this app stacks.
