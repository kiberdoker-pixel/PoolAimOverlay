package com.poolaim.overlay.recognition

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Local evidence that a traced endpoint left the side of a still-continuing white stripe. */
internal object GuideEndpointSupport {
    fun continuesForward(
        mask: ByteArray,
        width: Int,
        height: Int,
        tipX: Double,
        tipY: Double,
        directionX: Double,
        directionY: Double,
        radius: Double
    ): Boolean {
        if (width <= 0 || height <= 0 ||
            width.toLong() * height > mask.size.toLong() ||
            listOf(tipX, tipY, directionX, directionY, radius).any { !it.isFinite() } ||
            radius <= 0.0 || radius > min(width, height)) return false
        val directionLength = hypot(directionX, directionY)
        if (directionLength < 1e-6) return false
        val ux = directionX / directionLength
        val uy = directionY / directionLength
        val crossLimit = .4 * radius
        val requiredForward = max(3.0, .35 * radius)
        if (requiredForward > radius) return false

        // A bounded local window cannot connect nearby branches through their shared
        // origin far behind this endpoint. All distances are processing-ROI pixels.
        val extent = radius + 3.0
        val left = max(0, floor(tipX - extent).toInt())
        val top = max(0, floor(tipY - extent).toInt())
        val right = min(width - 1, ceil(tipX + extent).toInt())
        val bottom = min(height - 1, ceil(tipY + extent).toInt())
        if (left > right || top > bottom) return false
        val localWidth = right - left + 1
        val localHeight = bottom - top + 1
        val area = localWidth.toLong() * localHeight
        if (area > Int.MAX_VALUE) return false

        fun whiteInWindow(x: Int, y: Int): Boolean {
            if (x !in left..right || y !in top..bottom || mask[y * width + x].toInt() == 0)
                return false
            val dx = x - tipX
            val dy = y - tipY
            val projected = dx * ux + dy * uy
            val transverse = dx * -uy + dy * ux
            return projected >= -2.0 && projected <= radius &&
                transverse >= -crossLimit && transverse <= crossLimit
        }

        // These are exactly the perpendicular probes used by the recognizer's
        // whiteNear(tip). Never seed a wider strip or a different nearby white object.
        val seeds = listOf(0, -1, 1).map { offset ->
            (tipX - uy * offset).roundToInt() to (tipY + ux * offset).roundToInt()
        }.distinct().filter { (x, y) -> whiteInWindow(x, y) }
            .sortedBy { (x, y) -> (x - tipX) * (x - tipX) + (y - tipY) * (y - tipY) }
        if (seeds.isEmpty()) return false

        fun localIndex(x: Int, y: Int) = (y - top) * localWidth + x - left
        val visited = BooleanArray(area.toInt())
        val queue = IntArray(area.toInt())
        val first = seeds.first()
        val firstIndex = localIndex(first.first, first.second)
        visited[firstIndex] = true
        queue[0] = firstIndex
        var head = 0
        var tail = 1
        var continues = false
        while (head < tail) {
            val index = queue[head++]
            val x = left + index % localWidth
            val y = top + index / localWidth
            if ((x - tipX) * ux + (y - tipY) * uy >= requiredForward) continues = true
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nextX = x + dx
                    val nextY = y + dy
                    if (!whiteInWindow(nextX, nextY)) continue
                    val nextIndex = localIndex(nextX, nextY)
                    if (!visited[nextIndex]) {
                        visited[nextIndex] = true
                        queue[tail++] = nextIndex
                    }
                }
            }
        }
        // If the trace probes touch disconnected components, endpoint identity is
        // itself uncertain. Keep the hypothesis rather than erase competing evidence.
        return continues && seeds.all { (x, y) -> visited[localIndex(x, y)] }
    }
}
