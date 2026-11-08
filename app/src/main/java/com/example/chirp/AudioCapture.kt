package com.example.chirp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/** Frame position and nanoseconds on Android's MONOTONIC clock, not wall time. */
data class AudioClockSample(val framePosition: Long, val nanoTime: Long)

data class AudioCaptureResult(
    val transmittedPcm: ByteArray,
    val recordedPcm: ByteArray,
    val sampleRateHz: Int,
    val audioSource: Int,
    val unprocessedSupported: Boolean,
    val metadata: String,
    val config: AcousticConfig = AcousticConfig(),
    val referencePcm: ByteArray = transmittedPcm,
    val queuedPcm: ByteArray = transmittedPcm,
    val overlap: String = "Not evaluated",
    val report: AcousticReport? = null,
)

/** One-shot audio I/O, not a foreground Android Service. No activity or UI ownership.
 *
 * capture() is serialized and cancellation-cooperative. Each invocation owns and releases
 * its native resources. No automatic repeats or distance estimates are performed.
 */
class AudioCapture(context: Context) {
    private val context = context.applicationContext
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mutex = Mutex()

    suspend fun capture(config: AcousticConfig = AcousticConfig()): AudioCaptureResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            // captureOnce releases all native resources before analysis starts.
            val result = captureOnce(config)
            val report = withContext(Dispatchers.Default) {
                AcousticDiagnostics.analyze(result.recordedPcm, result.referencePcm, result.sampleRateHz, config)
            }
            Log.i(TAG, report.text())
            result.copy(report = report, metadata = result.metadata + "\n" + report.text())
        }
    }

    /** Private app storage; accessible via adb run-as in the debug build. No storage permission. */
    suspend fun export(result: AudioCaptureResult): File = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "audio-diagnostic").apply { mkdirs() }
        val directory = File(root, "run-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        // Archive first. Keep legacy filenames as copies of the most recent successful run.
        val files = mapOf(
            "transmitted.pcm" to result.transmittedPcm,
            "recorded.pcm" to result.recordedPcm,
            "reference.pcm" to result.referencePcm,
            "playback-queued.pcm" to result.queuedPcm,
            "metadata.txt" to result.metadata.toByteArray(Charsets.UTF_8),
        )
        // Preserve legacy exports made before per-run archives were introduced.
        val legacy = File(root, "legacy-original")
        if (!legacy.exists() && File(root, "recorded.pcm").exists()) {
            check(legacy.mkdirs()) { "Cannot preserve the original PCM exports." }
            files.keys.forEach { name ->
                val previous = File(root, name)
                if (previous.exists()) previous.copyTo(File(legacy, name))
            }
        }
        files.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
        files.forEach { (name, bytes) -> File(root, name).writeBytes(bytes) }
        directory
    }

    private suspend fun captureOnce(config: AcousticConfig): AudioCaptureResult {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) { "Microphone permission is required." }
        check(audioManager.mode == AudioManager.MODE_NORMAL) {
            "End the call or communication session before testing."
        }
        val diagnostics = mutableListOf<String>()
        fun logRouting(message: String) {
            diagnostics += message
            Log.i(TAG, message)
        }
        // Inventory is not the active route. Android can expose unused/virtual endpoints.
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
        devices.forEach { logRouting("availableDevice=${describeDevice(it)}") }
        val speaker = devices.firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        check(config.baseline || speaker != null) { "Built-in speaker unavailable." }
        val mic = devices.firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: error("Built-in microphone unavailable.")
        val supported = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.equals("true", ignoreCase = true) == true
        diagnostics += listOf(
            "format=mono signed PCM16 little-endian sampleRateHz=$SAMPLE_RATE",
            "unprocessedPropertySupported=$supported; this is not a guarantee of unmodified capture",
            "config=$config amplitude=0.15 trackGain=0.25 chirpOutputOffsetFrames=${SAMPLE_RATE / 10}",
            "WARNING: use low nonzero media volume; keep away from ears. Lower band may be audible; never raise volume just to hear a high-frequency chirp.",
            "mediaVolume=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}",
        )
        val observedRoutes = mutableMapOf<String, String>()
        fun checkRoute(device: AudioDeviceInfo?, expected: Int, name: String, required: Boolean = false) {
            val description = describeDevice(device)
            if (observedRoutes[name] != description) {
                logRouting("$name actualRoute=$description")
                observedRoutes[name] = description
            }
            check(device == null || device.type == expected) {
                "Unexpected $name route: $description; built-in type=$expected required."
            }
            check(!required || device != null) { "$name route could not be verified; capture discarded." }
        }
        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        val effects = mutableListOf<AudioEffect>()
        try {
            val inputMin = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val outputMin = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(inputMin > 0 && (config.baseline || outputMin > 0)) {
                "48 kHz PCM16 unsupported: inputMin=$inputMin outputMin=$outputMin"
            }
            // 200 ms input buffer tolerates short scheduling stalls while we poll every 2 ms.
            val inputBytes = maxOf(inputMin * 2, SAMPLE_RATE / 5 * 2).let { (it + 1) / 2 * 2 }
            var source = if (supported) MediaRecorder.AudioSource.UNPROCESSED
                else MediaRecorder.AudioSource.VOICE_RECOGNITION
            fun createRecorder(selectedSource: Int): AudioRecord = AudioRecord.Builder()
                .setAudioSource(selectedSource)
                .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                .setBufferSizeInBytes(inputBytes).build()
            try {
                recorder = createRecorder(source)
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone not initialized." }
            } catch (failure: RuntimeException) {
                runCatching { recorder?.release() }
                recorder = null
                if (!supported || failure is SecurityException) throw failure
                diagnostics += "UNPROCESSED initialization failed: ${failure.message}; trying VOICE_RECOGNITION"
                source = MediaRecorder.AudioSource.VOICE_RECOGNITION
                recorder = createRecorder(source)
            }
            val input = checkNotNull(recorder)
            check(input.state == AudioRecord.STATE_INITIALIZED && input.sampleRate == SAMPLE_RATE &&
                input.channelCount == 1 && input.audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                "Microphone cannot initialize at 48000 Hz PCM16 mono."
            }
            logRouting("Request microphone preferredRoute=${describeDevice(mic)}")
            check(input.setPreferredDevice(mic)) { "Cannot select the built-in microphone." }
            logRouting("Microphone preference accepted=${describeDevice(input.preferredDevice)}; active route must still be verified")
            inspectEffects(input.audioSessionId, effects, diagnostics)

            val chirp = DspEngine.generateChirp(SAMPLE_RATE, config.band.startHz, config.band.endHz,
                config.durationMs / 1000.0, amplitude = 0.15)
            val chirpSamples = PcmAudio.decode(chirp)
            val chirpOffsetFrames = SAMPLE_RATE / 10
            var queuedPcm = ByteArray(0)
            var written = 0
            var writeCalls = 0
            var partialWrites = 0
            val deadline = System.nanoTime() + 5_000_000_000L
            val output = if (config.baseline) null else {
                // 400 ms of lead/chirp/tail keeps the output clock observable for short chirps.
                val outputBytes = maxOf(outputMin, SAMPLE_RATE * 2 * 4 / 10)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(outputBytes).build()
                val device = checkNotNull(track)
                check(device.state == AudioTrack.STATE_INITIALIZED && device.sampleRate == SAMPLE_RATE &&
                    device.channelCount == 1 && device.audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                    "Speaker cannot initialize at 48000 Hz PCM16 mono."
                }
                logRouting("Request speaker preferredRoute=${describeDevice(speaker)}")
                check(device.setPreferredDevice(checkNotNull(speaker))) { "Cannot select the built-in speaker." }
                logRouting("Speaker preference accepted=${describeDevice(device.preferredDevice)}; active route must still be verified")
                check(device.setVolume(0.25f) == AudioTrack.SUCCESS) { "Cannot set conservative playback gain." }
                val samples = ShortArray(device.bufferSizeInFrames)
                check(samples.size >= chirpOffsetFrames + chirpSamples.size) { "Playback buffer too short for selected chirp." }
                chirpSamples.copyInto(samples, chirpOffsetFrames)
                queuedPcm = PcmAudio.encode(samples)
                while (written < samples.size) {
                    checkDeadline(deadline)
                    val remaining = samples.size - written
                    val count = device.write(samples, written, remaining, AudioTrack.WRITE_NON_BLOCKING)
                    writeCalls++
                    if (count in 1 until remaining) partialWrites++
                    check(count >= 0) { "AudioTrack.write failed: code=$count offset=$written" }
                    if (count == 0) delay(2) else written += count
                }
                diagnostics += "requestedOutputBufferBytes=$outputBytes minOutputBytes=$outputMin actualOutputFrames=${device.bufferSizeInFrames} writtenFrames=$written"
                device
            }
            diagnostics += "source=${sourceName(source)} ($source) actualSource=${input.audioSource} requestedInputBufferBytes=$inputBytes minInputBytes=$inputMin actualInputFrames=${input.bufferSizeInFrames}"
            if (config.baseline) diagnostics += "RECORD_ONLY baseline: no AudioTrack created; reference.pcm is analysis-only; transmitted.pcm and playback-queued.pcm are empty"
            Log.i(TAG, diagnostics.joinToString("\n"))

            val recording = ShortArray(SAMPLE_RATE * 7 / 10) // 100 ms pre-roll + 600 ms following play request.
            var received = 0
            var readCalls = 0
            var partialReads = 0
            var emptyReads = 0
            var playRequestNs: Long? = null
            var playRequestReadFrames: Int? = null
            var chirpProgressStartNs: Long? = null
            var chirpProgressEndNs: Long? = null
            var chirpProgressEndReadFrames: Int? = null
            val startRequestNs = System.nanoTime()
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone failed to start." }
            var inputClock: AudioClockSample? = null
            var outputClock: AudioClockSample? = null
            while (received < recording.size) {
                checkDeadline(deadline)
                checkRoute(input.routedDevice, AudioDeviceInfo.TYPE_BUILTIN_MIC, "microphone")
                if (playRequestNs != null) {
                    checkRoute(output?.routedDevice, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "speaker")
                }
                // Small bounded reads give cancellation and route checks a chance between chunks.
                val requested = minOf(960, recording.size - received)
                val count = input.read(recording, received, requested, AudioRecord.READ_NON_BLOCKING)
                readCalls++
                if (count == 0) emptyReads++
                if (count in 1 until requested) partialReads++
                check(count >= 0) { "AudioRecord.read failed: code=$count offset=$received" }
                received += count
                if (output != null && playRequestNs == null && received >= SAMPLE_RATE / 10) {
                    checkRoute(input.routedDevice, AudioDeviceInfo.TYPE_BUILTIN_MIC, "microphone", required = true)
                    playRequestReadFrames = received
                    playRequestNs = System.nanoTime()
                    output.play()
                }
                val inStamp = AudioTimestamp()
                if (input.getTimestamp(inStamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    inputClock = AudioClockSample(inStamp.framePosition, inStamp.nanoTime)
                }
                if (output != null && playRequestNs != null) {
                    val head = output.playbackHeadPosition
                    if (head > chirpOffsetFrames && chirpProgressStartNs == null) chirpProgressStartNs = System.nanoTime()
                    if (head >= chirpOffsetFrames + chirpSamples.size && chirpProgressEndNs == null) {
                        chirpProgressEndNs = System.nanoTime()
                        chirpProgressEndReadFrames = received
                    }
                    val outStamp = AudioTimestamp()
                    // An underrun can leave a frozen end-of-buffer frame position.
                    // Use only advancing timestamps from inside the queued stream.
                    if (output.getTimestamp(outStamp) && outStamp.nanoTime > 0 &&
                        outStamp.framePosition in 1L until written.toLong() &&
                        outStamp.framePosition > (outputClock?.framePosition ?: -1L)) {
                        outputClock = AudioClockSample(outStamp.framePosition, outStamp.nanoTime)
                    }
                }
                if (count == 0) delay(2)
            }
            checkRoute(input.routedDevice, AudioDeviceInfo.TYPE_BUILTIN_MIC, "microphone", required = true)
            if (output != null) {
                checkRoute(output.routedDevice, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "speaker", required = true)
                check(chirpProgressEndNs != null && chirpProgressEndReadFrames!! < recording.size) {
                    "Chirp playback progress did not complete before the capture's last read; overlap unverified."
                }
            }
            val overlap = if (config.baseline) "NOT_APPLICABLE: recording-only baseline" else
                PlaybackOverlap.describe(inputClock, outputClock, SAMPLE_RATE, received, chirpOffsetFrames, chirpSamples.size)
            diagnostics += "playbackOverlap=$overlap"
            diagnostics += "chirpProgressStartMonotonicNs=$chirpProgressStartNs chirpProgressEndMonotonicNs=$chirpProgressEndNs readFramesAtChirpCompletion=$chirpProgressEndReadFrames"
            if (!config.baseline) diagnostics += "Playback head crossed chirp frames while AudioRecord was running; this alone does not prove acoustic detection."
            // Keep questionable captures for analysis, but never label out-of-window or missing-clock data verified.
            if (overlap.startsWith("OUTSIDE_CAPTURE")) diagnostics += "WARNING: timestamp mapping places chirp outside capture; do not treat this run as a detection test"
            val endNs = System.nanoTime()
            diagnostics += "inputRoute=${input.routedDevice?.id}:${input.routedDevice?.type} outputRoute=${output?.routedDevice?.id}:${output?.routedDevice?.type}"
            diagnostics += "capturedFrames=$received durationSeconds=${received.toDouble() / SAMPLE_RATE} wallCaptureMs=${(endNs - startRequestNs) / 1e6}"
            diagnostics += "recordStartRequestMonotonicNs=$startRequestNs playRequestMonotonicNs=$playRequestNs readFramesAtPlayRequest=$playRequestReadFrames captureEndMonotonicNs=$endNs"
            diagnostics += "inputTimestamp=$inputClock outputTimestamp=$outputClock playbackHeadFrames=${output?.playbackHeadPosition}"
            diagnostics += "request times and read frame counts are not acoustic onset times; timestamps may be unavailable"
            val statistics = PcmAudio.statistics(recording)
            diagnostics += "readCalls=$readCalls partialReads=$partialReads emptyReads=$emptyReads writeCalls=$writeCalls partialWrites=$partialWrites"
            diagnostics += "signal=$statistics"
            if (statistics.peak == 0.0) diagnostics += "WARNING: silent capture; check microphone privacy, volume, and competing apps"
            if (statistics.clippedSamples > 0) diagnostics += "WARNING: clipped input; reduce volume before echo analysis"
            diagnostics += "No read/write errors; amplitude alone does not validate echo detection or raw capture."
            val metadata = diagnostics.joinToString("\n")
            Log.i(TAG, metadata)
            return AudioCaptureResult(if (config.baseline) ByteArray(0) else chirp, PcmAudio.encode(recording),
                SAMPLE_RATE, source, supported, metadata, config, chirp, queuedPcm, overlap)
        } catch (failure: Exception) {
            Log.e(TAG, "Capture stopped/failed: ${failure.message}")
            throw failure
        } finally {
            // Stop I/O before releasing attached effects and native handles. Each cleanup is independent.
            runCatching { track?.stop() }
            runCatching { recorder?.stop() }
            effects.forEach { runCatching { it.release() } }
            runCatching { track?.release() }
            runCatching { recorder?.release() }
            Log.i(TAG, "Audio resources released")
        }
    }

    private fun inspectEffects(session: Int, owned: MutableList<AudioEffect>, notes: MutableList<String>) {
        fun inspect(name: String, available: () -> Boolean, create: () -> AudioEffect?) {
            try {
                if (!available()) {
                    notes += "$name unavailable"
                    return
                }
                val effect = create()
                if (effect == null) {
                    notes += "$name available but session attachment unavailable"
                    return
                }
                owned += effect
                val before = effect.enabled
                val status = if (before) effect.setEnabled(false) else AudioEffect.SUCCESS
                notes += "$name available control=${effect.hasControl()} enabledBefore=$before disableStatus=$status enabledAfter=${effect.enabled}"
            } catch (failure: RuntimeException) {
                notes += "$name inspection/disable failed: ${failure.message}"
            }
        }
        inspect("AGC", { AutomaticGainControl.isAvailable() }, { AutomaticGainControl.create(session) })
        inspect("NS", { NoiseSuppressor.isAvailable() }, { NoiseSuppressor.create(session) })
        inspect("AEC", { AcousticEchoCanceler.isAvailable() }, { AcousticEchoCanceler.create(session) })
    }

    private suspend fun checkDeadline(deadline: Long) {
        currentCoroutineContext().ensureActive()
        check(System.nanoTime() < deadline) { "Audio I/O timed out; no complete capture received." }
    }

    private fun describeDevice(device: AudioDeviceInfo?): String = device?.let {
        "id=${it.id} type=${it.type} name=${it.productName} isSource=${it.isSource} isSink=${it.isSink}"
    } ?: "unavailable (stream may not be active yet)"

    private fun format(channelMask: Int) = AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channelMask).build()

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val TAG = "ChirpAudio"
        fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            else -> "source $source"
        }
    }
}

/** Explicit LE conversion between Android's signed shorts and Dev 1's byte contract. */
internal object PcmAudio {
    fun encode(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { i, sample ->
            bytes[i * 2] = sample.toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
    }

    fun decode(bytes: ByteArray): ShortArray {
        require(bytes.size % 2 == 0)
        return ShortArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }

    data class Statistics(val peak: Double, val rms: Double, val clippedSamples: Int)
    fun statistics(samples: ShortArray): Statistics {
        if (samples.isEmpty()) return Statistics(0.0, 0.0, 0)
        var peak = 0.0
        var energy = 0.0
        var clipped = 0
        for (sample in samples) {
            val normalized = sample / 32768.0
            peak = maxOf(peak, abs(normalized))
            energy += normalized * normalized
            if (sample == Short.MIN_VALUE || sample == Short.MAX_VALUE) clipped++
        }
        return Statistics(peak, sqrt(energy / samples.size), clipped)
    }
}
