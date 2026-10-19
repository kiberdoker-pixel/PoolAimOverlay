package com.poolaim.overlay

import kotlin.math.abs
import kotlin.math.hypot

internal object TargetBallTrajectory {
    data class Point(val x: Float, val y: Float)

    fun calculate(
        start: Point,
        direction: Point,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        maxBounces: Int = 3
    ): List<Point> {
        if (left >= right || top >= bottom) return emptyList()

        var x = start.x
        var y = start.y
        var dx = direction.x
        var dy = direction.y

        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (length < 0.0001f) return emptyList()

        dx /= length
        dy /= length

        if (x < left || x > right || y < top || y > bottom) return emptyList()

        val result = mutableListOf(Point(x, y))

        repeat(maxBounces.coerceIn(0, 3) + 1) {
            val tx = when {
                dx > 0.0001f -> (right - x) / dx
                dx < -0.0001f -> (left - x) / dx
                else -> Float.POSITIVE_INFINITY
            }
            val ty = when {
                dy > 0.0001f -> (bottom - y) / dy
                dy < -0.0001f -> (top - y) / dy
                else -> Float.POSITIVE_INFINITY
            }

            val t = minOf(tx, ty)
            if (!t.isFinite() || t < 0f) return result

            x += dx * t
            y += dy * t
            result.add(Point(x, y))

            if (abs(tx - t) < 0.001f) dx = -dx
            if (abs(ty - t) < 0.001f) dy = -dy
        }

        return result
    }
}
