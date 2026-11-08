package com.example.chirp.sonar

import com.example.chirp.sonar.alerts.Alert
import com.example.chirp.sonar.alerts.ProximityAlert
import com.example.chirp.sonar.audio.ChirpGenerator
import com.example.chirp.sonar.calibration.Baseline
import com.example.chirp.sonar.calibration.Calibrator
import com.example.chirp.sonar.detection.DetectionResult
import com.example.chirp.sonar.detection.EchoDetector
import com.example.chirp.sonar.dsp.HighPassFilter
import com.example.chirp.sonar.dsp.Signal
import com.example.chirp.sonar.ranging.RangeCorrection
import com.example.chirp.sonar.tracking.ProximityTracker
import com.example.chirp.sonar.tracking.Track
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class SonarState { MEASURING_AMBIENT, ACQUIRING, CALIBRATING, RUNNING }

/** Everything known about one chirp period. Arrays are fresh copies, safe to hand to the UI. */
class SonarFrame(
    val sequence: Long,
    val state: SonarState,
    /** Monotonic time (ns) of this period's direct arrival; sample-clock based if no hardware timestamp. */
    val timeNs: Long,
    val timeFromHardware: Boolean,
    /** Unfiltered mic samples covering the echo window (direct arrival at [SonarConfig.PROFILE_PRE]). */
    val rawWindow: DoubleArray,
    /** Envelope of the raw matched-filter output, lag index as in profiles. */
    val profileEnvelope: DoubleArray,
    val detection: DetectionResult?,
    val track: Track?,
    /** Null until RUNNING. */
    val alert: Alert?,
    val directLevel: Double,
    /** Sub-sample drift of the direct arrival since calibration. Beyond ~0.3 the template no longer cancels. */
    val misalignmentSamples: Double?,
    val clippedSamples: Int,
    val processingMs: Double,
    val calibrationProgress: Float,
)

/**
 * The sonar pipeline, fed raw mic samples in arrival order:
 *
 *   MEASURING_AMBIENT  speaker silent; in-band noise floor
 *   ACQUIRING          find where the direct speaker->mic chirp lands within the period
 *   CALIBRATING        average N clear-scene periods into a [Baseline]
 *   RUNNING            per period: matched filter -> subtract baseline -> detect -> track -> alert
 *
 * Alignment: the speaker loops one period from a static buffer on the same audio clock as
 * the mic, so after acquisition every direct arrival lands exactly one period later. No
 * Android timestamp feeds the range math; hardware timestamps only label frames for tracking.
 */
class SonarProcessor(
    private val config: SonarConfig,
    /** Maps a mic frame index to monotonic ns, or null when the hardware clock isn't known. */
    private val clock: (Long) -> Long? = { null },
    initialCorrection: RangeCorrection = RangeCorrection(),
) {
    /** Raw -> true distance correction, from accuracy validation. Thread-safe; applies from the next period. */
    @Volatile var correction: RangeCorrection = initialCorrection
        set(value) { field = value; detector?.correction = value }

    /** Handheld mode: adaptive background instead of the fixed calibration snapshot. Thread-safe. */
    @Volatile var adaptiveBackground: Boolean = false
        set(value) { field = value; detector?.adaptive = value }

    /** Last period's track, used to protect the tracked object from the adaptive background. */
    private var lastTrack: Track? = null

    var onFrame: (SonarFrame) -> Unit = {}
    var onStatus: (SonarState, String) -> Unit = { _, _ -> }
    var onBaseline: (Baseline) -> Unit = {}

    val chirp = ChirpGenerator.linearChirp(
        config.sampleRateHz, config.startHz, config.endHz,
        config.chirpMs / 1000, config.amplitude, config.fadeMs / 1000,
    )

    private val period = config.periodFrames
    private val m = chirp.size
    private val pre = SonarConfig.PROFILE_PRE
    private val search = SonarConfig.DIRECT_SEARCH
    private val raw = SampleRing(4 * period + 4 * m)
    private val filtered = SampleRing(4 * period + 4 * m)
    private val highPass = HighPassFilter(config.sampleRateHz, config.highPassCutoffHz)
    private val tracker = ProximityTracker()
    private val alerter = ProximityAlert()

    var state = SonarState.MEASURING_AMBIENT
        private set
    var baseline: Baseline? = null
        private set

    private var ambientEnergy = 0.0
    private var ambientCount = 0
    private var ambientDbfs = Double.NaN
    private var acquireFrom = 0L
    private var acquireAttempts = 0
    private var nextDirect = 0L
    private var referenceLevel = 0.0
    private var lostStreak = 0
    private var sequence = 0L
    private var calibrator: Calibrator? = null
    @Volatile private var detector: EchoDetector? = null
    @Volatile private var recalibrationRequested = false

    /** Thread-safe; takes effect on the next period. Keep the area in front clear while it runs. */
    fun requestRecalibration() { recalibrationRequested = true }

    fun push(samples: ShortArray, count: Int = samples.size) {
        for (i in 0 until count) {
            val x = samples[i] / 32768.0
            val y = highPass.process(x)
            raw.add(x)
            filtered.add(y)
            if (state == SonarState.MEASURING_AMBIENT && raw.end > FILTER_SETTLE) {
                ambientEnergy += y * y; ambientCount++
            }
        }
        step()
    }

    private fun step() {
        if (state == SonarState.MEASURING_AMBIENT) {
            if (raw.end < config.ambientFrames) return
            ambientDbfs = 20 * log10(sqrt(ambientEnergy / maxOf(1, ambientCount)).coerceAtLeast(1e-9))
            beginAcquisition("Ambient noise ${"%.1f".format(ambientDbfs)} dBFS in band. Listening for the chirp…")
        }
        if (state == SonarState.ACQUIRING && !tryAcquire()) return

        if (recalibrationRequested) {
            recalibrationRequested = false
            beginCalibration()
        }
        while (state != SonarState.ACQUIRING) {
            val from = nextDirect - pre - search
            val to = nextDirect + config.maxLag + search + m
            if (!filtered.contains(from, to)) return
            processPeriod(from, to)
            nextDirect += period
        }
    }

    private fun beginAcquisition(message: String) {
        state = SonarState.ACQUIRING
        acquireFrom = raw.end
        acquireAttempts = 0
        tracker.reset()
        alerter.reset()
        onStatus(state, message)
    }

    private fun beginCalibration() {
        state = SonarState.CALIBRATING
        calibrator = Calibrator(config, chirp, ambientDbfs)
        detector = null
        tracker.reset()
        lastTrack = null
        alerter.reset()
        onStatus(state, "Calibrating: hold the phone still with the space in front clear for 3 s.")
    }

    /** Correlates two periods and takes the strongest arrival as the direct path. */
    private fun tryAcquire(): Boolean {
        val to = acquireFrom + 2 * period + m - 1
        if (!filtered.contains(acquireFrom, to)) return false
        val env = Signal.envelope(Signal.crossCorrelate(filtered.copy(acquireFrom, to), chirp))
        var d = 0
        for (i in 1 until period) if (env[i] > env[d]) d = i
        val second = (d + period - 2..d + period + 2).maxOf { env[it.coerceIn(0, env.lastIndex)] }
        val snr = env[d] / Signal.median(env).coerceAtLeast(1e-300)
        val repeats = second in env[d] * 0.5..env[d] * 2.0

        // The ring still holds the samples before acquireFrom, so a small d needs no special case.
        if (snr >= MIN_DIRECT_SNR && repeats) {
            nextDirect = acquireFrom + d
            referenceLevel = env[d]
            lostStreak = 0
            beginCalibration()
            return true
        }
        acquireAttempts++
        if (acquireAttempts % 5 == 0) {
            onStatus(state, "No chirp heard (direct-path SNR ${"%.1f".format(snr)}). " +
                "Raise media volume, uncover the bottom speaker/mic, disconnect headphones.")
        }
        acquireFrom = raw.end // retry with fresh audio
        return false
    }

    private fun processPeriod(from: Long, to: Long) {
        val started = System.nanoTime()
        val corr = Signal.crossCorrelate(filtered.copy(from, to), chirp)
        val env = Signal.envelope(corr)

        // Expected direct arrival sits at corr index pre + search.
        var j = pre
        for (i in pre..pre + 2 * search) if (env[i] > env[j]) j = i
        val offset = j + Signal.parabolicOffset(env, j) - (pre + search)
        val level = env[j]

        val lost = level < LOST_RATIO * referenceLevel || j == pre || j == pre + 2 * search
        lostStreak = if (lost) lostStreak + 1 else 0
        if (lostStreak >= LOST_PERIODS) {
            beginAcquisition("Lost the direct path (speaker covered, volume changed, or audio glitch). Re-acquiring…")
            return
        }

        val profile = corr.copyOfRange(search, search + config.profileLength)
        val profileEnv = env.copyOfRange(search, search + config.profileLength)
        val rawWindow = raw.copy(from + search, from + search + config.profileLength + m)
        val clipped = rawWindow.count { abs(it) >= CLIP_LEVEL }

        var detection: DetectionResult? = null
        var track: Track? = null
        var alert: Alert? = null
        var misalignment: Double? = null
        val timeNs = clock(nextDirect)
        val t = timeNs ?: (nextDirect * 1e9 / config.sampleRateHz).toLong()

        val frameState = state // label the frame with the stage that processed it
        when (frameState) {
            SonarState.CALIBRATING -> {
                val cal = checkNotNull(calibrator)
                if (!lost) {
                    val start = nextDirect + offset.roundToInt() - Calibrator.DIRECT_WINDOW_PRE
                    val end = nextDirect + offset.roundToInt() + m + Calibrator.DIRECT_WINDOW_POST
                    if (filtered.contains(start, end)) cal.add(profile, offset, filtered.copy(start, end))
                }
                if (cal.isComplete) {
                    val b = cal.finish()
                    baseline = b
                    referenceLevel = b.directLevel
                    detector = EchoDetector(config, b).also { it.correction = correction; it.adaptive = adaptiveBackground }
                    calibrator = null
                    state = SonarState.RUNNING
                    onBaseline(b)
                    onStatus(state, "Running. Leakage-to-noise ${"%.0f".format(b.dynamicRangeDb)} dB.")
                }
            }
            SonarState.RUNNING -> {
                val b = checkNotNull(baseline)
                misalignment = offset - b.directOffset
                val det = checkNotNull(detector)
                val protect = lastTrack?.takeIf { it.confirmed }?.let { det.lagForDistance(it.distanceM) }
                detection = det.detect(profile, offset, protect)
                track = tracker.update(t, detection.echoes.map { it.distanceM })
                lastTrack = track
                alert = alerter.update(t, track)
            }
            else -> Unit
        }

        onFrame(
            SonarFrame(
                sequence = sequence++, state = frameState, timeNs = t, timeFromHardware = timeNs != null,
                rawWindow = rawWindow, profileEnvelope = profileEnv, detection = detection, track = track, alert = alert,
                directLevel = level, misalignmentSamples = misalignment, clippedSamples = clipped,
                processingMs = (System.nanoTime() - started) / 1e6,
                calibrationProgress = calibrator?.let { it.count.toFloat() / config.calibrationFrames } ?: 1f,
            )
        )
    }

    companion object {
        /** Direct arrival envelope over the median envelope needed to lock on. */
        const val MIN_DIRECT_SNR = 8.0
        /** Direct level below this fraction of the reference counts as a lost period. */
        const val LOST_RATIO = 0.25
        const val LOST_PERIODS = 3
        /** Samples ignored while the high-pass filter settles. */
        const val FILTER_SETTLE = 480
        const val CLIP_LEVEL = 0.999
    }
}

/** Fixed-size history of samples addressed by absolute index since start. */
internal class SampleRing(private val capacity: Int) {
    private val data = DoubleArray(capacity)
    /** Absolute index of the next sample to be written. */
    var end = 0L
        private set

    fun add(x: Double) {
        data[(end % capacity).toInt()] = x
        end++
    }

    fun contains(from: Long, to: Long) = from >= 0 && from >= end - capacity && to <= end && from <= to

    fun copy(from: Long, to: Long): DoubleArray {
        check(contains(from, to)) { "samples [$from, $to) not buffered (end=$end)" }
        return DoubleArray((to - from).toInt()) { data[((from + it) % capacity).toInt()] }
    }
}
