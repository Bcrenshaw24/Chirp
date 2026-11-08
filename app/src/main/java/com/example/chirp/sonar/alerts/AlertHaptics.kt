package com.example.chirp.sonar.alerts

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibration for alert levels: slow pulses for CAUTION, rapid pulses for WARNING.
 * Changes the pattern only when the level changes. Vibration is used instead of sound
 * because any tone from the speaker would land in the sonar's own band.
 */
class AlertHaptics(context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }
    private var current = AlertLevel.NONE

    val available: Boolean get() = vibrator?.hasVibrator() == true

    fun show(level: AlertLevel) {
        if (level == current) return
        current = level
        val v = vibrator ?: return
        try {
            when (level) {
                AlertLevel.NONE -> v.cancel()
                AlertLevel.CAUTION -> v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 560), 0))
                AlertLevel.WARNING -> v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 110), 0))
            }
        } catch (_: RuntimeException) {
            // Losing the vibrator service must never take the sonar down.
        }
    }

    fun stop() = show(AlertLevel.NONE)
}
