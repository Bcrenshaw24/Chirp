package com.example.chirp.sonar.ranging

import android.content.Context

/**
 * Persists the fitted [RangeCorrection] in app-private storage. Survives restarts and
 * reinstalls with `installDebug`; only uninstalling or clearing app data removes it.
 */
class CorrectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): RangeCorrection {
        val scale = prefs.getFloat(KEY_SCALE, 1f).toDouble()
        val offset = prefs.getFloat(KEY_OFFSET, 0f).toDouble()
        // Never let a corrupted value break ranging.
        return if (scale.isFinite() && offset.isFinite() && scale > 0) RangeCorrection(scale, offset) else RangeCorrection()
    }

    /** Written synchronously so a correction can't be lost if the app is killed right after Fit. */
    fun save(correction: RangeCorrection): Boolean =
        prefs.edit()
            .putFloat(KEY_SCALE, correction.scale.toFloat())
            .putFloat(KEY_OFFSET, correction.offsetM.toFloat())
            .commit()

    private companion object {
        const val FILE = "range_correction"
        const val KEY_SCALE = "scale"
        const val KEY_OFFSET = "offset_m"
    }
}
