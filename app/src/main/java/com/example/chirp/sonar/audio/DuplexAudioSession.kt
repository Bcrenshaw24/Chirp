package com.example.chirp.sonar.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.chirp.sonar.SonarConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Owns the speaker and microphone for one sonar run.
 *
 * Speaker: a MODE_STATIC AudioTrack looping one period (chirp + silence) forever. The mixer
 * replays it with no app CPU, no refills and no underruns, and every chirp is exactly one
 * period after the last on the shared audio clock. That regularity is what lets the
 * processor align periods without trusting any Android latency figure.
 *
 * Microphone: continuous AudioRecord, UNPROCESSED source when the device allows it (fallback
 * VOICE_RECOGNITION), with AGC / noise suppression / echo cancellation switched off where
 * permitted. Each of those would otherwise treat our chirp as something to remove.
 *
 * Recording starts first; the speaker starts after [SonarConfig.ambientFrames] so the
 * processor can measure the background with the speaker silent.
 */
class DuplexAudioSession(context: Context, private val config: SonarConfig) : AutoCloseable {
    private val context = context.applicationContext
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val rate = config.sampleRateHz

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private val effects = mutableListOf<AudioEffect>()
    private val notes = mutableListOf<String>()

    private var stampFrame = 0L
    private var stampNs = 0L
    @Volatile private var hasStamp = false

    /** Throws with a user-readable message if the hardware can't be set up as required. */
    fun open(chirp: DoubleArray) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) { "Microphone permission is required." }
        check(audioManager.mode == AudioManager.MODE_NORMAL) { "End the call or voice chat first." }

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
        val speaker = devices.firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: error("Built-in speaker unavailable.")
        val mic = devices.firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: error("Built-in microphone unavailable.")

        // --- Microphone ---
        val inMin = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(inMin > 0) { "$rate Hz mono PCM16 capture unsupported." }
        val inBytes = maxOf(inMin * 2, rate / 5 * 2) // >= 200 ms of slack against scheduling stalls
        val advertised = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.equals("true", ignoreCase = true) == true
        notes += "UNPROCESSED advertised: $advertised"
        // Tried even when not advertised: the old capture experiments found it can still initialise.
        val input = listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .firstNotNullOfOrNull { source ->
                runCatching {
                    AudioRecord.Builder().setAudioSource(source).setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                        .setBufferSizeInBytes(inBytes).build()
                        .also { if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("not initialised") } }
                }.onFailure { if (it is SecurityException) throw it; notes += "${sourceName(source)} failed: ${it.message}" }
                    .getOrNull()
            } ?: error("Microphone could not be initialised.")
        recorder = input
        check(input.sampleRate == rate) { "Microphone runs at ${input.sampleRate} Hz, need $rate Hz." }
        input.setPreferredDevice(mic)
        notes += "Source: ${sourceName(input.audioSource)}, buffer ${input.bufferSizeInFrames} frames"
        disableEffects(input.audioSessionId)

        // --- Speaker ---
        val period = ChirpGenerator.chirpTrainPeriod(chirp, config.periodFrames)
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(period.size * 2)
            .build()
        track = output
        check(output.state == AudioTrack.STATE_NO_STATIC_DATA || output.state == AudioTrack.STATE_INITIALIZED) {
            "Speaker could not be initialised."
        }
        check(output.write(period, 0, period.size) == period.size) { "Could not load the chirp into the speaker." }
        check(output.setLoopPoints(0, period.size, -1) == AudioTrack.SUCCESS) { "Speaker looping unsupported." }
        output.setPreferredDevice(speaker)
        notes += "Speaker: static loop of ${period.size} frames (${config.periodMs} ms)"
    }

    /**
     * Blocks (cooperatively cancellable) delivering mic audio in 10 ms blocks until cancelled.
     * [onReady] receives setup notes once the speaker has started and routes are known.
     */
    suspend fun run(onReady: (List<String>) -> Unit, onBlock: (ShortArray, Int) -> Unit) {
        val input = checkNotNull(recorder) { "open() first" }
        val output = checkNotNull(track)
        input.startRecording()
        check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "Microphone did not start (another app may be using it)."
        }
        val block = ShortArray(rate / 100)
        val stamp = AudioTimestamp()
        var total = 0L
        var reads = 0
        var playing = false
        var routesChecked = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
            check(n >= 0) { "Microphone read failed (code $n)." }
            if (n == 0) continue
            onBlock(block, n)
            total += n
            if (!playing && total >= config.ambientFrames) {
                output.play()
                playing = true
            }
            if (playing && !routesChecked && total >= config.ambientFrames + rate / 5) {
                routesChecked = true
                checkRoutes(input, output)
                onReady(notes.toList())
            }
            if (++reads % 10 == 0 &&
                input.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                stampFrame = stamp.framePosition; stampNs = stamp.nanoTime; hasStamp = true
            }
        }
    }

    /** Monotonic ns at which mic frame [index] was captured, from the latest hardware timestamp. */
    fun frameTimeNs(index: Long): Long? =
        if (!hasStamp) null else stampNs + ((index - stampFrame) * 1_000_000_000L) / rate

    override fun close() {
        runCatching { track?.stop() }
        runCatching { recorder?.stop() }
        effects.forEach { runCatching { it.release() } }
        runCatching { track?.release() }
        runCatching { recorder?.release() }
        effects.clear(); track = null; recorder = null
        Log.i(TAG, "Audio resources released")
    }

    private fun checkRoutes(input: AudioRecord, output: AudioTrack) {
        val inRoute = input.routedDevice
        val outRoute = output.routedDevice
        notes += "Input route: ${inRoute?.let { "${it.productName} (type ${it.type})" } ?: "unknown"}"
        notes += "Output route: ${outRoute?.let { "${it.productName} (type ${it.type})" } ?: "unknown"}"
        check(inRoute == null || inRoute.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            "Audio is routed to an external microphone. Disconnect headsets/Bluetooth."
        }
        check(outRoute == null || outRoute.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            "Audio is routed to an external speaker. Disconnect headsets/Bluetooth."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { input.activeMicrophones }.getOrNull()?.forEach { m ->
                notes += "Active mic: ${m.description} id=${m.id} pos=${m.position.let { "(%.3f, %.3f, %.3f)".format(it.x, it.y, it.z) }}"
            }
        }
        notes.forEach { Log.i(TAG, it) }
    }

    private fun disableEffects(session: Int) {
        fun handle(name: String, available: Boolean, create: () -> AudioEffect?) {
            if (!available) { notes += "$name: not present"; return }
            try {
                val effect = create()
                if (effect == null) { notes += "$name: present, not attachable"; return }
                effects += effect
                val wasOn = effect.enabled
                if (wasOn) effect.setEnabled(false)
                notes += "$name: was ${if (wasOn) "ON" else "off"}, now ${if (effect.enabled) "STILL ON" else "off"}"
            } catch (e: RuntimeException) {
                notes += "$name: could not control (${e.message})"
            }
        }
        handle("AGC", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(session) }
        handle("Noise suppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(session) }
        handle("Echo canceller", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(session) }
    }

    private fun format(channelMask: Int) = AudioFormat.Builder().setSampleRate(rate)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channelMask).build()

    companion object {
        private const val TAG = "ChirpAudio"
        fun sourceName(source: Int) = when (source) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            else -> "source $source"
        }
    }
}
