package com.poolaim.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

/** Small movable diagnostic HUD. Visible only while screen capture runs. */
class DiagnosticOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + 0.5f).toInt()
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.argb(220, 8, 17, 28))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.rgb(26, 211, 222))
        }
    }
    private val title = TextView(context).apply {
        text = "AIMX · ДИАГНОСТИКА"
        setTextColor(Color.rgb(35, 229, 233))
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val status = TextView(context).apply {
        text = "Ожидание кадров…"
        setTextColor(Color.WHITE)
        textSize = 12f
        maxLines = 3
    }
    private val params = WindowManager.LayoutParams(
        dp(310), WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(75)
    }
    private var shown = false

    init {
        root.addView(title)
        root.addView(status)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        root.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - downX).toInt().coerceAtLeast(0)
                    params.y = (startY + event.rawY - downY).toInt().coerceAtLeast(0)
                    if (shown) wm.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }
    fun show() {
        if (!shown) { wm.addView(root, params); shown = true }
    }
    fun update(message: String) { status.text = message }
    fun hide() {
        if (shown) { wm.removeView(root); shown = false }
    }
}
