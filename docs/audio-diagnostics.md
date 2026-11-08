# Chirp acoustic diagnostic

The main Scan button still runs simulation. The separate Acoustic diagnostic panel
helps establish chirp detection before ranging. It never reports real distances,
labels a correlation candidate as a detection, or changes Dev 1's DSP algorithms.

## Controlled Samsung Galaxy S23 Ultra test

```sh
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s ChirpAudio
```

1. Disconnect external audio, including Bluetooth earbuds, USB audio adapters,
   and wired headphones. USB ADB alone is fine. End calls/communication sessions.
   Put the phone in a fixed position in a quiet room with its speaker and mic
   openings uncovered. Avoid handling the phone during capture.
2. Set media volume low but nonzero; leave it unchanged for the test matrix.
   Keep the speaker away from ears. Every active test plays exactly one chirp
   at amplitude 0.15 and track gain 0.25; the app never raises system volume.
   8–14 kHz may be audible. Do not raise volume just to hear the higher band.
3. Scroll to Acoustic diagnostic, select 8–14 kHz and 10 ms, and enable
   Recording-only baseline. Tap Start test, grant microphone access, then tap
   Start test again. The baseline creates no AudioTrack and emits no sound.
4. Disable baseline and capture with the same band/duration and media volume.
   Wait a few seconds between tests. Collect at least three baseline/active
   pairs without moving the phone or changing the room. Repeat for 30 and 50 ms,
   then repeat all three durations at 15–20 kHz. Change one setting at a time.
   Each baseline is correlated with its selected reference despite no playback.
5. All captures contain 33,600 frames / 67,200 bytes at 48 kHz, mono PCM16 LE.
   Active reference lengths are 480, 1,440, or 2,400 frames (960/2,880/4,800 bytes).
   Inspect source/effects/routes, peak/RMS/clipping, transfer errors, timestamp
   overlap, selected-band RMS, and NCC candidates. Prefer repeatable active
   peaks in a consistent timing region that clearly exceed baseline candidates,
   alongside increased band energy. A lone maximum or energy increase is not
   proof of chirp detection; unrelated noise and leakage can also produce peaks.
6. Confirm TIMESTAMP_OVERLAP for active runs when hardware timestamps are
   available. INCONCLUSIVE means timestamp overlap could not be verified; do not
   claim acoustic timing from play() request times. OUTSIDE_CAPTURE means the
   timestamp mapping does not contain the whole chirp: investigate timing before
   interpreting the run as a valid detection test. Baselines are NOT_APPLICABLE.
7. Try Stop promptly after Start, Home, lock, rotation, and reopening capture.
   Look for Audio resources released, no ongoing playback, and microphone use
   ending. Native resources are released before analysis runs, even though the
   panel remains busy until processing/export completes. Stop also cancels analysis.
8. Deny/revoke permission and verify simulation still works; use App permissions
   to grant it again. With the system microphone privacy toggle disabled the
   platform may return silence; inspect stats. Available endpoints alone never
   reject capture, but an actual non-built-in route must fail and release resources.

There is no universal NCC threshold here. Compare only runs with the same reference,
normalization, source, volume, environment, and window length. Longer references
change the distribution of background matches. A working I/O pipeline and no clipping
alone do not demonstrate detection. Establish direct waveform detection first; a
reflector/distance experiment comes later.

## Playback/capture overlap

Capture starts first. After at least 4,800 input frames have been read, play() is
called. The queued stream contains 100 ms of leading zeros, the selected chirp,
and trailing zeros, with a buffer of at least 400 ms. The lead allows active route
checks before the chirp; the tail keeps the output clock observable after a short
chirp. Silence does not increase acoustic exposure. Capture remains 700 ms.

The playback head must cross the chirp's last frame before the recording's last
read; otherwise the test fails rather than reporting a successful playback.
Monotonic API call times, read frames at play request/completion, playback head,
and optional input/output hardware frame/time pairs are recorded. Output timestamp
samples must advance and lie inside the queued stream; frozen end-of-buffer
positions after underrun are not used for mapping. Both timestamp pairs map the chirp's known output frame interval onto the recorded input timeline:

```
inputStartFrame = input.framePosition
    + (output.nanoTime - input.nanoTime) * sampleRateHz / 1e9
    + chirpOutputOffsetFrames - output.framePosition
inputEndFrame = inputStartFrame + chirpFrames
```

TIMESTAMP_OVERLAP means that entire estimated interval fits within the recording.
These are hardware clock estimates, not proof that the waveform reached the mic.
Driver latency, vendor timestamp quality, analog paths and processing still require
acoustic validation. Timestamp availability is device-dependent. The reported
correlation lag is relative to input sample zero, NOT relative to play() or an echo.
No timing values are converted into distances.

## Metrics

Analysis runs on Dispatchers.Default after all native audio resources are released.

- Signal stats: full-capture peak and RMS in full-scale units, and saturated PCM16
  sample count. Silent/clipped data generates warnings in metadata/logs.
- Selected-band RMS: a zero-padded radix-2 FFT integrates one-sided squared bin
  magnitudes within the chosen frequency limits. Parseval scaling divides by FFT
  size and original sample count before taking the square root. Rectangular
  windows can leak spectral energy near band edges. Values are digital RMS,
  not calibrated sound pressure or loudness.
- Band RMS is reported for the full recording, first 100 ms (pre-roll), remaining
  recording, and strongest NCC candidate's reference-length window. Later RMS
  includes silence and is not itself a time-gated chirp estimate.
- NCC at every valid lag uses Dev 1's unchanged crossCorrelate(), divided by
  sqrt(reference energy * that lag's recording-window energy). It uses raw,
  unfiltered, uncentered samples and no mean subtraction. Near-zero energies
  produce zero. This is different from normalizing by whole-recording energy.
- Three largest absolute candidates are reported with signed NCC, integer sample
  lag and milliseconds from the first recorded sample. Candidate locations are
  separated by at least half a chirp duration to reduce repeated carrier lobes.
  This suppression is for diagnostics, not validated echo selection.

## PCM export and preservation

The original PCM exports remain unfiltered signed 16-bit little-endian, mono,
48000 Hz. No analysis modifies them. The repository's existing diagnostics/ files
are left untouched. Every completed run saves an immutable run directory beneath
files/audio-diagnostic/run-<time>-<id>, shown in the UI. Existing pre-archive device
exports are copied once into legacy-original/ before latest-file copies are updated.

Each run and the latest-file aliases contain:

- recorded.pcm: unchanged microphone samples.
- transmitted.pcm: exact generated chirp before track/system gain; EMPTY for baseline.
- reference.pcm: selected analysis chirp, including for baseline (not played there).
- playback-queued.pcm: exact stream submitted before track gain, including lead/tail;
  EMPTY for baseline. The chirp starts at output frame 4,800 in active runs.
- metadata.txt: settings, system media volume, buffers, source/effects/routes,
  timing/overlap, signal stats, and analysis results.

Export the latest files without any storage permission:

```sh
adb exec-out run-as com.example.chirp cat files/audio-diagnostic/recorded.pcm > recorded.pcm
adb exec-out run-as com.example.chirp cat files/audio-diagnostic/transmitted.pcm > transmitted.pcm
adb exec-out run-as com.example.chirp cat files/audio-diagnostic/reference.pcm > reference.pcm
adb exec-out run-as com.example.chirp cat files/audio-diagnostic/playback-queued.pcm > playback-queued.pcm
adb exec-out run-as com.example.chirp cat files/audio-diagnostic/metadata.txt > metadata.txt
adb shell run-as com.example.chirp ls files/audio-diagnostic
```

For an archived result, insert its exact run directory name between audio-diagnostic/
and the filename in the cat command. Copy results before uninstalling or clearing
app data. Archives accumulate; they are not automatically deleted. Baseline analysis
must use reference.pcm, since nothing was transmitted. Failures before export leave
previous latest-file aliases intact. A storage failure may leave an incomplete export.

## Capture and future ranging contract

AudioCapture.capture(AcousticConfig(...)) returns reference/recorded/transmitted/
queued PCM, sample rate, selected source, advertised unprocessed support, settings,
overlap status, metadata and AcousticReport. Captures/analysis are serialized and
cancellable. Activity Stop/onPause cancels the owning coroutine. No background
Android Service is used.

UNPROCESSED is preferred when advertised, falling back to VOICE_RECOGNITION on
initialization failure. VOICE_COMMUNICATION is never used. Available AGC/NS/AEC
session effects are inspected and disabled where permitted; results are logged.
Hardware/vendor processing may remain. Routing logs distinguish availableDevice,
requested preferredRoute and actualRoute changes (IDs, numeric types, names and
direction). Accepted preferences alone do not prove the active route.

Only after reproducible detection should Dev 1's findPeak() and
lagToDistanceMeters() be considered for echo ranging. Direct speaker-to-microphone
leakage, ringing, multipath, overlapping returns, frequency response and latency
can obscure reflections. A strong chirp peak usually establishes waveform arrival,
not an object's distance. Blindly skipping a whole chirp after direct arrival can
also discard nearby returns. Use captures to discuss any future DSP changes with
Dev 1 rather than changing the existing algorithms now.

Android timing references:
- https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp)
- https://developer.android.com/reference/android/media/AudioRecord#getTimestamp(android.media.AudioTimestamp,%20int)
