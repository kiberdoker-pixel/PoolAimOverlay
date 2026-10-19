package com.poolaim.overlay

import android.media.Image
import kotlin.math.*

/** Experimental geometric selection. The chosen line is only a hypothesis, not verified ball identification. */
internal object LineDetector {
    data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    data class Result(val summary: String, val segments: List<Segment>, val width: Int, val height: Int, val cueX: Float? = null, val cueY: Float? = null)

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
                val area = TableBoundsOverlay.bounds
                val inside = landscape && px >= w * area[0] && px <= w * area[2] &&
                    py >= h * area[1] && py <= h * area[3]
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
        // Keep only strong peaks, then split each line into connected runs.
        val peaks = votes.indices.asSequence().filter { votes[it] >= 7 }
            .sortedByDescending { votes[it] }.take(180).toList()
        val segments = ArrayList<Segment>()
        for (index in peaks) {
            if (segments.size >= 120) break
            val a = index / rhoCount
            val rho = index % rhoCount - diag
            val nx = cosines[a]
            val ny = sines[a]
            val tx = -ny
            val ty = nx
            val positions = ArrayList<Double>()
            for (y in 0 until rows) for (x in 0 until cols) {
                if (!bright[y * cols + x]) continue
                if (abs(x * nx + y * ny - rho) > 1.0) continue
                positions.add(x * tx + y * ty)
            }
            if (positions.size < 7) continue
            positions.sort()
            var start = 0
            for (i in 1..positions.size) {
                val endRun = i == positions.size || positions[i] - positions[i - 1] > 8.0
                if (!endRun) continue
                val count = i - start
                val minT = positions[start]
                val maxT = positions[i - 1]
                val length = maxT - minT
                if (count >= 6 && length >= 7.0 && count / (length + 1.0) >= 0.30) {
                    val x1 = ((rho * nx + minT * tx) * step).toFloat()
                    val y1 = ((rho * ny + minT * ty) * step).toFloat()
                    val x2 = ((rho * nx + maxT * tx) * step).toFloat()
                    val y2 = ((rho * ny + maxT * ty) * step).toFloat()
                    val duplicate = segments.any { s ->
                        val d1 = hypot((s.x1 - x1).toDouble(), (s.y1 - y1).toDouble()) +
                            hypot((s.x2 - x2).toDouble(), (s.y2 - y2).toDouble())
                        val d2 = hypot((s.x1 - x2).toDouble(), (s.y1 - y2).toDouble()) +
                            hypot((s.x2 - x1).toDouble(), (s.y2 - y1).toDouble())
                        val sameEndpoints = min(d1, d2) < 55.0
                        val adx = x2 - x1
                        val ady = y2 - y1
                        val bdx = s.x2 - s.x1
                        val bdy = s.y2 - s.y1
                        val al = hypot(adx.toDouble(), ady.toDouble())
                        val bl = hypot(bdx.toDouble(), bdy.toDouble())
                        val parallel = al > 0 && bl > 0 &&
                            abs((adx * bdx + ady * bdy) / (al * bl)) > 0.985
                        val mx = (x1 + x2) / 2.0
                        val my = (y1 + y2) / 2.0
                        val bx = (s.x1 + s.x2) / 2.0
                        val by = (s.y1 + s.y2) / 2.0
                        val separation = if (bl > 0) abs((mx - bx) * bdy - (my - by) * bdx) / bl else 1e9
                        val overlap = if (bl > 0) {
                            val t1 = ((x1 - s.x1) * bdx + (y1 - s.y1) * bdy) / bl
                            val t2 = ((x2 - s.x1) * bdx + (y2 - s.y1) * bdy) / bl
                            max(0.0, min(max(t1, t2), bl) - max(min(t1, t2), 0.0))
                        } else 0.0
                        sameEndpoints || (parallel && separation < 12.0 && overlap > min(al, bl) * 0.5)
                    }
                    if (!duplicate) segments.add(Segment(x1, y1, x2, y2))
                }
                if (segments.size >= 120) break
                start = i
            }
        }
        // Stage 1 diagnostic: detect the longest bright straight segment only.
        // This does NOT yet prove that the segment belongs to the cue-ball guide.
        fun segmentLength(s: Segment): Double = hypot(
            (s.x2 - s.x1).toDouble(), (s.y2 - s.y1).toDouble()
        )
        val longest = segments.maxByOrNull { segmentLength(it) }
        val longestLength = longest?.let { segmentLength(it) } ?: 0.0
        val minGuideLength = 110.0
        val chosen = if (longestLength >= minGuideLength) longest else null
        // Cue-ball diagnostic: look for a solid white disk close to either end
        // of the long guide. No cue-ball coordinate is fabricated if uncertain.
        fun whiteAt(px: Int, py: Int): Boolean {
            if (px !in 0 until w || py !in 0 until h) return false
            val offset = py.toLong() * rowStride + px.toLong() * pixelStride
            if (offset + 2 >= buffer.limit()) return false
            val p = offset.toInt()
            val r = buffer.get(p).toInt() and 255
            val g = buffer.get(p + 1).toInt() and 255
            val b = buffer.get(p + 2).toInt() and 255
            return r > 185 && g > 185 && b > 185 &&
                max(r, max(g, b)) - min(r, min(g, b)) < 48
        }
        var cueX: Float? = null
        var cueY: Float? = null
        var bestBallScore = 0.0
        if (chosen != null) {
            val bounds = TableBoundsOverlay.bounds
            val endpoints = arrayOf(floatArrayOf(chosen.x1, chosen.y1),
                floatArrayOf(chosen.x2, chosen.y2))
            // The cue ball is typically ~25-35 capture pixels in radius.
            // Sample the inner disk rather than its dark outline.
            val offsets = arrayOf(
                intArrayOf(0, 0), intArrayOf(-9, 0), intArrayOf(9, 0),
                intArrayOf(0, -9), intArrayOf(0, 9),
                intArrayOf(-12, -12), intArrayOf(12, -12),
                intArrayOf(-12, 12), intArrayOf(12, 12),
                intArrayOf(-18, 0), intArrayOf(18, 0),
                intArrayOf(0, -18), intArrayOf(0, 18)
            )
            for (end in endpoints) {
                for (dy in -52..52 step 8) for (dx in -52..52 step 8) {
                    val cx = (end[0] + dx).toInt()
                    val cy = (end[1] + dy).toInt()
                    if (cx < w * bounds[0] + 24 || cx > w * bounds[2] - 24 ||
                        cy < h * bounds[1] + 24 || cy > h * bounds[3] - 24) continue
                    var whiteCount = 0
                    for (offset in offsets) if (whiteAt(cx + offset[0], cy + offset[1])) whiteCount++
                    val fraction = whiteCount.toDouble() / offsets.size
                    val dist = hypot(dx.toDouble(), dy.toDouble())
                    // Solid white ball beats a thin white guide crossing the sample.
                    val score = fraction - dist * 0.0012
                    if (fraction >= 0.77 && score > bestBallScore) {
                        bestBallScore = score
                        cueX = cx.toFloat()
                        cueY = cy.toFloat()
                    }
                }
            }
        }
        val summary = "Размер:${w}x${h} Шаг:$step Белые:$white " +
            "Отрезки:${segments.size} Макс:${longestLength.roundToInt()}px " +
            "Длинная:${if (chosen != null) "кандидат" else "нет"} " +
            "Биток:${if (cueX != null) "гипотеза" else "не найден"}"
        return Result(summary, chosen?.let { listOf(it) } ?: emptyList(), w, h, cueX, cueY)
    }
}
