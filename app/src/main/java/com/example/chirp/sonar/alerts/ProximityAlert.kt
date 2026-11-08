package com.example.chirp.sonar.alerts

import com.example.chirp.sonar.tracking.Track

enum class AlertLevel { NONE, CAUTION, WARNING }

data class Alert(val level: AlertLevel, val reason: String)

data class AlertConfig(
    val warningDistanceM: Double = 0.5,
    val cautionDistanceM: Double = 1.0,
    /** Time to contact (closing objects only) that triggers each level. */
    val warningTtcSec: Double = 1.0,
    val cautionTtcSec: Double = 2.5,
    /** To LOWER a level the object must be this much farther than the trigger distance... */
    val releaseMarginM: Double = 0.1,
    /** ...and its time to contact this many times longer than the trigger... */
    val releaseTtcFactor: Double = 1.5,
    /** ...continuously for this long. Raising is immediate. */
    val holdNs: Long = 600_000_000L,
)

/**
 * Turns the tracked object into an alert level.
 *
 * Only confirmed tracks count, so a single noisy echo never alerts. Levels go up at once
 * (low latency matters for a warning) but come down only after the condition has cleared
 * by a margin for [AlertConfig.holdNs], so the output doesn't flicker at a threshold.
 * Stateful: call once per period, in order.
 */
class ProximityAlert(private val config: AlertConfig = AlertConfig()) {
    private var level = AlertLevel.NONE
    private var reason = ""
    private var clearingSinceNs: Long? = null

    fun reset() { level = AlertLevel.NONE; reason = ""; clearingSinceNs = null }

    fun update(timeNs: Long, track: Track?): Alert {
        val (enterLevel, enterReason) = classify(track, relaxed = false)
        val (stayLevel, stayReason) = classify(track, relaxed = true)
        when {
            enterLevel > level -> { level = enterLevel; reason = enterReason; clearingSinceNs = null }
            stayLevel >= level -> { clearingSinceNs = null; if (stayLevel != AlertLevel.NONE) reason = stayReason }
            else -> {
                val since = clearingSinceNs ?: timeNs.also { clearingSinceNs = it }
                if (timeNs - since >= config.holdNs) { level = stayLevel; reason = stayReason; clearingSinceNs = null }
            }
        }
        return Alert(level, if (level == AlertLevel.NONE) "" else reason)
    }

    private fun classify(track: Track?, relaxed: Boolean): Pair<AlertLevel, String> {
        if (track == null || !track.confirmed) return AlertLevel.NONE to ""
        val margin = if (relaxed) config.releaseMarginM else 0.0
        val factor = if (relaxed) config.releaseTtcFactor else 1.0
        val d = track.distanceM
        val ttc = track.timeToContactSec
        val near = "Object at %.2f m".format(d)
        val closing = ttc?.let { "Closing: contact in %.1f s".format(it) } ?: ""
        return when {
            d <= config.warningDistanceM + margin -> AlertLevel.WARNING to near
            ttc != null && ttc <= config.warningTtcSec * factor -> AlertLevel.WARNING to closing
            d <= config.cautionDistanceM + margin -> AlertLevel.CAUTION to near
            ttc != null && ttc <= config.cautionTtcSec * factor -> AlertLevel.CAUTION to closing
            else -> AlertLevel.NONE to ""
        }
    }
}
