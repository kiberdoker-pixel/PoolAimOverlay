package com.poolaim.overlay

import android.media.Image
import kotlin.math.abs
import kotlin.math.max

/** Experimental image-only line candidates, NOT ball trajectory identification. */
internal object LineDetector {
    data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    data class Result(val summary: String, val segments: List<Segment>, val width: Int, val height: Int)

    fun inspect(image: Image): Result {
        val plane = image.planes.firstOrNull() ?: return Result("Нет пикселей", emptyList(), image.width, image.height)
        val w = image.width
        val h = image.height
        val stride = plane.pixelStride
        val row = plane.rowStride
        if (stride < 4 || w <= 0 || h <= 0) return Result("Неверный формат", emptyList(), w, h)
        val data = plane.buffer.duplicate()
        // Higher resolution than the original diagnostic sampling; keep work bounded.
        val step = max(2, max(w / 500, h / 260))
        val cols = (w + step - 1) / step
        val rows = (h + step - 1) / step
        val mask = BooleanArray(cols * rows)
        var turquoise = 0
        var white = 0
        val landscape = w > h
        for (y in 0 until rows) {
            val py = minOf(h - 1, y * step)
            for (x in 0 until cols) {
                val px = minOf(w - 1, x * step)
                val offset = py.toLong() * row + px.toLong() * stride
                if (offset + 2 >= data.limit()) continue
                val p = offset.toInt()
                val r = data.get(p).toInt() and 255
                val g = data.get(p + 1).toInt() and 255
                val b = data.get(p + 2).toInt() and 255
                if (g > 65 && b > 65 && g > r * 1.22 && b > r * 1.12) turquoise++
                val isWhite = r > 185 && g > 185 && b > 185 &&
                    maxOf(r, g, b) - minOf(r, g, b) < 48
                if (isWhite) white++
                // Ignore UI outside the approximate table interior; calibrate later.
                val inside = landscape && px > w * .19f && px < w * .81f &&
                    py > h * .21f && py < h * .92f
                mask[y * cols + x] = inside && isWhite
            }
        }
        data class Candidate(val seg: Segment, val score: Int)
        val candidates = ArrayList<Candidate>()
        // Four families of angles; candidates are deliberately approximate.
        val directions = arrayOf(1 to 0, 0 to 1, 1 to 1, 1 to -1, 2 to 1, 2 to -1, 1 to 2, 1 to -2)
        for ((dx, dy) in directions) {
            for (y in 0 until rows) for (x in 0 until cols) {
                if (!mask[y * cols + x]) continue
                val prevX = x - dx
                val prevY = y - dy
                if (prevX in 0 until cols && prevY in 0 until rows && mask[prevY * cols + prevX]) continue
                var cx = x
                var cy = y
                var lastX = x
                var lastY = y
                var hits = 0
                var misses = 0
                while (cx in 0 until cols && cy in 0 until rows && misses < 2) {
                    if (mask[cy * cols + cx]) {
                        hits++
                        lastX = cx
                        lastY = cy
                        misses = 0
                    } else misses++
                    cx += dx
                    cy += dy
                }
                if (hits >= 7 && (abs(lastX - x) + abs(lastY - y)) >= 9) {
                    candidates.add(Candidate(Segment(x * step.toFloat(), y * step.toFloat(),
                        lastX * step.toFloat(), lastY * step.toFloat()), hits))
                }
            }
        }
        // Avoid flooding the overlay with hundreds of duplicate candidate lines.
        val selected = ArrayList<Segment>()
        for (c in candidates.sortedByDescending { it.score }) {
            if (selected.size >= 12) break
            val s = c.seg
            val duplicate = selected.any {
                val direct = abs(it.x1 - s.x1) + abs(it.y1 - s.y1) + abs(it.x2 - s.x2) + abs(it.y2 - s.y2)
                val reverse = abs(it.x1 - s.x2) + abs(it.y1 - s.y2) + abs(it.x2 - s.x1) + abs(it.y2 - s.y1)
                minOf(direct, reverse) < step * 12
            }
            if (!duplicate) selected.add(s)
        }
        return Result("Бирюза:$turquoise Белые:$white Кандидаты:${selected.size}", selected, w, h)
    }
}
