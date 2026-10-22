package com.poolaim.overlay.recognition

import android.graphics.Bitmap
import android.media.Image
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A separate classical-CV experiment, independent of the legacy detector and classifier.
 *
 * OpenCV supplies Hough segment/circle extraction. The application-specific evidence tests
 * below deliberately reject uncertain scenes. Their score is not a model probability, and
 * synthetic tests do not establish accuracy on actual game captures.
 *
 * The recognizer reads only the captured pixels. Circles serve exclusively as local anchors
 * for a visible guide: they are never used to simulate collisions with other balls.
 */
class OpenCvGuideRecognizer {
    private val nativeReady: Boolean by lazy {
        try {
            OpenCVLoader.initLocal()
        } catch (_: LinkageError) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Synchronous: neither the Image nor its plane buffers are retained. */
    fun inspect(image: Image, bounds: TableBounds): FrameGuideResult {
        val width = image.width
        val height = image.height
        if (!bounds.isValid()) return rejected(width, height, bounds, "Неверные границы стола")
        return try {
            val plane = image.planes.firstOrNull()
                ?: return rejected(width, height, bounds, "Нет RGBA-кадра")
            if (plane.pixelStride != 4) return rejected(width, height, bounds, "Нужен RGBA-кадр")
            val buffer = plane.buffer.duplicate()
            val baseOffset = buffer.position()
            val raster = raster(width, height, bounds) { x, y ->
                val index = baseOffset + y * plane.rowStride + x * plane.pixelStride
                require(index >= 0 && index.toLong() + 3 < buffer.limit())
                val r = buffer.get(index).toInt() and 255
                val g = buffer.get(index + 1).toInt() and 255
                val b = buffer.get(index + 2).toInt() and 255
                (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
            analyze(raster, width, height, bounds)
        } catch (_: RuntimeException) {
            rejected(width, height, bounds, "Ошибка чтения или анализа кадра")
        } catch (_: LinkageError) {
            rejected(width, height, bounds, "OpenCV недоступна")
        }
    }

    /** Same native pipeline, available for offline capture and instrumented validation. */
    fun inspectBitmap(bitmap: Bitmap, bounds: TableBounds): FrameGuideResult {
        val width = bitmap.width
        val height = bitmap.height
        if (!bounds.isValid()) return rejected(width, height, bounds, "Неверные границы стола")
        return try {
            require(!bitmap.isRecycled)
            val raster = raster(width, height, bounds) { x, y -> bitmap.getPixel(x, y) }
            analyze(raster, width, height, bounds)
        } catch (_: RuntimeException) {
            rejected(width, height, bounds, "Ошибка чтения или анализа изображения")
        } catch (_: LinkageError) {
            rejected(width, height, bounds, "OpenCV недоступна")
        }
    }

    private data class Raster(
        val width: Int,
        val height: Int,
        val rgba: ByteArray,
        val offsetX: Int,
        val offsetY: Int,
        val scaleX: Double,
        val scaleY: Double
    ) {
        fun capturePoint(point: PixelPoint) = GuidePoint(
            (offsetX + point.x * scaleX).toFloat(),
            (offsetY + point.y * scaleY).toFloat()
        )
    }

    private fun raster(
        width: Int,
        height: Int,
        bounds: TableBounds,
        pixel: (Int, Int) -> Int
    ): Raster {
        require(width > 0 && height > 0)
        val left = (bounds.left * width).toInt().coerceIn(0, width - 1)
        val top = (bounds.top * height).toInt().coerceIn(0, height - 1)
        val right = ceil(bounds.right * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil(bounds.bottom * height).toInt().coerceIn(top + 1, height)
        val roiWidth = right - left
        val roiHeight = bottom - top
        val scale = min(1.0, 720.0 / max(roiWidth, roiHeight))
        val sampleWidth = max(1, (roiWidth * scale).roundToInt())
        val sampleHeight = max(1, (roiHeight * scale).roundToInt())
        val scaleX = roiWidth.toDouble() / sampleWidth
        val scaleY = roiHeight.toDouble() / sampleHeight
        val rgba = ByteArray(sampleWidth * sampleHeight * 4)
        var index = 0
        for (y in 0 until sampleHeight) {
            val sourceY = top + (y * scaleY).toInt().coerceAtMost(roiHeight - 1)
            for (x in 0 until sampleWidth) {
                val argb = pixel(left + (x * scaleX).toInt().coerceAtMost(roiWidth - 1), sourceY)
                rgba[index++] = (argb shr 16).toByte()
                rgba[index++] = (argb shr 8).toByte()
                rgba[index++] = argb.toByte()
                rgba[index++] = 255.toByte()
            }
        }
        return Raster(sampleWidth, sampleHeight, rgba, left, top, scaleX, scaleY)
    }

    private data class PixelPoint(val x: Double, val y: Double)
    private data class Anchor(val center: PixelPoint, val radius: Double, val roundSupport: Double)
    private data class Hypothesis(val anchor: Anchor, val tip: PixelPoint, val score: Double)

    private fun analyze(
        raster: Raster,
        captureWidth: Int,
        captureHeight: Int,
        bounds: TableBounds
    ): FrameGuideResult {
        fun none(reason: String) = rejected(captureWidth, captureHeight, bounds, reason)
        if (!nativeReady) return none("OpenCV недоступна")
        if (min(raster.width, raster.height) < 100) return none("Область стола слишком мала")

        val rgba = Mat(raster.height, raster.width, CvType.CV_8UC4)
        val rgb = Mat()
        val hsv = Mat()
        val mask = Mat()
        val lines = Mat()
        val gray = Mat()
        val circles = Mat()
        try {
            rgba.put(0, 0, raster.rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            // Real 8 Ball Pool guides can be neutral gray rather than bright white.
            // Preserve the low-saturation gate so cyan overlay and colored cloth stay out.
            Core.inRange(hsv, Scalar(0.0, 0.0, 85.0), Scalar(180.0, 55.0, 255.0), mask)
            val shortSide = min(raster.width, raster.height).toDouble()
            // The game renders dotted aim guides. A 2px Hough gap split the incoming\n            // guide into dozens of short pieces (observed in gameplay recording).\n            Imgproc.HoughLinesP(mask, lines, 1.0, PI / 180.0,
                max(10, (shortSide * .035).roundToInt()), shortSide * .04, max(3.0, shortSide * .025))
            if (lines.empty()) return none("Линия не найдена")

            // Build 98: a guide is valid only when attached to the game's hollow
            // contact marker. A long cue line alone must never produce a prediction.
            val segments = (0 until lines.rows()).mapNotNull { index ->
                val v = lines.get(index, 0)
                if (v == null || v.size < 4) null else {
                    val a = PixelPoint(v[0], v[1])
                    val b = PixelPoint(v[2], v[3])
                    Triple(a, b, hypot(b.x - a.x, b.y - a.y))
                }
            }.filter { it.third >= shortSide * .025 }
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 1.4)
            val radius = shortSide * .030
            Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT, 1.0,
                radius * 3.0, 110.0, 24.0,
                max(4, (radius * .60).roundToInt()),
                max(6, (radius * 1.45).roundToInt()))

            // Branch-first: select the short outgoing guide, not a detected ball.
            data class Branch(val origin: PixelPoint, val direction: PixelPoint,
                val score: Double)
            val radiusPx = shortSide * .030
            val longSegments = segments.filter { it.third >= shortSide * .12 }
            val shortSegments = segments.filter {
                it.third >= radiusPx * 1.6 && it.third <= shortSide * .18
            }
            val candidates = mutableListOf<Branch>()
            // Track how many pairs survive each filter in actual gameplay.
            var alignedPairs = 0
            var positionedPairs = 0
            var contrastPairs = 0
            var coloredPairs = 0
            var targetPairs = 0
            var crossPairs = 0
            for (incoming in longSegments) {
                for ((impact, cue) in listOf(incoming.first to incoming.second,
                        incoming.second to incoming.first)) {
                    val ix = (impact.x - cue.x) / incoming.third
                    val iy = (impact.y - cue.y) / incoming.third
                    for (outgoing in shortSegments) {
                        for ((near, far) in listOf(outgoing.first to outgoing.second,
                                outgoing.second to outgoing.first)) {
                            val ux = (far.x - near.x) / outgoing.third
                            val uy = (far.y - near.y) / outgoing.third
                            val alignment = ix * ux + iy * uy
                            if (alignment < .65) continue
                            alignedPairs++
                            val dx = near.x - impact.x
                            val dy = near.y - impact.y
                            val forward = dx * ix + dy * iy
                            val lateral = abs(dx * iy - dy * ix)
                            if (forward !in radiusPx * 1.0..radiusPx * 5.0 ||
                                lateral > radiusPx * 2.4) continue
                            positionedPairs++
                            // Local contrast validates the collision neighbourhood
                            // without recognizing individual balls.
                            val mid = PixelPoint((impact.x + near.x) / 2,
                                (impact.y + near.y) / 2)
                            val sample = rgb(raster, mid.x, mid.y) ?: continue
                            val felt = rgb(raster, mid.x, mid.y + radiusPx * 3)
                                ?: continue
                            val contrast = sample.indices.sumOf { abs(sample[it] - felt[it]) }
                            if (contrast < 100) continue
                            contrastPairs++
                            // A valid branch begins after a visible contact zone:
                            // verify colour evidence at several points, not just
                            // one potentially white pixel on the cue guide.
                            var colored = 0
                            for (step in 1..7) {
                                val t = step / 8.0
                                val probe = rgb(raster, impact.x + dx * t,
                                    impact.y + dy * t) ?: continue
                                val spread = (probe.maxOrNull() ?: 0) -
                                    (probe.minOrNull() ?: 0)
                                val bright = (probe.maxOrNull() ?: 0) >= 95
                                if (spread >= 65 && bright) colored++
                            }
                            if (colored < 3) continue
                            coloredPairs++
                            // A hollow white impact marker can provide a few
                            // coloured anti-aliased edge samples. Require a
                            // sustained coloured region near the outgoing
                            // branch, where the object ball should be.
                            var targetSupport = 0
                            for (step in 4..7) {
                                val t = step / 8.0
                                val probe = rgb(raster, impact.x + dx * t,
                                    impact.y + dy * t) ?: continue
                                val spread = (probe.maxOrNull() ?: 0) -
                                    (probe.minOrNull() ?: 0)
                                if (spread >= 65 && (probe.maxOrNull() ?: 0) >= 95)
                                    targetSupport++
                            }
                            if (targetSupport < 2) continue
                            targetPairs++
                            // Look BEHIND the start of the outgoing guide:
                            // its far endpoint lies at the edge of the target
                            // ball, so sampling directly at that endpoint
                            // misses the ball and rejects valid shots.
                            var crossSupport = 0
                            for (side in listOf(-1.0, 1.0)) {
                                val px = near.x - ux * radiusPx * .95 -
                                    uy * radiusPx * .35 * side
                                val py = near.y - uy * radiusPx * .95 +
                                    ux * radiusPx * .35 * side
                                val probe = rgb(raster, px, py) ?: continue
                                val background = rgb(raster,
                                    px - uy * radiusPx * 3.0 * side,
                                    py + ux * radiusPx * 3.0 * side) ?: continue
                                val difference = probe.indices.sumOf {
                                    abs(probe[it] - background[it])
                                }
                                if (difference >= 115) crossSupport++
                            }
                            if (crossSupport < 2) continue
                            crossPairs++
                            val score = alignment + min(contrast, 350) / 1000.0 -
                                lateral / (radiusPx * 10) + colored * .015
                            candidates.add(Branch(near, PixelPoint(ux, uy), score))
                        }
                    }
                }
            }
            val best = candidates.maxByOrNull { it.score }
                ?: return none("Нет исходящей · Д${longSegments.size} К${shortSegments.size} · А${alignedPairs} П${positionedPairs} К${contrastPairs} Ц${coloredPairs} Ш${targetPairs} Ф${crossPairs}")
            if (candidates.any {
                    it.score > best.score - .08 &&
                    hypot(it.origin.x - best.origin.x, it.origin.y - best.origin.y) > radiusPx * 3
                }) return none("Неоднозначная исходящая линия")
            val refined = best.origin
            val chosen = best.direction
            val centerScore = best.score.coerceIn(0.0, 1.0)
            val branchAngle = Math.toDegrees(kotlin.math.atan2(chosen.y, chosen.x)).roundToInt()
            val guide = RecognizedGuide(
                origin = raster.capturePoint(refined),
                tip = raster.capturePoint(PixelPoint(
                    refined.x + chosen.x * radiusPx * 5,
                    refined.y + chosen.y * radiusPx * 5)),
                ballRadius = (radiusPx * raster.scaleX).toFloat(),
                // Evidence score, not a calibrated probability. Avoid reporting a
                // fixed 95% for every accepted frame regardless of guide alignment.
                score = centerScore.toFloat()
            )
            return FrameGuideResult(captureWidth, captureHeight, bounds, guide,
                "Исходящая линия · угол=${branchAngle}° · 3 отражения")
        } finally {
            circles.release()
            gray.release()
            lines.release()
            mask.release()
            hsv.release()
            rgb.release()
            rgba.release()
        }
    }

    /** Reject cue-white and hollow ghost circles; require a filled, contrasted round patch. */
    private fun roundAnchorSupport(raster: Raster, center: PixelPoint, radius: Double): Double {
        var whiteInterior = 0
        var totalInterior = 0
        val step = max(1, (radius / 4).roundToInt())
        for (dy in -radius.toInt() / 2..radius.toInt() / 2 step step) {
            for (dx in -radius.toInt() / 2..radius.toInt() / 2 step step) {
                if (dx * dx + dy * dy > radius * radius * .25) continue
                val color = rgb(raster, center.x + dx, center.y + dy) ?: return 0.0
                totalInterior++
                val high = color.maxOrNull() ?: 0
                val low = color.minOrNull() ?: 0
                if (high >= 190 && high - low <= 55) whiteInterior++
            }
        }
        if (totalInterior == 0 || whiteInterior.toDouble() / totalInterior > .30) return 0.0
        var contrasted = 0
        for (index in 0 until 24) {
            val angle = index * 2.0 * PI / 24
            val inner = rgb(raster, center.x + cos(angle) * radius * .65,
                center.y + sin(angle) * radius * .65) ?: return 0.0
            val outer = rgb(raster, center.x + cos(angle) * radius * 1.4,
                center.y + sin(angle) * radius * 1.4) ?: return 0.0
            val contrast = inner.indices.sumOf { abs(inner[it] - outer[it]) }
            if (contrast >= 70) contrasted++
        }
        return contrasted / 24.0
    }

    private fun rgb(raster: Raster, x: Double, y: Double): IntArray? {
        val ix = x.roundToInt()
        val iy = y.roundToInt()
        if (ix !in 0 until raster.width || iy !in 0 until raster.height) return null
        val index = (iy * raster.width + ix) * 4
        return intArrayOf(raster.rgba[index].toInt() and 255,
            raster.rgba[index + 1].toInt() and 255, raster.rgba[index + 2].toInt() and 255)
    }

    private fun hypothesis(
        rawStart: PixelPoint,
        end: PixelPoint,
        anchor: Anchor,
        circles: List<Anchor>,
        white: ByteArray,
        width: Int,
        height: Int
    ): Hypothesis? {
        val length = hypot(end.x - rawStart.x, end.y - rawStart.y)
        if (length < 1.0) return null
        val ux = (end.x - rawStart.x) / length
        val uy = (end.y - rawStart.y) / length
        // Hough may partition intersecting stripes and return only a distal piece.
        // Recover its observed near end before testing attachment to a ball. Follow
        // only existing white support, with exactly the forward trace's tolerances.
        var backwardWhite = 0
        var backwardGap = 0
        for (distance in 0..max(width, height)) {
            val x = rawStart.x - distance * ux
            val y = rawStart.y - distance * uy
            if (x < 0 || y < 0 || x >= width || y >= height) break
            if (whiteNear(white, width, height, x, y, ux, uy)) {
                backwardWhite = distance
                backwardGap = 0
            } else if (++backwardGap > 2) break
        }
        val start = PixelPoint(rawStart.x - backwardWhite * ux,
            rawStart.y - backwardWhite * uy)
        val bx = anchor.center.x - start.x
        val by = anchor.center.y - start.y
        val along = bx * ux + by * uy
        val across = abs(bx * uy - by * ux)
        val radius = anchor.radius
        // A colored circular anchor must lie behind exactly this endpoint, along the line.
        if (along !in -1.9 * radius..-.45 * radius || across > radius * .28) return null
        if (hypot(bx, by) !in .6 * radius..1.95 * radius) return null

        // Extend through white support before testing length. A fragment of a long cue line
        // must not be accepted merely because Hough happened to split it into short pieces.
        var lastWhite = 0
        var gap = 0
        for (distance in 0..max(width, height)) {
            val x = start.x + distance * ux
            val y = start.y + distance * uy
            if (x < 0 || y < 0 || x >= width || y >= height) break
            if (whiteNear(white, width, height, x, y, ux, uy)) {
                lastWhite = distance
                gap = 0
            } else if (++gap > 2) break
        }
        if (lastWhite !in (radius * 1.8).toInt()..(radius * 14.0).toInt()) return null
        val tip = PixelPoint(start.x + lastWhite * ux, start.y + lastWhite * uy)
        // A slightly tilted Hough fragment can trace out of a wide stripe's side.
        // Reject only when the SAME local white component demonstrably continues
        // beyond this endpoint; do not relax candidate clustering or confidence.
        if (GuideEndpointSupport.continuesForward(white, width, height,
                tip.x, tip.y, ux, uy, radius)) return null
        // Hough endpoints have no semantic order. In particular, a cue-white ball or
        // ghost ring at the other end must not turn an incoming cue line into an
        // outgoing object-ball guide. Include circles swallowed by the white trace.
        if (circles.any { circle ->
                val sameAnchor = hypot(circle.center.x - anchor.center.x,
                    circle.center.y - anchor.center.y) <= min(circle.radius, radius) * .6
                val cx = circle.center.x - start.x
                val cy = circle.center.y - start.y
                val projected = cx * ux + cy * uy
                !sameAnchor && projected > radius &&
                    projected < lastWhite + circle.radius * 1.9 &&
                    abs(cx * uy - cy * ux) <= circle.radius * .6
            }) return null
        val margin = radius * 1.2
        if (tip.x < margin || tip.y < margin || tip.x > width - margin || tip.y > height - margin)
            return null

        var supported = 0
        var thin = 0
        val samples = max(12, min(100, lastWhite))
        for (index in 0 until samples) {
            val distance = lastWhite * (.1 + .8 * index / max(1, samples - 1))
            val x = start.x + distance * ux
            val y = start.y + distance * uy
            if (whiteNear(white, width, height, x, y, ux, uy)) supported++
            var thickness = 0
            val extent = ceil(radius * .8).toInt()
            for (offset in -extent..extent) {
                if (whiteAt(white, width, height, x - uy * offset, y + ux * offset)) thickness++
            }
            if (thickness <= max(3.0, radius * .45)) thin++
        }
        val score = minOf(supported.toDouble() / samples, thin.toDouble() / samples,
            anchor.roundSupport, 1.0 - across / (radius * 4))
        return if (score >= .80) Hypothesis(anchor, tip, score) else null
    }

    private fun whiteNear(
        mask: ByteArray, width: Int, height: Int, x: Double, y: Double, ux: Double, uy: Double
    ) = (-1..1).any { offset ->
        whiteAt(mask, width, height, x - uy * offset, y + ux * offset)
    }

    private fun whiteAt(mask: ByteArray, width: Int, height: Int, x: Double, y: Double): Boolean {
        val ix = x.roundToInt()
        val iy = y.roundToInt()
        return ix in 0 until width && iy in 0 until height && mask[iy * width + ix].toInt() != 0
    }

    private fun sameGuide(a: Hypothesis, b: Hypothesis): Boolean {
        val radius = min(a.anchor.radius, b.anchor.radius)
        if (hypot(a.anchor.center.x - b.anchor.center.x,
                a.anchor.center.y - b.anchor.center.y) > radius * .6) return false
        val ax = a.tip.x - a.anchor.center.x
        val ay = a.tip.y - a.anchor.center.y
        val bx = b.tip.x - b.anchor.center.x
        val by = b.tip.y - b.anchor.center.y
        val cosine = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))
        return cosine >= cos(5.0 * PI / 180.0) && hypot(a.tip.x - b.tip.x, a.tip.y - b.tip.y) < radius
    }

    private fun rejected(width: Int, height: Int, bounds: TableBounds, reason: String) =
        FrameGuideResult(width, height, bounds, reason = reason)
}
