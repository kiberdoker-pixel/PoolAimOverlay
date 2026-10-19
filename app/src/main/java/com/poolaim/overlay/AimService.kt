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
    private lateinit var bubble: LinearLayout
    private lateinit var aimParams: WindowManager.LayoutParams
    private var editing = true
    private val channel = "pool_aim_channel"

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, "Прицел", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(this, channel).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Pool Aim Overlay").setContentText("Прицел работает поверх игры")
            .setContentIntent(pi).build()
        startForeground(1001, notif)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        aim = AimView(this)
        aimParams = WindowManager.LayoutParams(-1, -1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        wm.addView(aim, aimParams)
        bubble = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(220, 20, 25, 30))
            val toggle = Button(this@AimService).apply {
                text = "🎱 Играть"
                setOnClickListener {
                    editing = !editing
                    aimParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        (if (editing) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    aimParams.alpha = if (editing) 1f else 0.7f // Lower opacity permits touch pass-through on Android 12+
                    wm.updateViewLayout(aim, aimParams)
                    aim.editing = editing
                    aim.invalidate()
                    text = if (editing) "🎱 Играть" else "🎱 Настроить"
                }
            }
            addView(toggle)
            addView(Button(this@AimService).apply { text = "✕"; setOnClickListener { stopSelf() } })
        }
        val bubbleParams = WindowManager.LayoutParams(-2, -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 10; y = 65 }
        wm.addView(bubble, bubbleParams)
    }
    override fun onDestroy() {
        if (::wm.isInitialized) {
            if (::aim.isInitialized) wm.removeView(aim)
            if (::bubble.isInitialized) wm.removeView(bubble)
        }
        super.onDestroy()
    }
}

private data class V(val x: Float, val y: Float) {
    operator fun plus(o: V) = V(x + o.x, y + o.y)
    operator fun minus(o: V) = V(x - o.x, y - o.y)
    operator fun times(s: Float) = V(x * s, y * s)
    fun dot(o: V) = x * o.x + y * o.y
    fun unit(): V { val l = hypot(x, y); return if (l < 0.001f) V(1f, 0f) else V(x / l, y / l) }
}

private class AimView(context: Context) : View(context) {
    private val paint = Paint(3)
    private var initialized = false
    private var cue = V(0f, 0f)
    private var target = V(0f, 0f)
    private var handle = V(0f, 0f)
    private var drag = -1
    var editing = true
    private val r get() = 14f * resources.displayMetrics.density
    private fun line(a: V, b: V, color: Int, stroke: Float = 3f) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke * resources.displayMetrics.density
        canvasRef?.drawLine(a.x, a.y, b.x, b.y, paint)
    }
    private var canvasRef: Canvas? = null
    private fun circle(p: V, color: Int) {
        paint.style = Paint.Style.FILL; paint.color = color; canvasRef?.drawCircle(p.x, p.y, r, paint)
        paint.style = Paint.Style.STROKE; paint.color = Color.BLACK; paint.strokeWidth = 2f; canvasRef?.drawCircle(p.x, p.y, r, paint)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvasRef = canvas
        if (!initialized) { cue = V(width * .28f, height * .55f); target = V(width * .64f, height * .55f); handle = V(width * .48f, height * .55f); initialized = true }
        // Table bounds are approximate; adjust markers to match the game table.
        val left = width * .075f; val right = width * .925f
        val top = height * .20f; val bottom = height * .80f
        paint.style = Paint.Style.STROKE; paint.color = Color.argb(130, 0, 255, 180); paint.strokeWidth = 2f
        canvas.drawRect(left, top, right, bottom, paint)
        val d = (handle - cue).unit()
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
            val objectDir = normal
            val cueResidual = d - normal * objectSpeed
            drawBounces(target, objectDir, left, top, right, bottom, Color.YELLOW, 3)
            if (hypot(cueResidual.x, cueResidual.y) > .08f)
                drawBounces(hitPoint, cueResidual.unit(), left, top, right, bottom, Color.WHITE, 2)
        } else drawBounces(cue, d, left, top, right, bottom, Color.CYAN, 4)
        if (editing) {
            circle(cue, Color.WHITE); circle(target, Color.YELLOW); circle(handle, Color.CYAN)
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textSize = 15f * resources.displayMetrics.scaledDensity
            canvas.drawText("Биток", cue.x - 24f, cue.y - 1.7f*r, paint)
            canvas.drawText("Цель", target.x - 20f, target.y - 1.7f*r, paint)
            canvas.drawText("Направление", handle.x - 38f, handle.y - 1.7f*r, paint)
        }
        canvasRef = null
    }
    private fun drawBounces(start: V, direction: V, l: Float, t: Float, right: Float, bottom: Float, color: Int, count: Int) {
        var p = start; var d = direction.unit()
        val xl = l + r; val xr = right - r; val yt = t + r; val yb = bottom - r
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
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!editing) return false
        val p = V(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pts = listOf(cue, target, handle)
                drag = pts.indices.minByOrNull { hypot(p.x - pts[it].x, p.y - pts[it].y) } ?: -1
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (drag) { 0 -> cue = p; 1 -> target = p; 2 -> handle = p }
                invalidate(); return true
            }
            MotionEvent.ACTION_UP -> { drag = -1; return true }
        }
        return true
    }
}
