package com.poolaim.overlay

import android.media.Image
import kotlin.math.*

/** Experimental geometric selection. The chosen line is only a hypothesis, not verified ball identification. */
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
        // Experimental ball-anchored selection. Still a hypothesis, not a verified
        // object-ball detector. Prefer a short guide starting near a coloured ball.
        fun length(s: Segment): Double = hypot(
            (s.x2 - s.x1).toDouble(), (s.y2 - s.y1).toDouble())
        fun distance(ax: Float, ay: Float, bx: Float, by: Float): Double =
            hypot((ax - bx).toDouble(), (ay - by).toDouble())

        // Estimate whether the neighbourhood contains a ball instead of blue cloth.
        // Sampling around, not directly on, the bright guide avoids its white pixels.
        fun ballEvidence(cx: Float, cy: Float): Double {
            var coloured = 0
            var sampled = 0
            for (oy in -28..28 step 8) for (ox in -28..28 step 8) {
                val rr = ox * ox + oy * oy
                if (rr < 64 || rr > 784) continue
                val px = (cx + ox).toInt()
                val py = (cy + oy).toInt()
                if (px !in 0 until w || py !in 0 until h) continue
                val pos = py.toLong() * rowStride + px.toLong() * pixelStride
                if (pos + 2 >= buffer.limit()) continue
                val p = pos.toInt()
                val r = buffer.get(p).toInt() and 255
                val g = buffer.get(p + 1).toInt() and 255
                val b = buffer.get(p + 2).toInt() and 255
                val high = max(r, max(g, b))
                val low = min(r, min(g, b))
                val blueCloth = b > r * 1.18 && g > r * 1.12 && b > 90
                if (high > 65 && high - low > 48 && !blueCloth) coloured++
                sampled++
            }
            return if (sampled == 0) 0.0 else coloured.toDouble() / sampled
        }

        val longLines = segments.filter { length(it) >= 145.0 }
            .sortedByDescending { length(it) }.take(12)
        val shortLines = segments.filter { length(it) in 28.0..140.0 }
        var nearbyCount = 0
        var forwardCount = 0
        var aheadCount = 0
        var best: Segment? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (longLine in longLines) {
            val ll = length(longLine)
            val endpoints = arrayOf(
                floatArrayOf(longLine.x1, longLine.y1, longLine.x2, longLine.y2),
                floatArrayOf(longLine.x2, longLine.y2, longLine.x1, longLine.y1)
            )
            for (end in endpoints) {
                val incomingX = (end[0] - end[2]) / ll
                val incomingY = (end[1] - end[3]) / ll
                // Estimate the object-ball centre on the forward side of the impact.
                // Search a small region, not only the beginning of a guide segment.
                var ballX = Float.NaN
                var ballY = Float.NaN
                var ballStrength = 0.0
                val sideX = -incomingY
                val sideY = incomingX
                for (along in 24..80 step 8) for (side in -40..40 step 8) {
                    val cx = (end[0] + incomingX * along + sideX * side).toFloat()
                    val cy = (end[1] + incomingY * along + sideY * side).toFloat()
                    val evidence = ballEvidence(cx, cy)
                    // Bias towards plausible ball centres just ahead of impact.
                    val quality = evidence - abs(side) * 0.0012 - abs(along - 43) * 0.0006
                    if (quality > ballStrength && evidence >= 0.055) {
                        ballStrength = quality
                        ballX = cx
                        ballY = cy
                    }
                }
                for (shortLine in shortLines) {
                    val sl = length(shortLine)
                    val d1 = distance(end[0], end[1], shortLine.x1, shortLine.y1)
                    val d2 = distance(end[0], end[1], shortLine.x2, shortLine.y2)
                    val near = min(d1, d2)
                    if (near > 135.0) continue
                    nearbyCount++
                    val startX = if (d1 <= d2) shortLine.x1 else shortLine.x2
                    val startY = if (d1 <= d2) shortLine.y1 else shortLine.y2
                    val dx = if (d1 <= d2) shortLine.x2 - shortLine.x1
                             else shortLine.x1 - shortLine.x2
                    val dy = if (d1 <= d2) shortLine.y2 - shortLine.y1
                             else shortLine.y1 - shortLine.y2
                    val forward = (dx * incomingX + dy * incomingY) / sl
                    if (forward < 0.05) continue
                    forwardCount++
                    // The target ball lies ahead of the collision marker. Cue-ball
                    // deflection often starts right at that marker instead.
                    val ahead = ((startX - end[0]) * incomingX +
                        (startY - end[1]) * incomingY).toDouble()
                    if (ahead < -22.0) continue
                    aheadCount++
                    val evidence = ballEvidence(startX, startY)
                    // Colour evidence is a preference, not a hard rejection: striped
                    // and pale object balls may have very little saturated colour.
                    // A true object-ball guide should follow the radial direction
                    // from impact towards the object-ball centre. Penalise lateral
                    // offsets instead of rewarding only a forward-pointing segment.
                    var anchorBonus = 0.0
                    if (!ballX.isNaN() && !ballY.isNaN()) {
                        val vx = (ballX - end[0]).toDouble()
                        val vy = (ballY - end[1]).toDouble()
                        val vl = hypot(vx, vy)
                        if (vl > 8.0) {
                            val radial = (dx * vx + dy * vy) / (sl * vl)
                            val perpendicular = abs((ballX - startX) * dy -
                                (ballY - startY) * dx) / sl
                            val startToBall = distance(startX, startY, ballX, ballY)
                            anchorBonus = radial * 72.0 -
                                perpendicular.coerceAtMost(85.0) * 1.5 -
                                max(0.0, startToBall - 35.0) * 0.42
                        }
                    }
                    val score = evidence * 25.0 + forward * 22.0 +
                        ahead.coerceIn(-22.0, 90.0) * 0.18 -
                        abs(near - 40.0) * 0.30 + min(sl, 100.0) * 0.10 +
                        anchorBonus
                    if (score > bestScore) {
                        bestScore = score
                        best = shortLine
                    }
                }
            }
        }
        val selected = best?.let { listOf(it) } ?: emptyList()
        val summary = "Размер:${w}x${h} Шаг:$step Белые:$white " +
            "Длинные:${longLines.size} Короткие:${shortLines.size} " +
            "Рядом:$nearbyCount Вперёд:$forwardCount Перед:$aheadCount " +
            "Цель:${if (best == null) "не найдена" else "гипотеза-центр"}"
        return Result(summary, selected, w, h)
    }
}
