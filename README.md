# MYmusic Cutter (native Android)

The cutter interface (`app/src/main/assets/index.html`) runs inside a native WebView.
Kotlin code does the cutting, saving, landscape lock and fullscreen.

## Build on GitHub
Either way works:
- **iPad / single file:** create a new repository, upload `MYmusicCutter_source.zip` as one file,
  then use *Add file → Create new file*, name it `.github/workflows/build-apk.yml` and paste the
  workflow text. The workflow unpacks the ZIP itself.
- **Computer:** extract the ZIP and upload its *contents* so `settings.gradle` and the `.github`
  folder sit at the top level of the repository (not inside a sub-folder).

Then open **Actions → Build MYmusic Cutter APK**. The APK is in the run's *Artifacts*.

## What the app does
- Opens straight into the portrait cutter. The video area matches the video's shape; sliders fill the rest.
- Portrait: tap the video to play/pause. Fullscreen: tap left third -5 s, right third +5 s, centre play/pause;
  one-finger swipe seeks; two fingers pinch-zoom and move the picture anywhere (at least 20% stays on screen).
  Leaving fullscreen or "Reset picture" puts it back.
- Equalizer page (button on the video): 8 bands, bass, clarity at 8 kHz (±15 dB), HRTF virtual surround,
  click & pop repair, hiss/hum/rumble reduction, auto enhancer, and a limiter (on by default).
  Sound is untouched until a setting is changed.
- Back / edge swipe: closes the equalizer, then leaves fullscreen; on the main screen a second Back within
  2 seconds stops playback and closes the app. Playback pauses whenever the app leaves the screen.
- Swipes over the cutter timeline are not treated as the Back gesture (Android 10+).
- Below the timeline: one slim row with Reset, EQ, Add, and after saving: ▶ Play cut (opens the result here),
  ↩ Original (back to the file you started from, markers restored) or 🖼 View for snapshots; ✕ hides it.
  The time display sits on the timeline; the separate seek bar only appears in fullscreen.
- Results are written straight into their final folder (no temporary copy) with a live "Saving NN%".
- Appears in "Open with" and "Share" for audio and video files (one or several).
- Results are saved as "original name N" (my song 1.mp3, my song 2.mp3, ...).

## Version 2.0
- **Protected / encrypted files** (Solid Explorer `.sec` folders, cloud and messenger apps): files handed over as a
  one-way stream are copied once into the app's private cache ("Preparing NN%") and played from there with full
  seeking; the copy is deleted when another file is opened or the app closes. Normal files stream directly with
  seeking (HTTP Range) instead of being loaded into memory, so large videos open instantly.
- **Open with** from every file manager: content:// and file:// links, all audio/video types, files with unknown
  type matched by extension (mp4, mkv, webm, avi, mov, ts, flv, wmv, mpg, mp3, m4a, aac, wav, flac, ogg, opus, amr, wma…).
- **★ Default** button (next to Add): tells which app opens videos/music without asking, and opens that app's
  settings so its default can be cleared; then pick MYmusic Cutter → Always. Android does not let an app make itself
  the default; the user confirms it once.
- Equalizer: analog faders and VU meters, tube amplifier (drive, warmth), cinema sound (Off/Music/Movie/Voice/Night,
  dialogue enhancer, stereo width, bass enhancer, volume leveler), smarter auto enhancer (tone, loudness, hum, rumble),
  settings remembered between sessions. Mono files now feed both channels of the surround stages.
- Formats Android's built-in decoders cannot play (e.g. AC3/DTS sound, WMV, DivX AVI) still show a clear message.

## Cutting (no re-encoding)
| File | Output | Notes |
|---|---|---|
| MP3 | .mp3 | Whole frames (~26 ms) copied; length/seek header rewritten |
| WAV | .wav | Exact to the sample |
| MP4 / M4A / 3GP / MOV / AAC (H.264, H.265, AAC) | .mp4 / .m4a | Video starts at the keyframe at or before your start point |
| WebM / OGG / Opus (VP8, VP9, Vorbis, Opus) | .webm | Same keyframe rule |
| FLAC, AC3, MP3-inside-video, etc. | — | Reported as unsupported; nothing is saved |

Video goes to `Movies/MYmusicCuts`, audio to `Music/MYmusicCuts`. The original is never changed.
Results keep the original name plus a number: `my song.mp3` → `my song 1.mp3`, then `my song 2.mp3`, and so on
(numbered per name within each folder). Snapshots follow the same rule in `Pictures/MYmusicCuts`.
The file is read directly from what you picked; copying through JavaScript is only a fallback.
On Android 9 and older the app asks for storage permission so it can save into those public folders.

## Status
Page tested in Chromium (all sound stages measured offline). Record the exact error text and Android version for any failure.
