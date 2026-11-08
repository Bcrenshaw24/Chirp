# Chirp

Chirp is an Android app that helps you sense what's around you using your phone's built-in
hardware. It is developed and tested on a Galaxy S23+.

Each feature lives in its own package so they can grow independently. `MainActivity` and
`ui/theme` stay at the app level.

## Sonar proximity sensing

Chirp plays short, mostly inaudible chirps (15–20 kHz) from the speaker, records the echoes, and
works out how far away the nearest object is (about 0.15–3 m), ten times a second.

**Status:** with the phone still on a table, it detects an object in front of the bottom edge and
estimates its distance live. Handheld use, alerts and an accuracy check are in place and being tuned.

### Using it

1. Lay the phone flat with the bottom edge (speaker and mic) facing open space, media volume about half.
2. Tap **Start scanning** and keep the area clear for ~3 s while it calibrates.
3. Bring something in front of the bottom edge. The radar and readout show its distance and
   whether it's getting closer; the screen turns amber, then red, as it gets near.

**Settings** on the main screen: **Sound** (Silent 15–20 kHz or Audible 8–14 kHz), **Handheld mode**
and **Vibrate on alert**. The collapsible **Signal lab** at the bottom holds the live plots, the
accuracy check and the diagnostics.

### Pipeline

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
                               SonarEngine ─ SonarFrame flow ─► SonarViewModel ─► ui/SonarScreen
```

All sonar code lives in `com.example.chirp.sonar`.

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
| `ui/` | Main screen and radar view, Signal lab (plots, accuracy card, diagnostics), view model |

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

### Handheld mode

On by default (switch under Settings on the main screen). Instead of subtracting the fixed calibration snapshot,
the background follows the scene with a 1.5 s time constant. Things that stay put relative to the
phone (your hand, your body, a new grip) fade in a few seconds. Things moving relative to it (a wall
you walk toward, an approaching person) stay visible. The confirmed track's range bins are frozen, so
an object you stop in front of doesn't fade. That only applies if it *added* echo energy: a "ghost"
of something that was in the background and has since gone does fade. Turn it off for table tests,
where the fixed baseline is more sensitive.

Limits: an object that stays at a constant distance while it is *not* the tracked (nearest) object
fades in about 4 s. If a new object lands exactly on the range of a vanished one, the two can cancel.

### Accuracy check

In the Signal lab, after calibration: place a flat object at 0.3 / 0.5 / 1.0 / 1.5 m (tape-measured
from the bottom edge), tap that distance, and 20 readings are collected. The table shows the raw bias,
the repeatability (standard deviation) and the detection rate. **Fit & apply** fits `true = scale x raw + offset`
(offset only unless the points span at least 0.4 m) and saves it to app storage. It is reloaded on every
start and survives restarts and reinstalls; only uninstalling or clearing app data removes it. The offset absorbs the speaker-mic spacing
guess; the scale absorbs speed-of-sound error from temperature (~0.18% per C). Implausible fits
(scale outside 0.5–1.5, or offset over 50 cm) are rejected rather than applied.

### Proximity alerts

Only confirmed tracks alert. **WARNING** below 0.5 m or under 1 s to contact; **CAUTION** below
1.0 m or under 2.5 s (thresholds in `AlertConfig`). Alerts rise immediately and fall only after the
object is 0.1 m (or 1.5x time to contact) clear of the threshold for 0.6 s. On screen, the status turns amber
(CAUTION) or red (WARNING) and shows the reason; the phone also vibrates. There is no sound, because a tone from the speaker would interfere with the sonar band.
Vibration shakes the phone and may disturb the leakage the sonar subtracts. The "Vibrate on alert"
switch lets you compare.

### Testing on the phone

```sh
./gradlew installDebug
adb logcat -s ChirpAudio
```

1. Settings → Sounds and vibration → Sound quality and effects: **turn off Dolby Atmos and the equalizer**.
   Disconnect Bluetooth/wired audio.
2. Put the phone flat on a table, bottom edge facing open space (nothing within ~1.5 m). Media volume about half.
3. **Start scanning**. The app measures ambient noise (0.3 s), locks onto the chirp, then calibrates (3 s).
   Keep the area clear and don't touch the phone.
4. Bring a flat object (book, box, hand) in front of the bottom edge at measured distances: 0.3, 0.5, 1.0, 1.5 m.
   Compare the readout with a tape measure, then move it slowly toward and away from the phone.
5. If you move the phone or the scene changes, press **Recalibrate** (with the area clear).

What to look at (open the **Signal lab**):
- **Raw microphone**: the chirp should be visible near the red line; there should be no clipping.
- **Matched-filter output**: a tall direct peak at 0 m, then room reflections.
- **Echo detection**: flat below the grey threshold when clear; a blue peak above it at the object.
- **Speaker → mic response**: shows where in 15–20 kHz the S23+ is weak (likely the top end).
- **Diagnostics**: whether UNPROCESSED was granted, whether AGC/NS/AEC were on, which mic is active, and alignment drift.

If "No chirp heard" persists, switch **Sound** to **Audible** (8–14 kHz). If that works, the high band isn't getting through.

### Limitations

- **Distance only, no direction.** The nearest reflector anywhere in a wide beam wins, including the hand holding the phone.
- **Range is about 0.15–3 m.** Closer echoes blur into the speaker's own sound; small or soft objects near 3 m may be missed.
- **Phone audio varies.** Speakers fade toward 20 kHz and some phones' audio processing can't be fully switched off.
  The Signal lab's speaker → mic response plot shows how your unit does. 15–20 kHz is audible to some young people and pets.
- **Foreground only.** Scanning stops when the app leaves the screen.

### The math

Symbols: $`f_s = 48\,000`$ Hz sample rate, $c$ speed of sound, $B = f_1 - f_0$ sweep bandwidth,
$T$ chirp duration, $P$ samples per period, $k$ lag in samples after the direct arrival.

#### 1. Chirp

Linear FM sweep from $f_0 = 15$ kHz to $f_1 = 20$ kHz over $T = 5$ ms, with raised-cosine edge fades $w[n]$:

```math
f(t) = f_0 + \frac{B}{T}\,t, \qquad
\varphi(t) = 2\pi\!\left(f_0\,t + \frac{B}{2T}\,t^2\right), \qquad
s[n] = A\,w[n]\,\sin\!\big(\varphi(n/f_s)\big)
```

Range resolution depends on bandwidth, detection strength on energy (duration). The chirp gets both:

```math
\Delta R = \frac{c}{2B} \approx 3.4\ \text{cm}, \qquad \text{compression ratio} = T B = 25
```

#### 2. Delay → distance

Delay $\tau$ is measured from the direct speaker→mic arrival, so all audio-stack latency cancels.
With speaker–mic spacing $s$:

```math
\tau = \frac{2D - s}{c} \quad\Longleftrightarrow\quad D = \frac{c\,\tau + s}{2}, \qquad
c(T_\text{air}) = 331.3 + 0.606\,T_\text{air}\ \ [\text{m/s}]
```

One sample is $c / 2f_s \approx 3.6$ mm of range. A period of $P = 4800$ samples gives 10 updates/s
and a maximum unambiguous range of $cP / 2f_s \approx 17$ m.

#### 3. Matched filter, envelope, sub-sample peak

After a 4th-order Butterworth high-pass at $`0.75\,f_0`$, the recording $x$ is correlated with the chirp $h$:

```math
r[k] = \sum_{m=0}^{M-1} x[k+m]\,h[m]
```

An echo $`a\,h[n-d]`$ gives $`r[d] = a\,E_h`$ with $E_h = \sum h^2$, while white noise of variance $\sigma^2$
only reaches $\sigma\sqrt{E_h}$. That is a processing gain of about $M = 240$ samples, or $+24$ dB.

The carrier oscillation is removed with the analytic signal: $\mathcal{H}$ is the Hilbert transform,
computed by zeroing negative frequencies in the FFT and doubling positive ones:

```math
\operatorname{env}(r) = \big|\, r + j\,\mathcal{H}\{r\} \,\big|
```

The peak at sample $i$ is refined with a parabola through its neighbours:

```math
\delta = \frac{1}{2}\,\frac{y_{i-1} - y_{i+1}}{y_{i-1} - 2y_i + y_{i+1}}, \qquad \delta \in \left[-\tfrac12, \tfrac12\right]
```

#### 4. Calibration

From $N = 30$ clear-scene profiles $p_n$: a template (leakage + static room) and a per-range noise level

```math
T[k] = \frac{1}{N}\sum_{n=1}^{N} p_n[k], \qquad
\sigma[k] = \sqrt{\frac{1}{N-1}\sum_{n=1}^{N} \operatorname{env}\!\big(p_n - T\big)[k]^2}
```

$\sigma[k]$ is smoothed over about 4 pulse widths. The speaker→mic response across the band:

```math
H(f) = 20\log_{10}\frac{|Y(f)|}{|X(f)|}\quad \text{(received direct arrival } Y \text{ vs. chirp } X\text{)}
```

#### 5. Drift compensation

A template misalignment of $\delta$ samples at frequency $f$ leaves this fraction of the leakage behind:

```math
\theta = \frac{2\pi f\,\delta}{f_s}, \qquad \frac{|\text{residual}|}{|\text{leakage}|} = \big|1 - e^{j\theta}\big| = 2\sin\frac{\theta}{2}
```

(At 17.5 kHz, $`\delta = 0.39 \Rightarrow 86\%`$ left.) Fractional delays are applied exactly as a phase ramp:

```math
T_\delta = \mathcal{F}^{-1}\!\left\{ \mathcal{F}\{T\}[\kappa]\; e^{-j2\pi \kappa\delta/N_\text{FFT}} \right\}
```

Gain $g$ and shift are fitted by least squares over the direct-arrival region, linearised around the current shift $s$:

```math
p \approx a\,T_s + b\,D_s, \qquad D_s = \frac{T_{s+h} - T_{s-h}}{2h}
```

```math
\begin{bmatrix} \sum T_s^2 & \sum T_s D_s \\ \sum T_s D_s & \sum D_s^2 \end{bmatrix}
\begin{bmatrix} a \\ b \end{bmatrix}
=
\begin{bmatrix} \sum p\,T_s \\ \sum p\,D_s \end{bmatrix},
\qquad g = a, \quad s \leftarrow s + \frac{b}{a}
```

#### 6. Detection

```math
e[k] = \operatorname{env}\!\big(p - g\,T_s\big)[k], \qquad
\text{echo at } k \iff e[k] > K\,\sigma[k] \ \text{and}\ e[k] \text{ is a local max}, \quad K = 5
```

The noise envelope is Rayleigh-distributed, so the false-alarm probability per range bin is

```math
P_\text{FA} = P\big(e > K\cdot \text{rms}\big) = e^{-K^2} \approx 1.4\times10^{-11}
```

Peaks within 2 pulse widths of a stronger one are merged. Echo strength is $\text{SNR} = 20\log_{10}\big(e[k]/\sigma[k]\big)$.

#### 7. Handheld: adaptive background

```math
B \leftarrow B + \frac{p - B}{\tau}, \qquad \tau = 15 \text{ periods } (1.5\ \text{s})
```

A static change fades as $e^{-n/\tau}$. One that starts $R$ times above the threshold drops below it after
$n \approx \tau \ln R$ periods. An object moving at speed $v$ crosses a range bin in $\Delta R / v$
(0.34 s at 10 cm/s, much less than $\tau$), so it stays visible. Bins around the confirmed track are frozen
only when $`\lvert\operatorname{env}(p)\rvert > \lvert\operatorname{env}(g\,T_s)\rvert`$ there (energy added = real object;
energy removed = ghost, which must fade).

#### 8. Tracking (alpha-beta filter)

```math
\hat d = d + v\,\Delta t, \qquad
\text{gate: } |m - \hat d| \le 0.25 + |v|\,\Delta t, \qquad
\rho = m - \hat d
```

```math
d \leftarrow \hat d + \alpha\,\rho, \qquad v \leftarrow v + \frac{\beta}{\Delta t}\,\rho, \qquad
\alpha = 0.5,\ \beta = 0.1, \qquad
\text{TTC} = \frac{d}{-v}\ \ (v < 0)
```

A track is confirmed after 3 hits and dropped after 5 consecutive misses. The trend is APPROACHING when
$v < -0.08$ m/s and STEADY when $|v| < 0.04$ m/s (hysteresis).

#### 9. Alerts

```math
\text{WARNING: } d \le 0.5\ \text{m} \ \lor\ \text{TTC} \le 1\ \text{s}, \qquad
\text{CAUTION: } d \le 1.0\ \text{m} \ \lor\ \text{TTC} \le 2.5\ \text{s}
```

Levels rise immediately. To fall, the condition must clear by $+0.1$ m and $1.5\times$ TTC for 0.6 s
(a Schmitt trigger, so the output doesn't flicker at a threshold).

#### 10. Range correction

Least squares of true ($y$) on raw ($x$) distance over the tape-measured points:

```math
\text{scale} = \frac{\sum (x - \bar x)(y - \bar y)}{\sum (x - \bar x)^2}, \qquad
\text{offset} = \bar y - \text{scale}\cdot\bar x, \qquad
D_\text{true} = \text{scale}\cdot D_\text{raw} + \text{offset}
```

The offset absorbs the spacing guess (error $e$ in $s$ shifts every distance by $e/2$). The scale absorbs
speed-of-sound error, $`\frac{1}{c}\frac{dc}{dT} = \frac{0.606}{343} \approx 0.18\%/^\circ\text{C}`$. If the
points span less than 0.4 m, only the offset is fitted.
