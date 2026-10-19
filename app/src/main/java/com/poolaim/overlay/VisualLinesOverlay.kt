package com.poolaim.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Touch-through, secure overlay for visual debugging. */
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        manager.addView(view, params)
        showing = true
    }

    fun update(result: LineDetector.Result) {
        if (showing) view.update(result)
    }

    fun hide() {
        if (!showing) return
        showing = false
        manager.removeView(view)
    }

    private class LinesView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            strokeWidth = 4f * resources.displayMetrics.density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            strokeWidth = 2.5f * resources.displayMetrics.density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var result: LineDetector.Result? = null
        fun update(value: LineDetector.Result) {
            result = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = result ?: return
            if (r.width <= 0 || r.height <= 0) return

            val guide = r.segments.firstOrNull() ?: return
            val bounds = TableBoundsOverlay.bounds

            val points = TargetBallTrajectory.calculate(
                start = TargetBallTrajectory.Point(guide.x2, guide.y2),
                direction = TargetBallTrajectory.Point(
                    guide.x2 - guide.x1,
                    guide.y2 - guide.y1
                ),
                left = bounds[0] * r.width,
                top = bounds[1] * r.height,
                right = bounds[2] * r.width,
                bottom = bounds[3] * r.height,
                maxBounces = 3
            )

            val sx = width.toFloat() / r.width
            val sy = height.toFloat() / r.height

            paint.color = Color.CYAN

            for (i in 0 until points.size - 1) {
                canvas.drawLine(
                    points[i].x * sx,
                    points[i].y * sy,
                    points[i + 1].x * sx,
                    points[i + 1].y * sy,
                    paint
                )
            }
        }

    }
}
