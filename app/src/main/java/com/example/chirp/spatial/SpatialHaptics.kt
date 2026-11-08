package com.example.chirp.spatial

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short guidance bursts for the spatial-memory screen. Callers must stop() when leaving the foreground. */
class SpatialHaptics(context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    /** Gentle finite burst. Call stop() to interrupt a burst. */
    fun pulse(pulseCount: Int, pulseMs: Long, gapMs: Long, strength: Int): Boolean = try {
        val device = vibrator
        if (device == null || !device.hasVibrator()) false
        else {
            require(pulseCount in 1..3 && pulseMs in 1..50 && gapMs in 0..200 && strength in 1..100)
            val amplitude = if (device.hasAmplitudeControl()) strength else VibrationEffect.DEFAULT_AMPLITUDE
            val timings = LongArray(pulseCount * 2) { index ->
                if (index == 0) 0 else if (index % 2 == 1) pulseMs else gapMs
            }
            val amplitudes = IntArray(timings.size) { if (it % 2 == 1) amplitude else 0 }
            device.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
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
