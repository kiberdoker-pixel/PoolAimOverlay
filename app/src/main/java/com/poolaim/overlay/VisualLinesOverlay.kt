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
        private var result: LineDetector.Result? = null
        fun update(value: LineDetector.Result) {
            result = value
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = result ?: return
            if (r.width <= 0 || r.height <= 0) return
            val sx = width.toFloat() / r.width
            val sy = height.toFloat() / r.height
            for (s in r.segments) {
                canvas.drawLine(s.x1 * sx, s.y1 * sy, s.x2 * sx, s.y2 * sy, paint)
            }
        }
    }
}
