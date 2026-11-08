package com.example.chirp.spatial

internal data class ProximityPattern(
    val pulseCount: Int,
    val pulseMs: Long,
    val gapMs: Long,
    val intervalMs: Long,
    val amplitude: Int,
)

/** Rhythms remain distinct on devices without amplitude control. No hardware repeat. */
internal fun proximityPattern(meters: Float?): ProximityPattern? {
    if (meters == null || !meters.isFinite() || meters < 0f) return null
    return when {
        meters <= 0.5f -> ProximityPattern(3, 25, 75, 650, 75)
        meters <= 2f -> ProximityPattern(2, 30, 110, 1200, 65)
        else -> ProximityPattern(1, 30, 0, 2200, 55)
    }
}

/** Monotonic-clock debounce plus cooldown. Initial successful tracking is a silent baseline. */
internal class TrackingAnnouncements {
    private var candidate: Boolean? = null
    private var candidateSince = 0L
    private var announced: Boolean? = null
    private var lastAnnouncement: Long? = null

    fun update(tracking: Boolean, nowMs: Long): String? {
        if (candidate != tracking) {
            candidate = tracking
            candidateSince = nowMs
        }
        if (nowMs - candidateSince < 1200) return null
        if (announced == null && tracking) { announced = true; return null }
        if (announced == tracking) return null
        if (lastAnnouncement?.let { nowMs - it < 4000 } == true) return null
        announced = tracking
        lastAnnouncement = nowMs
        return if (tracking) "Tracking restored. Location guidance is available."
        else "Tracking unavailable. Move slowly in good light. Location guidance is paused."
    }
}
