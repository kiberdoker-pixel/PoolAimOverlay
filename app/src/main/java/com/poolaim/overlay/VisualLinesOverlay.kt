package com.poolaim.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.poolaim.overlay.recognition.FrameGuideResult

/** Transparent, touch-through output. Saturated cyan is excluded by the white-line mask. */
internal class VisualLinesOverlay(private val context: Context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = LinesView(context)
    private var showing = false

    fun show() {
        if (showing) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ blocks touches through untrusted windows above the
            // obscuring-opacity limit, even when the Canvas is transparent.
            alpha = .8f
        }
        manager.addView(view, params)
        showing = true
    }

    fun drawStatus(): String = if (showing) view.drawStatus else "окно скрыто"

    /**
     * Accept an independently detected post-impact cue-ball ray.
     * The ray is deliberately absent until its own recognition exists.
     * Coordinates use the captured frame, not screen pixels.
     */
    fun updateCueTrajectory(
        origin: TargetBallTrajectory.Point?,
        direction: TargetBallTrajectory.Point?,
        frame: FrameGuideResult
    ) {
        if (showing) view.updateCueTrajectory(origin, direction, frame)
    }

    fun update(result: FrameGuideResult, capturedAtMs: Long) {
        if (showing) view.update(result, capturedAtMs)
    }

    fun hide() {
        if (!showing) return
        showing = false
        view.clear()
        manager.removeView(view)
    }

    private class LinesView(context: Context) : View(context) {
        init {
            // Transparent pixels must never cover the game with opaque black.
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Independent cue-ball trajectory input. Do not infer it from the object
        // ball's guide: two differently coloured copies would be misleading.
        private var cueTrajectory: List<TargetBallTrajectory.Point> = emptyList()
        private var cueStatus = "нет отдельного направления битка"

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            alpha = 255
            strokeWidth = 2f * resources.displayMetrics.density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        var drawStatus: String = "ожидание линии"
            private set
        private var result: FrameGuideResult? = null
        private var capturedAt = 0L
        private var pending: FrameGuideResult? = null
        private var pendingCount = 0
        private var smoothed = 0
        private var jumps = 0
        private var held = 0
        private var switches = 0
        private var lastDecision = "ожидание"
        private var lastSeenAt = 0L
        private var rejectedReversals = 0
        private val expire = Runnable { clear() }

        fun update(value: FrameGuideResult, capturedAtMs: Long) {
            // Cue predictions must never survive a new frame without their own
            // fresh observation, even when an object-ball guide is still valid.
            cueTrajectory = emptyList()
            cueStatus = "ожидание нового кадра"
            removeCallbacks(expire)
            val now = SystemClock.elapsedRealtime()
            val age = now - capturedAtMs
            // Clear only for a changed coordinate system or explicit table editing.
            // A missed recognition frame must not erase a confirmed trajectory.
            val previous = result
            if (TableBoundsOverlay.editing ||
                (previous != null && (previous.width != value.width ||
                    previous.height != value.height || previous.bounds != value.bounds))) {
                result = null
                pending = null
                pendingCount = 0
                lastDecision = "смена рамки"
                lastSeenAt = 0L
            } else if (value.guide == null) {
                // Accuracy-first: never keep a path after the detector loses its target.
                result = null
                pending = null
                pendingCount = 0
                lastSeenAt = 0L
                lastDecision = "цель не подтверждена — линия скрыта"
            } else if (value.guide != null && age in 0 until MAX_AGE_MS &&
                capturedAtMs >= capturedAt) {
                val old = result?.guide
                val fresh = value.guide
                lastSeenAt = now
                if (old == null) {
                    // GuideStabilityGate has already confirmed three consistent
                    // frames. Do not demand three more frames in the renderer:
                    // the extra delay can exceed the target-loss timeout.
                    result = value
                    capturedAt = capturedAtMs
                    pending = null
                    pendingCount = 0
                    lastDecision = "цель подтверждена распознавателем"
                } else {
                    val shift = kotlin.math.hypot((fresh.origin.x - old.origin.x).toDouble(),
                        (fresh.origin.y - old.origin.y).toDouble())
                    val a = kotlin.math.atan2((old.tip.y-old.origin.y).toDouble(),
                        (old.tip.x-old.origin.x).toDouble())
                    val b = kotlin.math.atan2((fresh.tip.y-fresh.origin.y).toDouble(),
                        (fresh.tip.x-fresh.origin.x).toDouble())
                    val angle = kotlin.math.abs(kotlin.math.atan2(kotlin.math.sin(b-a),kotlin.math.cos(b-a)))
                    // Opposite directions on an unchanged target are a CV ambiguity,
                    // not an instantaneous 180-degree aim rotation.
                    if (shift < fresh.ballRadius*.65 && angle > Math.toRadians(125.0)) {
                        result = null
                        capturedAt = 0L
                        rejectedReversals++
                        held++
                        pending = null
                        pendingCount = 0
                        lastDecision = "отклонён разворот ${Math.toDegrees(angle).toInt()}°"
                    } else if (shift < fresh.ballRadius*.35 && angle < Math.toRadians(5.0)) {
                        fun mix(a: Float,b: Float) = a*.7f+b*.3f
                        result = value.copy(guide = fresh.copy(
                            origin = fresh.origin.copy(x=mix(old.origin.x,fresh.origin.x),
                                y=mix(old.origin.y,fresh.origin.y)),
                            tip = fresh.tip.copy(x=mix(old.tip.x,fresh.tip.x),
                                y=mix(old.tip.y,fresh.tip.y))))
                        capturedAt = capturedAtMs
                        pending = null
                        pendingCount = 0
                        smoothed++
                        lastDecision = "цель удержана"
                    } else {
                        val candidate = pending?.guide
                        val match = candidate != null &&
                            kotlin.math.hypot((candidate.origin.x-fresh.origin.x).toDouble(),
                                (candidate.origin.y-fresh.origin.y).toDouble()) < fresh.ballRadius*.35 &&
                            kotlin.math.hypot((candidate.tip.x-fresh.tip.x).toDouble(),
                                (candidate.tip.y-fresh.tip.y).toDouble()) < fresh.ballRadius*.65
                        pendingCount = if (match) pendingCount+1 else 1
                        pending = value
                        // Switching to another ball requires sustained evidence; an aim rotation
                        // around the same ball can be accepted more quickly.
                        val targetSwitch = shift >= fresh.ballRadius*.65
                        val required = if (targetSwitch) 5 else 3
                        if (pendingCount >= required) {
                            result = value
                            capturedAt = capturedAtMs
                            pending = null
                            pendingCount = 0
                            switches++
                            lastDecision = if (targetSwitch) "новая цель подтверждена" else "поворот подтверждён"
                        } else {
                            // Do not display the previous aim while the new direction is pending.
                            result = null
                            jumps++
                            held++
                            lastDecision = "ожидание: $pendingCount/$required · сдвиг ${shift.toInt()}px · угол ${Math.toDegrees(angle).toInt()}°"
                        }
                    }
                }
            } else if (result != null && (now - capturedAt >= MAX_AGE_MS ||
                    (lastSeenAt > 0 && now - lastSeenAt > LOST_TARGET_MS))) {
                result = null
                pending = null
                pendingCount = 0
                lastDecision = "цель потеряна / истёк срок"
            }
            if (result?.guide != null) {
                val remaining = minOf(MAX_AGE_MS - (SystemClock.elapsedRealtime() - capturedAt),
                    if (lastSeenAt > 0) LOST_TARGET_MS - (SystemClock.elapsedRealtime() - lastSeenAt) else MAX_AGE_MS)
                if (remaining > 0) postDelayed(expire, remaining)
            }
            invalidate()
        }

        fun updateCueTrajectory(
            origin: TargetBallTrajectory.Point?,
            direction: TargetBallTrajectory.Point?,
            frame: FrameGuideResult
        ) {
            val b = frame.bounds
            // The CV ray is from the current captured frame. The yellow path
            // may have been smoothed or held from an older frame; match both
            // direction and position before showing a paired prediction.
            cueTrajectory = if (origin != null && direction != null &&
                !TableBoundsOverlay.editing && b.isValid() &&
                frame.width > 0 && frame.height > 0 &&
                origin.x.isFinite() && origin.y.isFinite() &&
                direction.x.isFinite() && direction.y.isFinite() &&
                kotlin.math.hypot(direction.x.toDouble(), direction.y.toDouble()) > 0.001 &&
                origin.x in (b.left * frame.width)..(b.right * frame.width) &&
                origin.y in (b.top * frame.height)..(b.bottom * frame.height) &&
                result?.width == frame.width && result?.height == frame.height &&
                result?.bounds == frame.bounds && result?.guide != null &&
                // A cyan ray belongs to the incoming frame, not to an older
                // smoothed/held yellow path. Never mix two different shots.
                result?.guide?.let { displayed ->
                    frame.guide?.let { observed ->
                        val distance = kotlin.math.hypot(
                            (displayed.origin.x - observed.origin.x).toDouble(),
                            (displayed.origin.y - observed.origin.y).toDouble())
                        val dx = displayed.tip.x - displayed.origin.x
                        val dy = displayed.tip.y - displayed.origin.y
                        val ox = observed.tip.x - observed.origin.x
                        val oy = observed.tip.y - observed.origin.y
                        val lengths = kotlin.math.hypot(dx.toDouble(), dy.toDouble()) *
                            kotlin.math.hypot(ox.toDouble(), oy.toDouble())
                        val originMatches = distance <= observed.ballRadius * .35f
                        val angleMatches = lengths > 0.001 &&
                            (dx.toDouble() * ox + dy.toDouble() * oy) / lengths >=
                            kotlin.math.cos(Math.toRadians(5.0))
                        // A shifted cue impact can indicate a different incoming
                        // segment, even if the outgoing yellow guide is similar.
                        // Keep the cyan start close to the same collision region.
                        val cueDistance = kotlin.math.hypot(
                            (origin.x - observed.origin.x).toDouble(),
                            (origin.y - observed.origin.y).toDouble())
                        originMatches && angleMatches &&
                            cueDistance <= observed.ballRadius * 5.0f
                    } ?: false
                } == true &&
                SystemClock.elapsedRealtime() - capturedAt in 0 until MAX_AGE_MS &&
                (lastSeenAt == 0L ||
                    SystemClock.elapsedRealtime() - lastSeenAt < LOST_TARGET_MS)) {
                TargetBallTrajectory.calculate(
                    start = origin,
                    direction = direction,
                    left = b.left * frame.width,
                    top = b.top * frame.height,
                    right = b.right * frame.width,
                    bottom = b.bottom * frame.height,
                    maxBounces = 3
                )
            } else emptyList()
            cueStatus = if (cueTrajectory.size >= 2)
                "голубая: ${cueTrajectory.size - 1} отрезка"
            else if (origin == null || direction == null)
                "голубая: распознаватель не нашёл направление"
            else "голубая: кадр или геометрия не подтверждены"
            invalidate()
        }

        fun clear() {
            removeCallbacks(expire)
            result = null
            cueTrajectory = emptyList()
            cueStatus = "очищена"
            pending = null
            pendingCount = 0
            lastSeenAt = 0L
            drawStatus = "линия очищена"
            invalidate()
        }

        override fun onDetachedFromWindow() {
            clear()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = result ?: run { drawStatus = "нет подтверждённой линии"; return }
            val guide = r.guide ?: run { drawStatus = "нет координат линии"; return }
            if (r.width <= 0 || r.height <= 0 || !guide.score.isFinite()) {
                drawStatus = "неверный кадр или оценка"; return
            }
            if (SystemClock.elapsedRealtime() - capturedAt !in 0 until MAX_AGE_MS ||
                (lastSeenAt > 0 && SystemClock.elapsedRealtime() - lastSeenAt >= LOST_TARGET_MS)) {
                drawStatus = "линия устарела"; return
            }
            if (TableBoundsOverlay.editing) {
                drawStatus = "редактирование рамки"; return
            }
            // Use the actual view dimensions for independent X/Y mapping; a system inset
            // must not suppress an otherwise valid trajectory.
            if (width <= 0 || height <= 0) {
                drawStatus = "размер захвата ${r.width}x${r.height}, слой ${width}x${height}"
                return
            }

            val b = r.bounds
            // Three bounces, line-only: use the calibrated rail boundaries directly.
            // Never inset them by a detected or assumed ball radius.
            val anchorBased = true
            val startX = (if (anchorBased) guide.origin.x else guide.tip.x).coerceIn(b.left * r.width, b.right * r.width)
            val startY = (if (anchorBased) guide.origin.y else guide.tip.y).coerceIn(b.top * r.height, b.bottom * r.height)
            val points = TargetBallTrajectory.calculate(
                start = TargetBallTrajectory.Point(startX, startY),
                direction = TargetBallTrajectory.Point(guide.tip.x - guide.origin.x, guide.tip.y - guide.origin.y),
                left = b.left * r.width,
                top = b.top * r.height,
                right = b.right * r.width,
                bottom = b.bottom * r.height,
                maxBounces = 3
            )
            val uncertain = true
            paint.color = Color.YELLOW
            paint.pathEffect = if (uncertain) android.graphics.DashPathEffect(
                floatArrayOf(14f * resources.displayMetrics.density, 9f * resources.displayMetrics.density), 0f
            ) else null
            val angle = Math.toDegrees(kotlin.math.atan2(
                (guide.tip.y - guide.origin.y).toDouble(),
                (guide.tip.x - guide.origin.x).toDouble()
            )).toInt()
            drawStatus = if (points.size >= 2)
                "${if (uncertain) "НЕ УВЕРЕН" else "уверен"} · ${points.size - 1} отрезка · старт ${startX.toInt()},${startY.toInt()} · угол ${angle}° · сглажено $smoothed · скачков $jumps · удержано $held · смен $switches · разворотов $rejectedReversals · возраст ${SystemClock.elapsedRealtime()-capturedAt}мс · $lastDecision · $cueStatus"
            else "математика: нет отрезков"
            val sx = width.toFloat() / r.width
            val sy = height.toFloat() / r.height
            val left = b.left * r.width * sx
            val top = b.top * r.height * sy
            val right = b.right * r.width * sx
            val bottom = b.bottom * r.height * sy
            // Strictly clip rendered pixels to the calibrated table ROI.
            // Also clamp every endpoint against floating-point overshoot.
            val saved = canvas.save()
            try {
                canvas.clipRect(left, top, right, bottom)
                for (i in 0 until points.size - 1) {
                    val from = points[i]
                    val to = points[i + 1]
                    canvas.drawLine(
                        (from.x * sx).coerceIn(left, right),
                        (from.y * sy).coerceIn(top, bottom),
                        (to.x * sx).coerceIn(left, right),
                        (to.y * sy).coerceIn(top, bottom),
                        paint
                    )
                }
                // Cue-ball path is drawn only when a separate cue hypothesis
                // has been supplied; never fabricate it from the target path.
                if (cueTrajectory.size >= 2) {
                    paint.color = Color.CYAN
                    paint.pathEffect = android.graphics.DashPathEffect(
                        floatArrayOf(10f * resources.displayMetrics.density,
                            8f * resources.displayMetrics.density), 0f)
                    for (i in 0 until cueTrajectory.size - 1) {
                        val from = cueTrajectory[i]
                        val to = cueTrajectory[i + 1]
                        canvas.drawLine(
                            (from.x * sx).coerceIn(left, right),
                            (from.y * sy).coerceIn(top, bottom),
                            (to.x * sx).coerceIn(left, right),
                            (to.y * sy).coerceIn(top, bottom),
                            paint
                        )
                    }
                }
            } finally {
                canvas.restoreToCount(saved)
            }
            // Also re-check bounds/edit mode without waiting for another capture callback.
            postInvalidateDelayed(50L)
        }

        companion object {
            private const val MAX_AGE_MS = 900L
            private const val LOST_TARGET_MS = 420L
        }
    }
}
