package com.poolaim.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

internal class TableBoundsOverlay(private val context: Context) {
    companion object {
        @Volatile var bounds = floatArrayOf(.19f, .21f, .81f, .92f)
            private set
        @Volatile var editing = false
            private set

        fun snapshot(): com.poolaim.overlay.recognition.TableBounds {
            val value = bounds
            return com.poolaim.overlay.recognition.TableBounds(value[0], value[1], value[2], value[3])
        }
    }
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("aimx_table_bounds", Context.MODE_PRIVATE)
    private var editor: BoundsView? = null
    private var button: TextView? = null

    init {
        bounds = floatArrayOf(
            prefs.getFloat("left", .19f), prefs.getFloat("top", .21f),
            prefs.getFloat("right", .81f), prefs.getFloat("bottom", .92f)
        )
    }

    private fun params(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    fun show() {
        if (button != null) return
        button = TextView(context).apply {
            text = "Стол"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC14552D.toInt())
            setPadding(18, 12, 18, 12)
            setOnClickListener { if (editor == null) openEditor() else closeEditor(true) }
        }.also {
            wm.addView(it, params(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            ).apply { gravity = Gravity.TOP or Gravity.END; x = 12; y = 100 })
        }
    }

    private fun openEditor() {
        if (editor != null) return
        editing = true
        editor = BoundsView(context) { closeEditor(true) }.also {
            wm.addView(it, params(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            ))
        }
        button?.text = "Сохранить"
    }

    private fun closeEditor(save: Boolean) {
        editor?.let {
            if (save) {
                bounds = it.getBounds()
                prefs.edit()
                    .putFloat("left", bounds[0]).putFloat("top", bounds[1])
                    .putFloat("right", bounds[2]).putFloat("bottom", bounds[3])
                    .apply()
            }
            wm.removeView(it)
        }
        editor = null
        editing = false
        button?.text = "Стол"
    }

    fun hide() {
        closeEditor(false)
        button?.let { wm.removeView(it) }
        button = null
    }

    private class BoundsView(context: Context, private val onSave: () -> Unit) : View(context) {
        private val rectPaint = Paint(3).apply {
            color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private val pointPaint = Paint(3).apply { color = Color.GREEN }
        private val values = bounds.copyOf()
        private val saveRect = RectF()
        private val savePaint = Paint(3).apply { color = 0xEE176B35.toInt() }
        private val saveText = Paint(3).apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }
        private val touchRadius = 76f
        private var savePressed = false
        private var active = -1
        private val margin = 30f
        private val minGap = 80f

        fun getBounds() = values.copyOf()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val l = values[0] * width; val t = values[1] * height
            val r = values[2] * width; val b = values[3] * height
            canvas.drawRect(l, t, r, b, rectPaint)
            canvas.drawCircle(l, t, 22f, pointPaint)
            canvas.drawCircle(r, b, 22f, pointPaint)
            // The save control is part of the touchable editor itself, never behind it.
            val buttonW = min(300f, width * .28f)
            val buttonH = 84f
            saveRect.set(width - buttonW - 22f, 20f, width - 22f, 20f + buttonH)
            canvas.drawRoundRect(saveRect, 18f, 18f, savePaint)
            canvas.drawText("СОХРАНИТЬ", saveRect.centerX(), saveRect.centerY() + 12f, saveText)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (width <= 0 || height <= 0) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val d1 = (event.x - values[0] * width) * (event.x - values[0] * width) +
                        (event.y - values[1] * height) * (event.y - values[1] * height)
                    val d2 = (event.x - values[2] * width) * (event.x - values[2] * width) +
                        (event.y - values[3] * height) * (event.y - values[3] * height)
                    savePressed = saveRect.contains(event.x, event.y)
                    active = if (savePressed) -1 else when {
                        d1 <= touchRadius * touchRadius && d1 <= d2 -> 0
                        d2 <= touchRadius * touchRadius -> 2
                        else -> -1
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (active == -1) return true
                    val x = event.x.coerceIn(margin, max(margin, width - margin)) / width
                    val y = event.y.coerceIn(margin, max(margin, height - margin)) / height
                    if (active == 0) {
                        values[0] = min(x, values[2] - minGap / width)
                        values[1] = min(y, values[3] - minGap / height)
                    } else if (active == 2) {
                        values[2] = max(x, values[0] + minGap / width)
                        values[3] = max(y, values[1] + minGap / height)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val shouldSave = savePressed && saveRect.contains(event.x, event.y)
                    active = -1
                    savePressed = false
                    if (shouldSave) onSave()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    active = -1
                    savePressed = false
                    return true
                }
            }
            return true
        }
    }
}
