package com.example.chirp.sonar

import android.content.Context
import com.example.chirp.sonar.audio.DuplexAudioSession
import com.example.chirp.sonar.calibration.Baseline
import com.example.chirp.sonar.ranging.RangeCorrection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Connects the audio hardware to [SonarProcessor]. No UI types, so alerts can reuse it later. */
class SonarEngine(private val context: Context) {
    data class Status(
        val state: SonarState? = null,
        val message: String = "Idle.",
        val setupNotes: List<String> = emptyList(),
        val baseline: Baseline? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _frames = MutableSharedFlow<SonarFrame>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: SharedFlow<SonarFrame> = _frames.asSharedFlow()

    @Volatile private var processor: SonarProcessor? = null

    /** Runs until cancelled. Hardware problems surface as exceptions with user-readable messages. */
    suspend fun run(
        config: SonarConfig,
        correction: RangeCorrection = RangeCorrection(),
        adaptive: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        DuplexAudioSession(context, config).use { session ->
            val p = SonarProcessor(config, clock = session::frameTimeNs, initialCorrection = correction)
            p.adaptiveBackground = adaptive
            p.onFrame = { _frames.tryEmit(it) }
            p.onStatus = { state, message ->
                // A new calibration (or re-acquisition) invalidates the old baseline; don't keep showing it.
                _status.update {
                    it.copy(state = state, message = message, baseline = if (state == SonarState.RUNNING) it.baseline else null)
                }
            }
            p.onBaseline = { b -> _status.update { it.copy(baseline = b) } }
            _status.value = Status(SonarState.MEASURING_AMBIENT, "Measuring background noise (speaker silent)…")
            session.open(p.chirp)
            processor = p
            try {
                session.run(onReady = { notes -> _status.update { it.copy(setupNotes = notes) } }, onBlock = p::push)
            } finally {
                processor = null
            }
        }
    }

    fun recalibrate() { processor?.requestRecalibration() }

    fun setCorrection(correction: RangeCorrection) { processor?.correction = correction }

    fun setAdaptive(adaptive: Boolean) { processor?.adaptiveBackground = adaptive }
}
