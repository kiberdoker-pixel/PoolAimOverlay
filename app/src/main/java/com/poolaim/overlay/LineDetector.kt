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
        // Stage 2: independently detect a bright, approximately circular cue ball.
        // No reliance on guide endpoints; no tracking or fabricated coordinates.
        val bounds = TableBoundsOverlay.bounds
        val minX = (w * bounds[0]).toInt().coerceAtLeast(0) + 38
        val maxX = (w * bounds[2]).toInt().coerceAtMost(w - 1) - 38
        val minY = (h * bounds[1]).toInt().coerceAtLeast(0) + 38
        val maxY = (h * bounds[3]).toInt().coerceAtMost(h - 1) - 38
        // The screenshots show a cue-ball diameter of roughly 55-65 capture px.
        // Multiple radii tolerate differences between phones and game scaling.
        val radii = intArrayOf(22, 27, 32)
        val insidePoints = arrayOf(
            intArrayOf(0, 0), intArrayOf(-10, 0), intArrayOf(10, 0),
            intArrayOf(0, -10), intArrayOf(0, 10),
            intArrayOf(-15, -9), intArrayOf(15, -9),
            intArrayOf(-15, 9), intArrayOf(15, 9),
            intArrayOf(-20, 0), intArrayOf(20, 0),
            intArrayOf(0, -20), intArrayOf(0, 20)
        )
        val ringPoints = Array(16) { i ->
            val theta = i * 2.0 * PI / 16.0
            doubleArrayOf(cos(theta), sin(theta))
        }
        fun whiteAt(px: Int, py: Int): Boolean {
            if (px !in 0 until w || py !in 0 until h) return false
            val pos = py.toLong() * rowStride + px.toLong() * pixelStride
            if (pos + 2 >= buffer.limit()) return false
            val p = pos.toInt()
            val r = buffer.get(p).toInt() and 255
            val g = buffer.get(p + 1).toInt() and 255
            val b = buffer.get(p + 2).toInt() and 255
            return r > 180 && g > 180 && b > 180 &&
                max(r, max(g, b)) - min(r, min(g, b)) < 55
        }
        fun ballScore(cx: Int, cy: Int, radius: Int): Double {
            // Centre marking can be dark, so do not require a white centre pixel.
            var inner = 0
            for (p in insidePoints) {
                val dx = p[0] * radius / 27
                val dy = p[1] * radius / 27
                if (whiteAt(cx + dx, cy + dy)) inner++
            }
            val innerFraction = inner.toDouble() / insidePoints.size
            if (innerFraction < 0.69) return -1.0
            // Outside the disk should not be another broad white object.
            var outer = 0
            for (p in ringPoints) {
                val dx = (p[0] * (radius + 9)).roundToInt()
                val dy = (p[1] * (radius + 9)).roundToInt()
                if (whiteAt(cx + dx, cy + dy)) outer++
            }
            val outerFraction = outer.toDouble() / ringPoints.size
            if (outerFraction > 0.32) return -1.0
            return innerFraction - outerFraction * 0.45
        }
        // Cue evidence: the dark shaft must approach the candidate ball, while
        // the white aiming guide must leave it on the opposite side.
        fun shaftPixel(px: Int, py: Int): Boolean {
            if (px !in 0 until w || py !in 0 until h) return false
            val pos = py.toLong() * rowStride + px.toLong() * pixelStride
            if (pos + 2 >= buffer.limit()) return false
            val at = pos.toInt()
            val r = buffer.get(at).toInt() and 255
            val g = buffer.get(at + 1).toInt() and 255
            val b = buffer.get(at + 2).toInt() and 255
            return r < 120 && g < 110 && b < 125
        }
        fun cueAlignment(cx: Int, cy: Int): Double {
            var strongest = 0.0
            for (a in 0 until 36) {
                val angle = a * PI / 18.0
                val dx = cos(angle)
                val dy = sin(angle)
                var shaftHits = 0
                var shaftNear = 0
                var guideHits = 0
                // Dark cue shaft is behind the ball; allow the tip gap.
                for (d in 42..180 step 6) {
                    val x = (cx - dx * d).roundToInt()
                    val y = (cy - dy * d).roundToInt()
                    if (shaftPixel(x, y)) {
                        shaftHits++
                        if (d <= 90) shaftNear++
                    }
                }
                // White guide should be on the forward side.
                for (d in 40..165 step 5) {
                    if (whiteAt((cx + dx * d).roundToInt(),
                                (cy + dy * d).roundToInt())) guideHits++
                }
                val shaftFraction = shaftHits / 24.0
                val guideFraction = guideHits / 26.0
                if (shaftNear >= 3 && shaftFraction >= 0.38 && guideFraction >= 0.25) {
                    val confidence = shaftFraction * 0.55 + guideFraction * 0.45
                    if (confidence > strongest) strongest = confidence
                }
            }
            return strongest
        }
        var cueX: Float? = null
        var cueY: Float? = null
        var bestBallScore = 0.0
        var candidateCount = 0
        var distinctCount = 0
        var alignedCount = 0
        var maxAlignment = 0.0
        // Retain spatially distinct circles instead of just the brightest one.
        data class BallCandidate(val x: Int, val y: Int, val score: Double)
        val candidates = ArrayList<BallCandidate>()
        // A coarse search across the entire playable cloth, then local refinement.
        // We intentionally do not use the chosen red segment here.
        if (landscape && minX < maxX && minY < maxY) {
            for (cy in minY..maxY step 12) for (cx in minX..maxX step 12) {
                // At least some white in the inner area is required.
                if (!whiteAt(cx, cy) && !whiteAt(cx + 10, cy) &&
                    !whiteAt(cx - 10, cy) && !whiteAt(cx, cy + 10)) continue
                for (radius in radii) {
                    val score = ballScore(cx, cy, radius)
                    if (score > 0.0) candidateCount++
                    if (score >= 0.78) candidates.add(BallCandidate(cx, cy, score))
                }
            }
            // Limit expensive shaft checks to the strongest separated candidates.
            val distinct = ArrayList<BallCandidate>()
            for (candidate in candidates.sortedByDescending { it.score }) {
                if (distinct.none { hypot((it.x - candidate.x).toDouble(),
                            (it.y - candidate.y).toDouble()) < 48.0 }) {
                    distinct.add(candidate)
                    if (distinct.size >= 35) break
                }
            }
            distinctCount = distinct.size
            var bestCombined = 0.0
            for (candidate in distinct) {
                val alignment = cueAlignment(candidate.x, candidate.y)
                if (alignment > maxAlignment) maxAlignment = alignment
                if (alignment <= 0.0) continue
                alignedCount++
                val combined = alignment * 0.8 + candidate.score * 0.2
                if (combined > bestCombined) {
                    bestCombined = combined
                    bestBallScore = candidate.score
                    cueX = candidate.x.toFloat()
                    cueY = candidate.y.toFloat()
                }
            }
            // Refine only the selected cue-aligned circle; keep the shaft test
            // at coarse coordinates so tiny shifts do not erase the match.
            if (cueX != null && cueY != null) {
                val bx = cueX!!.toInt()
                val by = cueY!!.toInt()
                for (cy in (by - 12)..(by + 12) step 2)
                    for (cx in (bx - 12)..(bx + 12) step 2) {
                        if (cx !in minX..maxX || cy !in minY..maxY) continue
                        for (radius in radii) {
                            val score = ballScore(cx, cy, radius)
                            if (score > bestBallScore) {
                                bestBallScore = score
                                cueX = cx.toFloat()
                                cueY = cy.toFloat()
                            }
                        }
                    }
            }
        }
        // A weak hypothesis is less useful than no crosshair at all.
        if (bestBallScore < 0.78) { cueX = null; cueY = null }
        val summary = "Размер:${w}x${h} Шаг:$step Белые:$white " +
            "Отрезки:${segments.size} Макс:${longestLength.roundToInt()}px " +
            "Длинная:${if (chosen != null) "кандидат" else "нет"} " +
            "Биток:${if (cueX != null) "кий+линия" else "не найден"} " +
            "Оценка:${(bestBallScore * 100).roundToInt()} " +
            "Круги:$candidateCount Разные:$distinctCount " +
            "Кий:$alignedCount Сила:${(maxAlignment * 100).roundToInt()}"
        return Result(summary, chosen?.let { listOf(it) } ?: emptyList(), w, h, cueX, cueY)
    }
}
