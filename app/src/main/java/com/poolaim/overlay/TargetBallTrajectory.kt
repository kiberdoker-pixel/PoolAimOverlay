package com.poolaim.overlay

import kotlin.math.abs
import kotlin.math.hypot

/** Ideal specular ray geometry only; callers inset the rails by the ball radius. */
internal object TargetBallTrajectory {
    data class Point(val x: Float, val y: Float)

    /** Three reflections produce four legs: the initial leg and three reflected legs. */
    fun calculate(
        start: Point,
        direction: Point,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        maxBounces: Int = 3
    ): List<Point> {
        if (!listOf(start.x, start.y, direction.x, direction.y, left, top, right, bottom)
                .all { it.isFinite() } || left >= right || top >= bottom) return emptyList()
        if (start.x !in left..right || start.y !in top..bottom) return emptyList()
        val length = hypot(direction.x.toDouble(), direction.y.toDouble())
        if (length == 0.0) return emptyList()

        val l = left.toDouble()
        val t = top.toDouble()
        val r = right.toDouble()
        val b = bottom.toDouble()
        var x = start.x.toDouble()
        var y = start.y.toDouble()
        var dx = direction.x / length
        var dy = direction.y / length

        // Starting exactly on a rail and pointing outward is an immediate
        // reflection, not a zero-length segment in the visible trajectory.
        if ((x == l && dx < 0.0) || (x == r && dx > 0.0)) dx = -dx
        if ((y == t && dy < 0.0) || (y == b && dy > 0.0)) dy = -dy
        val result = mutableListOf(start)

        repeat(maxBounces.coerceIn(0, 3) + 1) {
            val tx = when {
                dx > 0.0 -> (r - x) / dx
                dx < 0.0 -> (l - x) / dx
                else -> Double.POSITIVE_INFINITY
            }
            val ty = when {
                dy > 0.0 -> (b - y) / dy
                dy < 0.0 -> (t - y) / dy
                else -> Double.POSITIVE_INFINITY
            }
            val distance = minOf(tx, ty)
            if (!distance.isFinite() || distance <= 0.0) return result
            // Relative tolerance handles exact corner hits after floating-point
            // normalization while retaining both components of the reflection.
            val tolerance = maxOf(1.0, distance) * 1e-9
            val hitX = abs(tx - distance) <= tolerance
            val hitY = abs(ty - distance) <= tolerance
            x = (x + dx * distance).coerceIn(l, r)
            y = (y + dy * distance).coerceIn(t, b)
            if (hitX) x = if (dx > 0.0) r else l
            if (hitY) y = if (dy > 0.0) b else t
            result.add(Point(x.toFloat(), y.toFloat()))
            if (hitX) dx = -dx
            if (hitY) dy = -dy
        }
        return result
    }
}
