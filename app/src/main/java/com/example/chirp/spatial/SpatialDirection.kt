package com.example.chirp.spatial

import kotlin.math.*

/** Coordinates in the camera's physical pose: right +X, up +Y, forward -Z. */
internal fun spatialDirection(x: Float, y: Float, z: Float): String {
    val distance = sqrt(x * x + y * y + z * z)
    val angle = Math.toDegrees(atan2(x.toDouble(), -z.toDouble()))
    val horizontal = when {
        abs(angle) <= 20 -> "ahead"
        abs(angle) >= 160 -> "behind"
        angle > 0 -> "to your right"
        else -> "to your left"
    }
    val vertical = when {
        y > 0.3f -> ", above phone level"
        y < -0.3f -> ", below phone level"
        else -> ""
    }
    return String.format(java.util.Locale.getDefault(), "Approximately %.1f meters, %s%s", distance, horizontal, vertical)
}
