package com.poolaim.overlay

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.media.Image
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val ACTION_STATUS = "com.poolaim.overlay.CAPTURE_STATUS"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL = "aimx_capture"
        private const val NOTIFICATION_ID = 1202
        private const val PREFS = "aimx_capture_status"
    }
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    private var frameCount = 0L
    private var stopping = false
    private var startedForeground = false
    private var detectorResult = "Анализ ещё не выполнялся"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "AimX 8BP · захват", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun report(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(EXTRA_STATUS, message).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun notification(message: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        if (projection != null) {
            report("Захват уже работает · кадров: $frameCount")
            return START_NOT_STICKY
        }
        try {
            // A mediaProjection foreground service must be started before getMediaProjection().
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification("Подключение…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification("Подключение…"))
            }
            startedForeground = true
            report("Служба запущена · ожидаем разрешение")
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            if (data == null || code != Activity.RESULT_OK) {
                report("Ошибка: нет подтверждённого разрешения на захват")
                stopSelf()
                return START_NOT_STICKY
            }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(code, data)
            projection = mp
            worker = HandlerThread("AimX-Capture").apply { start() }
            val handler = Handler(worker!!.looper)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    report("Захват завершён системой")
                    stopSelf()
                }
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
                        if (frameCount % 15L == 0L) {
                            try {
                                detectorResult = LineDetector.inspect(image)
                            } catch (e: Exception) {
                                detectorResult = "Ошибка анализа: ${e.javaClass.simpleName}"
                                android.util.Log.e("AimXDetector", "Analysis failed", e)
                            }
                        }
                        if (frameCount == 1L || frameCount % 30L == 0L) {
                            val msg = "Кадров: $frameCount · $detectorResult"
                            report(msg)
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification(msg))
                        }
                    } finally { image.close() }
                }
            }, handler)
            display = mp.createVirtualDisplay("AimX 8BP capture", width, height,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, handler)
            report("Захват подключён · ожидаем первые кадры")
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("Ожидание кадров…"))
        } catch (e: Exception) {
            val msg = "Ошибка ${e.javaClass.simpleName}: ${e.message ?: "нет подробностей"}"
            android.util.Log.e("AimXCapture", msg, e)
            report(msg)
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
        if (startedForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
