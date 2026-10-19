package com.poolaim.overlay

import android.media.Image
import kotlin.math.*

/** Experimental visual diagnostics. Candidates are NOT identified ball trajectories. */
internal object LineDetector {
    data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    data class Result(val summary: String, val segments: List<Segment>, val width: Int, val height: Int)

    fun inspect(image: Image): Result {
        val w = image.width
        val h = image.height
        val plane = image.planes.firstOrNull() ?: return Result("Нет пикселей", emptyList(), w, h)
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (w < 1 || h < 1 || pixelStride < 4) return Result("Неверный формат", emptyList(), w, h)
        val buffer = plane.buffer.duplicate()
        val step = 4 // Diagnostic sampling: finer than the previous 10px
        val cols = (w + step - 1) / step
        val rows = (h + step - 1) / step
        val bright = BooleanArray(cols * rows)
        var turquoise = 0
        var white = 0
        val landscape = w > h
        for (y in 0 until rows) {
            val py = min(h - 1, y * step)
            for (x in 0 until cols) {
                val px = min(w - 1, x * step)
                val pLong = py.toLong() * rowStride + px.toLong() * pixelStride
                if (pLong + 2 >= buffer.limit()) continue
                val p = pLong.toInt()
                val r = buffer.get(p).toInt() and 255
                val g = buffer.get(p + 1).toInt() and 255
                val b = buffer.get(p + 2).toInt() and 255
                if (g > 65 && b > 65 && g > r * 1.22 && b > r * 1.12) turquoise++
                val isWhite = r > 175 && g > 175 && b > 175 &&
                    max(r, max(g, b)) - min(r, min(g, b)) < 65
                if (isWhite) white++
                val inside = if (landscape) {
                    px > w * .19f && px < w * .81f &&
                        py > h * .21f && py < h * .92f
                } else {
                    // Capture may report portrait dimensions even while the game is landscape.
                    px > w * .06f && px < w * .94f &&
                        py > h * .06f && py < h * .94f
                }
                bright[y * cols + x] = inside && isWhite
            }
        }

        // Hough voting allows arbitrary line angles and tolerates broken thin lines.
        val angleCount = 90
        val cosines = DoubleArray(angleCount) { cos(it * PI / angleCount) }
        val sines = DoubleArray(angleCount) { sin(it * PI / angleCount) }
        val diag = hypot(cols.toDouble(), rows.toDouble()).toInt() + 2
        val rhoCount = 2 * diag + 1
        val votes = IntArray(angleCount * rhoCount)
        for (y in 0 until rows) for (x in 0 until cols) {
            if (!bright[y * cols + x]) continue
            for (a in 0 until angleCount) {
                val rho = (x * cosines[a] + y * sines[a]).roundToInt() + diag
                if (rho in 0 until rhoCount) votes[a * rhoCount + rho]++
            }
        }
        val peaks = votes.indices.filter { votes[it] >= 8 }
            .sortedByDescending { votes[it] }.take(100)
        val segments = ArrayList<Segment>()
        for (index in peaks) {
            if (segments.size >= 12) break
            val a = index / rhoCount
            val rho = index % rhoCount - diag
            val nx = cosines[a]
            val ny = sines[a]
            val tx = -ny
            val ty = nx
            var minT = Double.POSITIVE_INFINITY
            var maxT = Double.NEGATIVE_INFINITY
            var hits = 0
            for (y in 0 until rows) for (x in 0 until cols) {
                if (!bright[y * cols + x]) continue
                if (abs(x * nx + y * ny - rho) > 1.15) continue
                val t = x * tx + y * ty
                minT = min(minT, t)
                maxT = max(maxT, t)
                hits++
            }
            if (hits < 8 || maxT - minT < 12.0) continue
            // Reject isolated dots accidentally aligned across a large region.
            if (hits.toDouble() / (maxT - minT + 1.0) < 0.18) continue
            val x1 = ((rho * nx + minT * tx) * step).toFloat()
            val y1 = ((rho * ny + minT * ty) * step).toFloat()
            val x2 = ((rho * nx + maxT * tx) * step).toFloat()
            val y2 = ((rho * ny + maxT * ty) * step).toFloat()
            val duplicate = segments.any { s ->
                val d1 = hypot((s.x1 - x1).toDouble(), (s.y1 - y1).toDouble()) +
                    hypot((s.x2 - x2).toDouble(), (s.y2 - y2).toDouble())
                val d2 = hypot((s.x1 - x2).toDouble(), (s.y1 - y2).toDouble()) +
                    hypot((s.x2 - x1).toDouble(), (s.y2 - y1).toDouble())
                min(d1, d2) < 55.0
            }
            if (!duplicate) segments.add(Segment(x1, y1, x2, y2))
        }
        return Result("Размер:${w}x${h} Шаг:$step Белые:$white Кандидаты:${segments.size}", segments, w, h)
    }
}
