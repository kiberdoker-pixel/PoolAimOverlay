package com.poolaim.overlay.recognition

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Fail-closed temporal confirmation of an already oriented object-ball guide.
 * Stability never supplies missing visual evidence or increases its score.
 * Call only with consecutive analyzed frames, using a monotonic millisecond clock.
 */
class GuideStabilityGate {
    companion object {
        const val MIN_SCORE = 0.90f
        private const val REQUIRED_FRAMES = 2
        private const val MAX_GAP_MS = 500L
        private const val POSITION_RADIUS_FRACTION = 1.5
        private val MIN_DIRECTION_COSINE = cos(Math.toRadians(5.0))
    }

    private var reference: FrameGuideResult? = null
    private var lastTimeMs: Long? = null
    private var consecutive = 0

    fun reset() {
        reference = null
        lastTimeMs = null
        consecutive = 0
    }

    fun filter(result: FrameGuideResult, nowMs: Long): FrameGuideResult {
        val guide = result.guide
        if (guide == null) {
            reset()
            return result
        }
        if (nowMs < 0L || !isValid(result, guide)) {
            reset()
            return result.copy(guide = null, reason = "Недостаточная уверенность или неверная геометрия линии")
        }

        val previousTime = lastTimeMs
        if (previousTime != null && nowMs <= previousTime) {
            reset()
            return result.copy(guide = null, reason = "Нарушен порядок кадров")
        }
        val previous = reference
        val continuous = previous != null && previousTime != null &&
            nowMs - previousTime <= MAX_GAP_MS && sameGuide(previous, result)
        if (continuous) {
            consecutive = (consecutive + 1).coerceAtMost(REQUIRED_FRAMES)
        } else {
            // The first frame remains the spatial reference: small successive
            // movements must not accumulate into a false lock.
            reference = result
            consecutive = 1
        }
        lastTimeMs = nowMs
        return if (consecutive >= REQUIRED_FRAMES) result.copy(
            reason = "Устойчивый кандидат линии · 3 отражения"
        ) else result.copy(
            guide = null, reason = "Подтверждение линии: $consecutive/$REQUIRED_FRAMES"
        )
    }

    private fun isValid(frame: FrameGuideResult, guide: RecognizedGuide): Boolean {
        if (frame.width <= 0 || frame.height <= 0 || !frame.bounds.isValid()) return false
        if (!guide.score.isFinite() || guide.score < MIN_SCORE || guide.score > 1f ||
            !guide.ballRadius.isFinite() || guide.ballRadius <= 0f) return false
        val b = frame.bounds
        val left = b.left.toDouble() * frame.width
        val top = b.top.toDouble() * frame.height
        val right = b.right.toDouble() * frame.width
        val bottom = b.bottom.toDouble() * frame.height
        if (left >= right || top >= bottom) return false
        fun inside(point: GuidePoint): Boolean = point.x.isFinite() && point.y.isFinite() &&
            point.x.toDouble() in left..right && point.y.toDouble() in top..bottom
        return inside(guide.origin) && inside(guide.tip) &&
            distance(guide.origin, guide.tip) > 0.001
    }

    private fun sameGuide(previous: FrameGuideResult, current: FrameGuideResult): Boolean {
        if (previous.width != current.width || previous.height != current.height ||
            previous.bounds != current.bounds) return false
        val a = previous.guide ?: return false
        val b = current.guide ?: return false
        val radius = min(a.ballRadius, b.ballRadius).toDouble()
        if (kotlin.math.abs(a.ballRadius - b.ballRadius) > radius * 0.2) return false
        if (distance(a.origin, b.origin) > radius * POSITION_RADIUS_FRACTION ||
            distance(a.tip, b.tip) > radius * POSITION_RADIUS_FRACTION) return false
        val ax = a.tip.x.toDouble() - a.origin.x
        val ay = a.tip.y.toDouble() - a.origin.y
        val bx = b.tip.x.toDouble() - b.origin.x
        val by = b.tip.y.toDouble() - b.origin.y
        return (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by)) >= MIN_DIRECTION_COSINE
    }

    private fun distance(a: GuidePoint, b: GuidePoint): Double =
        hypot(a.x.toDouble() - b.x, a.y.toDouble() - b.y)
}
