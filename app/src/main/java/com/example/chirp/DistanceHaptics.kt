package com.example.chirp

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Owns physical feedback; callers must stop it when scanning or foreground use ends. */
class DistanceHaptics(context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    fun showDistance(meters: Double): Boolean = try {
        val device = vibrator
        if (device == null || !device.hasVibrator()) {
            false
        } else {
            val proximity = ((3.0 - meters) / 2.5).coerceIn(0.0, 1.0)
            val pauseMs = (700 - 540 * proximity).toLong()
            val amplitude = if (device.hasAmplitudeControl()) {
                (70 + 185 * proximity).toInt()
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            device.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 65, pauseMs), intArrayOf(0, amplitude, 0), 0
                )
            )
            true
        }
    } catch (_: RuntimeException) {
        stop()
        false
    }

    fun stop() {
        try {
            vibrator?.cancel()
        } catch (_: RuntimeException) {
            // Losing hardware/service access must not prevent lifecycle cleanup.
        }
    }
}
