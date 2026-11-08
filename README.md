# Chirp: acoustic proximity sensing on a Galaxy S23+

The phone's speaker emits short inaudible-ish chirps (15–20 kHz). The microphone records the
returning echoes, and the app works out the distance to the nearest object and whether it is approaching.

**Milestone 1 (current):** with the phone still on a table, detect an object in front of the
bottom edge and estimate its distance (0.15–3 m), live, with every intermediate signal visible.

## Pipeline

```
DuplexAudioSession ─ mic blocks ─► SonarProcessor
 (speaker loops chirp                ├─ high-pass (dsp/HighPassFilter)
  every 100 ms)                      ├─ ambient noise   (speaker silent, first 300 ms)
                                     ├─ acquisition     (find direct speaker→mic arrival)
                                     ├─ calibration     (calibration/Calibrator → Baseline)
                                     ├─ matched filter  (dsp/Signal.crossCorrelate + envelope)
                                     ├─ detection       (detection/EchoDetector)
                                     ├─ ranging         (ranging/Ranging)
                                     ├─ tracking        (tracking/ProximityTracker)
                                     └─ alerting        (alerts/ProximityAlert)
                                              │
                               SonarEngine ─ SonarFrame flow ─► SonarViewModel ─► ui/DebugScreen
```

All sonar code lives in one feature package, `com.example.chirp.sonar`, so other features can sit
beside it. `MainActivity` and `ui/theme` stay at the app level.

| Package (under `sonar/`) | Responsibility |
|---|---|
| *(root)* | `SonarConfig`, the pure-Kotlin pipeline `SonarProcessor`, Android glue `SonarEngine` |
| `audio/` | Chirp synthesis; speaker + mic ownership (raw source, effects off, route checks, timestamps) |
| `dsp/` | FFT, matched filter, envelope, sub-sample peak, high-pass, fractional delay |
| `calibration/` | Clear-scene template (leakage + static room), per-range noise, ambient level, speaker→mic response |
| `detection/` | Background subtraction (fixed or adaptive), drift fit, per-range threshold, peak picking |
| `ranging/` | Delay ↔ distance, speed of sound, fitted correction + its storage |
| `tracking/` | Alpha-beta tracker: gating, confirmation, approaching/receding, time to contact |
| `alerts/` | CAUTION/WARNING from distance or time to contact, with hysteresis; vibration output |
| `validation/` | Tape-measure accuracy check: bias, repeatability, detection rate per distance |
| `ui/` | Debug screen, plots, accuracy card, view model |

Everything except `audio/DuplexAudioSession`, `SonarEngine`, `ranging/CorrectionStore`, `alerts/AlertHaptics` and `ui/` is plain Kotlin and
is unit-tested on the JVM with synthetic echoes (`./gradlew testDebugUnitTest`).

### Key design decisions

- **Distances are measured from the direct speaker→mic arrival**, never from `play()` or timestamps.
  Android output/input latency is tens of ms, varies per run, and is partly unreported. Referencing the
  direct path cancels it exactly.
- **Static looping AudioTrack.** One period (chirp + silence) loops in the mixer: no app CPU, no
  underruns, and chirps are exactly one period apart on the audio clock. So after one acquisition, every
  later period's alignment is known.
- **Calibrate, then subtract.** Direct leakage is 30–50 dB louder than an echo. The averaged clear-scene
  profile (leakage + table + walls) is subtracted from each period, so only *changes* remain. The
  threshold is set per range bin from calibration noise, so it is strict near the leakage and lenient far out.
- **Tracking does part of the noise rejection.** A track needs 3 agreeing hits to be confirmed.
  Measurements outside the gate don't move it, and the trend uses hysteresis.

## Handheld mode

On by default (switch on the debug screen). Instead of subtracting the fixed calibration snapshot,
the background follows the scene with a 1.5 s time constant. Things that stay put relative to the
phone (your hand, your body, a new grip) fade in a few seconds. Things moving relative to it (a wall
you walk toward, an approaching person) stay visible. The confirmed track's range bins are frozen, so
an object you stop in front of doesn't fade. That only applies if it *added* echo energy: a "ghost"
of something that was in the background and has since gone does fade. Turn it off for table tests,
where the fixed baseline is more sensitive.

Limits: an object that stays at a constant distance while it is *not* the tracked (nearest) object
fades in about 4 s. If a new object lands exactly on the range of a vanished one, the two can cancel.

## Accuracy check

On the debug screen, after calibration: place a flat object at 0.3 / 0.5 / 1.0 / 1.5 m (tape-measured
from the bottom edge), tap that distance, and 20 readings are collected. The table shows the raw bias,
the repeatability (standard deviation) and the detection rate. **Fit & apply** fits `true = scale x raw + offset`
(offset only unless the points span at least 0.4 m) and saves it to app storage. It is reloaded on every
Start and survives restarts and reinstalls; only uninstalling or clearing app data removes it. The offset absorbs the speaker-mic spacing
guess; the scale absorbs speed-of-sound error from temperature (~0.18% per C). Implausible fits
(scale outside 0.5–1.5, or offset over 50 cm) are rejected rather than applied.

## Proximity alerts

Only confirmed tracks alert. **WARNING** below 0.5 m or under 1 s to contact; **CAUTION** below
1.0 m or under 2.5 s (thresholds in `AlertConfig`). Alerts rise immediately and fall only after the
object is 0.1 m (or 1.5x time to contact) clear of the threshold for 0.6 s. Output is an on-screen banner plus
vibration. There is no sound, because a tone from the speaker would interfere with the sonar band.
Vibration shakes the phone and may disturb the leakage the sonar subtracts. The "Vibrate on alert"
switch lets you compare.

## Testing on the phone

```sh
./gradlew installDebug
adb logcat -s ChirpAudio
```

1. Settings → Sounds and vibration → Sound quality and effects: **turn off Dolby Atmos and the equalizer**.
   Disconnect Bluetooth/wired audio.
2. Put the phone flat on a table, bottom edge facing open space (nothing within ~1.5 m). Media volume about half.
3. **Start**. The app measures ambient noise (0.3 s), locks onto the chirp, then calibrates (3 s).
   Keep the area clear and don't touch the phone.
4. Bring a flat object (book, box, hand) in front of the bottom edge at measured distances: 0.3, 0.5, 1.0, 1.5 m.
   Compare the readout with a tape measure, then move it slowly toward and away from the phone.
5. If you move the phone or the scene changes, press **Recalibrate** (with the area clear).

What to look at:
- **Raw microphone**: the chirp should be visible near the red line; there should be no clipping.
- **Matched-filter output**: a tall direct peak at 0 m, then room reflections.
- **Echo detection**: flat below the grey threshold when clear; a blue peak above it at the object.
- **Speaker → mic response**: shows where in 15–20 kHz the S23+ is weak (likely the top end).
- **Diagnostics**: whether UNPROCESSED was granted, whether AGC/NS/AEC were on, which mic is active, and alignment drift.

If "No chirp heard" persists, try the 8–14 kHz preset. That indicates the high band isn't getting through.

## Known hardware / Android limitations

- **Speaker band:** small phone speakers roll off above roughly 18–20 kHz, and the mic path is band-limited
  near 20–24 kHz at 48 kHz sampling. The calibration response plot measures this on your unit.
  15–20 kHz is audible to many young people and to pets.
- **Two speakers:** the S23+ plays stereo media through the bottom speaker *and* the earpiece. A mono stream
  may come out of both, giving two transmit points and two direct paths. This is unverified; check the
  matched-filter plot for a second early peak.
- **Raw capture isn't guaranteed:** `UNPROCESSED` is requested (and tried even when not advertised);
  vendor DSP may still run. AGC/NS/AEC are disabled where the API permits.
- **Range only, no direction:** one speaker and one effective mic give distance but not bearing. The
  nearest reflector anywhere in a wide beam wins, including the hand holding the phone.
- **Fixed vs. adaptive baseline:** fixed mode assumes the phone and the static scene don't move after
  calibration; handheld mode trades a little sensitivity for tolerating motion (see above).
- **Drift:** the direct arrival slowly shifts by fractions of a sample (the S23+ showed −0.39 samples
  over two minutes, most likely the speaker warming up). The detector delays the template by the measured
  drift each period, so it keeps cancelling. Beyond a few samples, recalibrate.
- **Blind zone:** about 15 cm, where echoes overlap leakage that is never perfectly removed.
- **Background use:** recording while the screen is off needs a foreground service with the microphone
  type (Android 14+). The app currently stops in `onStop` by design.
- **SNR budget:** a 5 ms chirp gives ~24 dB matched-filter gain. Weak or small targets near 3 m may need
  longer chirps (better SNR, longer blind zone) or more volume.
