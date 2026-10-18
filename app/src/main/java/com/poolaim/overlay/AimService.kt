package com.poolaim.overlay

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.*

class AimService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var aim: AimView
    private lateinit var panel: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var aimParams: WindowManager.LayoutParams
    private val prefs by lazy { getSharedPreferences("aim_settings", MODE_PRIVATE) }
    private var editing = true
    private val channel = "pool_aim_channel"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(channel, "Прицел", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Pool Aim Overlay")
            .setContentText("Прицел работает поверх игры")
            .setContentIntent(pending).build()
        startForeground(1001, notification)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        aim = AimView(this, prefs)
        aimParams = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(aim, aimParams)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            setBackgroundColor(Color.argb(225, 25, 29, 38))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mode = smallButton("▶")
        val settings = smallButton("⚙")
        val close = smallButton("×")
        val move = TextView(this).apply {
            text = "⠿"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(move)
        header.addView(mode)
        header.addView(settings)
        header.addView(close)
        panel.addView(header)

        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val sensitivityLabel = textLabel("Чувствительность: ${prefs.getInt("sensitivity", 100)}%")
        val sensitivity = SeekBar(this).apply {
            max = 190
            progress = prefs.getInt("sensitivity", 100) - 10
            setOnSeekBarChangeListener(onSeek { value ->
                val amount = value + 10
                sensitivityLabel.text = "Чувствительность: $amount%"
                aim.sensitivity = amount / 100f
                prefs.edit().putInt("sensitivity", amount).apply()
            })
        }
        val sizeLabel = textLabel("Размер шаров: ${prefs.getInt("ball_size", 100)}%")
        val size = SeekBar(this).apply {
            max = 150
            progress = prefs.getInt("ball_size", 100) - 50
            setOnSeekBarChangeListener(onSeek { value ->
                val amount = value + 50
                sizeLabel.text = "Размер шаров: $amount%"
                aim.ballScale = amount / 100f
                prefs.edit().putInt("ball_size", amount).apply()
                aim.invalidate()
            })
        }
        options.addView(textLabel("Перетаскивай точки и углы рамки"))
        options.addView(sensitivityLabel)
        options.addView(sensitivity)
        options.addView(sizeLabel)
        options.addView(size)
        val reset = smallButton("Сбросить рамку")
        reset.setOnClickListener { aim.resetBounds() }
        options.addView(reset)
        panel.addView(options)

        mode.setOnClickListener {
            editing = !editing
            aim.editing = editing
            aimParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (editing) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            // Android 12+: sufficiently transparent non-touchable overlays permit touches to reach the game.
            aimParams.alpha = if (editing) 1f else 0.7f
            wm.updateViewLayout(aim, aimParams)
            mode.text = if (editing) "▶" else "✎"
            aim.invalidate()
        }
        settings.setOnClickListener {
            options.visibility = if (options.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        close.setOnClickListener { stopSelf() }

        panelParams = WindowManager.LayoutParams(
            -2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("panel_x", dp(10))
            y = prefs.getInt("panel_y", dp(65))
        }
        move.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var downX = 0f
            var downY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = panelParams.x; startY = panelParams.y
                        downX = event.rawX; downY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        panelParams.x = (startX + event.rawX - downX).roundToInt()
                        panelParams.y = (startY + event.rawY - downY).roundToInt()
                        wm.updateViewLayout(panel, panelParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.edit().putInt("panel_x", panelParams.x).putInt("panel_y", panelParams.y).apply()
                        return true
                    }
                }
                return true
            }
        })
        wm.addView(panel, panelParams)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun smallButton(label: String) = Button(this).apply {
        text = label
        textSize = 13f
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(5), 0, dp(5), 0)
        layoutParams = LinearLayout.LayoutParams(-2, dp(39))
    }
    private fun textLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(Color.WHITE)
        setPadding(dp(5), dp(4), dp(5), 0)
    }
    private fun onSeek(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) block(progress) }
        override fun onStartTrackingTouch(bar: SeekBar?) {}
        override fun onStopTrackingTouch(bar: SeekBar?) {}
    }
    override fun onDestroy() {
        if (::wm.isInitialized) {
            if (::aim.isInitialized) wm.removeView(aim)
            if (::panel.isInitialized) wm.removeView(panel)
        }
        super.onDestroy()
    }
}

private data class V(val x: Float, val y: Float) {
    operator fun plus(o: V) = V(x + o.x, y + o.y)
    operator fun minus(o: V) = V(x - o.x, y - o.y)
    operator fun times(s: Float) = V(x * s, y * s)
    fun dot(o: V) = x * o.x + y * o.y
    fun unit(): V { val length = hypot(x, y); return if (length < .001f) V(1f, 0f) else V(x / length, y / length) }
}

private class AimView(context: Context, private val prefs: android.content.SharedPreferences) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var initialized = false
    private var cue = V(0f, 0f)
    private var target = V(0f, 0f)
    private var direction = V(0f, 0f)
    private var topLeft = V(0f, 0f)
    private var bottomRight = V(0f, 0f)
    private var drag = -1
    private var previous = V(0f, 0f)
    var editing = true
    var sensitivity = prefs.getInt("sensitivity", 100) / 100f
    var ballScale = prefs.getInt("ball_size", 100) / 100f
    private val density get() = resources.displayMetrics.density
    private val r get() = 14f * density * ballScale
    private var canvasRef: Canvas? = null

    private fun restore() {
        if (width <= 0 || height <= 0) return
        fun point(name: String, defaultX: Float, defaultY: Float): V = V(
            prefs.getFloat("${name}_x", defaultX) * width,
            prefs.getFloat("${name}_y", defaultY) * height
        )
        cue = point("cue", .28f, .55f)
        target = point("target", .64f, .55f)
        direction = point("direction", .48f, .55f)
        topLeft = point("top_left", .075f, .20f)
        bottomRight = point("bottom_right", .925f, .80f)
        initialized = true
    }
    private fun save() {
        if (width == 0 || height == 0) return
        val editor = prefs.edit()
        fun put(name: String, v: V) {
            editor.putFloat("${name}_x", v.x / width)
            editor.putFloat("${name}_y", v.y / height)
        }
        put("cue", cue); put("target", target); put("direction", direction)
        put("top_left", topLeft); put("bottom_right", bottomRight)
        editor.apply()
    }
    fun resetBounds() {
        topLeft = V(width * .075f, height * .20f)
        bottomRight = V(width * .925f, height * .80f)
        save(); invalidate()
    }
    private fun line(a: V, b: V, color: Int, stroke: Float = 3f) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke * density
        canvasRef?.drawLine(a.x, a.y, b.x, b.y, paint)
    }
    private fun marker(p: V, color: Int, label: String, index: Int) {
        // Separate small grip below the marker: your finger doesn't hide the exact ball position.
        paint.style = Paint.Style.FILL; paint.color = color
        canvasRef?.drawCircle(p.x, p.y, r, paint)
        paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        canvasRef?.drawCircle(p.x, p.y, r, paint)
        val grip = gripPoint(p, index)
        line(p, grip, Color.WHITE, 1f)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(235, 35, 40, 50)
        canvasRef?.drawCircle(grip.x, grip.y, 11f * density, paint)
        paint.color = Color.WHITE; paint.textSize = 12f * density; paint.textAlign = Paint.Align.CENTER
        canvasRef?.drawText("↕", grip.x, grip.y + 4f * density, paint)
        paint.textAlign = Paint.Align.LEFT
        canvasRef?.drawText(label, p.x + r + 5f * density, p.y - r, paint)
    }
    private fun gripPoint(p: V, index: Int): V {
        val offset = 38f * density
        return when (index) {
            0 -> V(p.x - offset, p.y + offset)
            1 -> V(p.x + offset, p.y + offset)
            else -> V(p.x - offset, p.y - offset)
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!initialized) restore()
        canvasRef = canvas
        val left = min(topLeft.x, bottomRight.x)
        val right = max(topLeft.x, bottomRight.x)
        val top = min(topLeft.y, bottomRight.y)
        val bottom = max(topLeft.y, bottomRight.y)
        paint.style = Paint.Style.STROKE; paint.color = Color.argb(155, 0, 255, 180)
        paint.strokeWidth = 2f * density
        canvas.drawRect(left, top, right, bottom, paint)

        val d = (direction - cue).unit()
        val toTarget = target - cue
        val projection = toTarget.dot(d)
        val closest = cue + d * projection
        val distance = hypot(target.x - closest.x, target.y - closest.y)
        val hit = projection > 0f && distance <= 2f * r
        val travel = if (hit) {
            val offset = sqrt(max(0f, (2f * r) * (2f * r) - distance * distance))
            max(0f, projection - offset)
        } else Float.POSITIVE_INFINITY
        val hitPoint = if (hit) cue + d * travel else null
        if (hitPoint != null) {
            line(cue, hitPoint, Color.CYAN)
            val normal = (target - hitPoint).unit()
            val objectSpeed = d.dot(normal).coerceAtLeast(0f)
            val residual = d - normal * objectSpeed
            drawBounces(target, normal, left, top, right, bottom, Color.YELLOW, 3)
            if (hypot(residual.x, residual.y) > .08f)
                drawBounces(hitPoint, residual.unit(), left, top, right, bottom, Color.WHITE, 2)
        } else drawBounces(cue, d, left, top, right, bottom, Color.CYAN, 4)

        if (editing) {
            marker(cue, Color.WHITE, "Биток", 0)
            marker(target, Color.YELLOW, "Цель", 1)
            marker(direction, Color.CYAN, "Направление", 2)
            paint.color = Color.GREEN; paint.style = Paint.Style.FILL
            canvas.drawCircle(topLeft.x, topLeft.y, 12f * density, paint)
            canvas.drawCircle(bottomRight.x, bottomRight.y, 12f * density, paint)
        }
        canvasRef = null
    }
    private fun drawBounces(start: V, vector: V, l: Float, t: Float, right: Float, bottom: Float, color: Int, count: Int) {
        var p = start
        var d = vector.unit()
        val xl = l + r; val xr = right - r; val yt = t + r; val yb = bottom - r
        if (xl >= xr || yt >= yb) return
        for (i in 0 until count) {
            val tx = if (d.x > .0001f) (xr - p.x) / d.x else if (d.x < -.0001f) (xl - p.x) / d.x else Float.POSITIVE_INFINITY
            val ty = if (d.y > .0001f) (yb - p.y) / d.y else if (d.y < -.0001f) (yt - p.y) / d.y else Float.POSITIVE_INFINITY
            val time = min(if (tx > .001f) tx else Float.POSITIVE_INFINITY, if (ty > .001f) ty else Float.POSITIVE_INFINITY)
            if (!time.isFinite()) break
            val end = p + d * time
            line(p, end, color, if (i == 0) 3f else 2f)
            if (abs(tx - time) < 1f) d = V(-d.x, d.y)
            if (abs(ty - time) < 1f) d = V(d.x, -d.y)
            p = end
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return false
        val p = V(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val handles = listOf(gripPoint(cue, 0), gripPoint(target, 1), gripPoint(direction, 2), topLeft, bottomRight)
                val closest = handles.indices.minByOrNull { hypot(p.x - handles[it].x, p.y - handles[it].y) } ?: -1
                drag = if (closest >= 0 && hypot(p.x - handles[closest].x, p.y - handles[closest].y) < 45f * density) closest else -1
                previous = p
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag >= 0) {
                    val delta = (p - previous) * sensitivity
                    when (drag) {
                        0 -> cue += delta
                        1 -> target += delta
                        2 -> direction += delta
                        3 -> topLeft += delta
                        4 -> bottomRight += delta
                    }
                    invalidate()
                }
                previous = p
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag >= 0) save()
                drag = -1
                return true
            }
        }
        return true
    }
}
