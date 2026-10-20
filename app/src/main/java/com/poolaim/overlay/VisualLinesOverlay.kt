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
        private val expire = Runnable { clear() }

        fun update(value: FrameGuideResult, capturedAtMs: Long) {
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
            } else if (value.guide != null && age in 0 until MAX_AGE_MS &&
                capturedAtMs >= capturedAt) {
                result = value
                capturedAt = capturedAtMs
            } else if (result != null && now - capturedAt >= MAX_AGE_MS) {
                result = null
            }
            if (result?.guide != null) {
                val remaining = MAX_AGE_MS - (SystemClock.elapsedRealtime() - capturedAt)
                if (remaining > 0) postDelayed(expire, remaining)
            }
            invalidate()
        }

        fun clear() {
            removeCallbacks(expire)
            result = null
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
            if (r.width <= 0 || r.height <= 0 || guide.score < .90f) {
                drawStatus = "неверный кадр или оценка"; return
            }
            if (SystemClock.elapsedRealtime() - capturedAt !in 0 until MAX_AGE_MS) {
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
            val uncertain = r.reason.startsWith("Не уверен")
            paint.color = if (uncertain) Color.rgb(255, 174, 48) else Color.CYAN
            paint.pathEffect = if (uncertain) android.graphics.DashPathEffect(
                floatArrayOf(14f * resources.displayMetrics.density, 9f * resources.displayMetrics.density), 0f
            ) else null
            val angle = Math.toDegrees(kotlin.math.atan2(
                (guide.tip.y - guide.origin.y).toDouble(),
                (guide.tip.x - guide.origin.x).toDouble()
            )).toInt()
            drawStatus = if (points.size >= 2)
                "${if (uncertain) "НЕ УВЕРЕН" else "уверен"} · ${points.size - 1} отрезка · старт ${guide.tip.x.toInt()},${guide.tip.y.toInt()} · угол ${angle}°"
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
            } finally {
                canvas.restoreToCount(saved)
            }
            // Also re-check bounds/edit mode without waiting for another capture callback.
            postInvalidateDelayed(50L)
        }

        companion object { private const val MAX_AGE_MS = 1400L }
    }
}
