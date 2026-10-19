package com.poolaim.overlay

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager

/** Stage 1: receive and close frames safely; no image analysis or saving yet. */
class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        private const val CHANNEL = "aimx_capture"
        private const val NOTIFICATION_ID = 1202
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    private var frameCount = 0L
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AimX 8BP · захват", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(message: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("AimX 8BP · захват экрана")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        if (code != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification("Подключение…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification("Подключение…"))
            }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(code, data)
            projection = mp
            worker = HandlerThread("AimX-Capture").apply { start() }
            val handler = Handler(worker!!.looper)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            val metrics = android.util.DisplayMetrics()
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage()
                if (image != null) {
                    try {
                        frameCount++
                        if (frameCount % 30L == 0L) {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification("Получено кадров: $frameCount"))
                        }
                    } finally { image.close() }
                }
            }, handler)
            display = mp.createVirtualDisplay(
                "AimX 8BP capture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, handler
            )
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("Ожидание кадров…"))
        } catch (e: Exception) {
            android.util.Log.e("AimXCapture", "Capture start failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!stopping) {
            stopping = true
            reader?.setOnImageAvailableListener(null, null)
            display?.release()
            display = null
            reader?.close()
            reader = null
            projection?.stop()
            projection = null
            worker?.quitSafely()
            worker = null
        }
        super.onDestroy()
    }
}
