package com.example.chirp.sonar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chirp.sonar.alerts.AlertHaptics
import com.example.chirp.sonar.alerts.AlertLevel
import com.example.chirp.sonar.ranging.CorrectionStore
import com.example.chirp.sonar.ranging.RangeCorrection
import com.example.chirp.sonar.SonarConfig
import com.example.chirp.sonar.SonarEngine
import com.example.chirp.sonar.SonarFrame
import com.example.chirp.sonar.SonarState
import com.example.chirp.sonar.validation.AccuracyMeasurement
import com.example.chirp.sonar.validation.AccuracyPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryPoint(val timeNs: Long, val nearestM: Double?, val trackedM: Double?, val confirmed: Boolean)

/** Survives rotation, so a run isn't restarted (and recalibrated) just because the screen turned. */
class SonarViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = SonarEngine(app)
    private val haptics = AlertHaptics(app)
    val vibrationAvailable = haptics.available
    val status = engine.status

    private val _frame = MutableStateFlow<SonarFrame?>(null)
    val frame: StateFlow<SonarFrame?> = _frame.asStateFlow()
    private val _history = MutableStateFlow<List<HistoryPoint>>(emptyList())
    val history: StateFlow<List<HistoryPoint>> = _history.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _config = MutableStateFlow(SonarConfig.INAUDIBLE)
    val config: StateFlow<SonarConfig> = _config.asStateFlow()

    /** Vibrating shakes the phone, which can disturb the leakage the sonar subtracts; toggle to compare. */
    private val _vibrate = MutableStateFlow(true)
    val vibrate: StateFlow<Boolean> = _vibrate.asStateFlow()

    private val correctionStore = CorrectionStore(app)
    private val _correction = MutableStateFlow(correctionStore.load())
    val correction: StateFlow<RangeCorrection> = _correction.asStateFlow()

    /** Accuracy validation: the distance being measured and its progress, or null. */
    private val _measuring = MutableStateFlow<Pair<Double, Float>?>(null)
    val measuring: StateFlow<Pair<Double, Float>?> = _measuring.asStateFlow()
    private val _points = MutableStateFlow<List<AccuracyPoint>>(emptyList())
    val points: StateFlow<List<AccuracyPoint>> = _points.asStateFlow()
    private val _fitMessage = MutableStateFlow<String?>(null)
    val fitMessage: StateFlow<String?> = _fitMessage.asStateFlow()
    private var measurement: AccuracyMeasurement? = null

    /** Handheld mode (adaptive background); off = fixed calibration snapshot, best on a table. */
    private val _handheld = MutableStateFlow(true)
    val handheld: StateFlow<Boolean> = _handheld.asStateFlow()

    fun setHandheld(on: Boolean) { _handheld.value = on; engine.setAdaptive(on) }

    private var job: Job? = null

    init {
        viewModelScope.launch {
            engine.frames.collect { f ->
                _frame.value = f
                haptics.show(if (_vibrate.value && _running.value) f.alert?.level ?: AlertLevel.NONE else AlertLevel.NONE)
                if (f.state == SonarState.RUNNING) measurement?.let { m ->
                    m.add(f.detection?.echoes?.map { it.rawDistanceM to it.snrDb }.orEmpty())
                    _measuring.value = m.trueM to m.progress
                    if (m.isDone) {
                        _points.value = (_points.value.filter { it.trueM != m.trueM } + m.result()).sortedBy { it.trueM }
                        measurement = null
                        _measuring.value = null
                    }
                }
                if (f.state == SonarState.RUNNING) {
                    val point = HistoryPoint(f.timeNs, f.detection?.nearest?.distanceM, f.track?.distanceM, f.track?.confirmed == true)
                    _history.value = (_history.value + point).dropWhile { f.timeNs - it.timeNs > HISTORY_NS }
                }
            }
        }
    }

    fun start() {
        if (job?.isActive == true) return
        _error.value = null
        _history.value = emptyList()
        _frame.value = null
        _running.value = true
        job = viewModelScope.launch {
            try {
                engine.run(_config.value, _correction.value, _handheld.value)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _running.value = false
                haptics.stop()
            }
        }
    }

    fun stop() { job?.cancel(); job = null; haptics.stop(); cancelMeasurement() }

    fun setVibrate(on: Boolean) { _vibrate.value = on; if (!on) haptics.stop() }

    override fun onCleared() { haptics.stop() }

    fun recalibrate() { cancelMeasurement(); engine.recalibrate() }

    /** Start collecting readings with an object placed [trueM] (tape-measured) from the bottom edge. */
    fun measureAt(trueM: Double) {
        if (!_running.value) return
        measurement = AccuracyMeasurement(trueM)
        _measuring.value = trueM to 0f
        _fitMessage.value = null
    }

    fun cancelMeasurement() { measurement = null; _measuring.value = null }

    fun clearPoints() { _points.value = emptyList(); _fitMessage.value = null }

    fun fitCorrection() {
        val usable = _points.value.filter { it.readings.size >= MIN_READINGS }
        if (usable.isEmpty()) { _fitMessage.value = "Need at least one distance with $MIN_READINGS+ readings."; return }
        val fit = RangeCorrection.fit(usable.map { it.medianM to it.trueM })
        if (fit == null) {
            _fitMessage.value = "Fit rejected as implausible (scale outside ${RangeCorrection.PLAUSIBLE_SCALE} or offset over " +
                "${(RangeCorrection.MAX_OFFSET_M * 100).toInt()} cm). Some points probably measured the wrong reflector; clear and re-measure."
            return
        }
        val saved = applyCorrection(fit)
        _fitMessage.value = "Applied from ${usable.size} point(s)" +
            (if (fit.scale == 1.0) " (offset only: spread the distances by 0.4 m+ to fit scale too)" else "") +
            if (saved) " and saved." else ", but saving to storage FAILED; it will be lost when the app closes."
    }

    fun resetCorrection() {
        _fitMessage.value = if (applyCorrection(RangeCorrection())) "Correction reset." else "Reset, but saving to storage failed."
    }

    /** Applies live and persists. Returns whether it was saved. */
    private fun applyCorrection(c: RangeCorrection): Boolean {
        _correction.value = c
        engine.setCorrection(c)
        return correctionStore.save(c)
    }

    fun selectConfig(config: SonarConfig) { if (!_running.value) _config.value = config }

    fun reportError(message: String) { _error.value = message }

    companion object {
        const val HISTORY_NS = 15_000_000_000L
        const val MIN_READINGS = 5
    }
}
