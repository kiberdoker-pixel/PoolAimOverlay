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
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import com.poolaim.overlay.recognition.FrameGuideResult
import com.poolaim.overlay.recognition.GuideStabilityGate
import com.poolaim.overlay.recognition.OpenCvGuideRecognizer

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
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0
    private var captureHandler: Handler? = null
    private var frameCount = 0L
    @Volatile private var stopping = false
    private var startedForeground = false
    private var detectorResult = "Анализ ещё не выполнялся"
    private var overlay: DiagnosticOverlay? = null
    private var visualOverlay: VisualLinesOverlay? = null
    private var tableOverlay: TableBoundsOverlay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val guideRecognizer = OpenCvGuideRecognizer()
    private val guideGate = GuideStabilityGate()
    private var lastAnalysisAt = 0L

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
            if (Settings.canDrawOverlays(this)) {
                mainHandler.post {
                    try {
                        if (overlay == null) overlay = DiagnosticOverlay(this).also { it.show() }
                        if (visualOverlay == null) visualOverlay = VisualLinesOverlay(this).also { it.show() }
                        if (tableOverlay == null) tableOverlay = TableBoundsOverlay(this).also { it.show() }
                    } catch (e: Exception) {
                        android.util.Log.e("AimXOverlay", "Cannot show overlay", e)
                    }
                }
            }
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
            captureHandler = handler
            val metrics = currentDisplayMetrics()
            captureWidth = metrics.widthPixels.coerceAtLeast(1)
            captureHeight = metrics.heightPixels.coerceAtLeast(1)
            captureDensity = metrics.densityDpi.coerceAtLeast(1)
            reader = makeReader(captureWidth, captureHeight, handler)
            display = mp.createVirtualDisplay("AimX 8BP capture", captureWidth, captureHeight,
                captureDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, handler)
            handler.post(rotationCheck)
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

    @Suppress("DEPRECATION")
    private fun currentDisplayMetrics(): android.util.DisplayMetrics =
        android.util.DisplayMetrics().also {
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
        }

    private fun makeReader(width: Int, height: Int, handler: Handler): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ source ->
                val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
                if (image != null) {
                    try {
                        frameCount++
                        val capturedAt = SystemClock.elapsedRealtime()
                        if (!stopping && capturedAt - lastAnalysisAt >= 120L) {
                            lastAnalysisAt = capturedAt
                            try {
                                val bounds = TableBoundsOverlay.snapshot()
                                val detected = if (TableBoundsOverlay.editing) {
                                    guideGate.reset()
                                    FrameGuideResult(image.width, image.height, bounds,
                                        reason = "Настройка границ стола · прогноз скрыт")
                                } else {
                                    guideGate.filter(guideRecognizer.inspect(image, bounds), capturedAt)
                                }
                                detectorResult = detected.reason
                                mainHandler.post {
                                    if (!stopping) visualOverlay?.update(detected, capturedAt)
                                }
                            } catch (e: Exception) {
                                clearRecognition("Ошибка анализа: ${e.javaClass.simpleName}", image.width, image.height)
                                android.util.Log.e("AimXDetector", "Analysis failed", e)
                            } catch (e: LinkageError) {
                                clearRecognition("OpenCV недоступна · прогноз скрыт", image.width, image.height)
                                android.util.Log.e("AimXDetector", "OpenCV initialization failed", e)
                            }
                        }
                        if (frameCount == 1L || frameCount % 30L == 0L) {
                            val msg = "Кадров: $frameCount · $detectorResult"
                            report(msg)
                            mainHandler.post {
                                overlay?.update("$msg\nРисование: ${visualOverlay?.drawStatus() ?: "нет окна"}")
                            }
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification(msg))
                        }
                    } finally { image.close() }
                }
            }, handler)
        }

    private fun clearRecognition(message: String, width: Int, height: Int) {
        guideGate.reset()
        detectorResult = message
        val empty = FrameGuideResult(width, height, TableBoundsOverlay.snapshot(), reason = message)
        mainHandler.post { if (!stopping) visualOverlay?.update(empty, SystemClock.elapsedRealtime()) }
    }

    // Android 15: MediaProjection is single-use. Resize the existing virtual display;
    // do NOT request another projection or create another virtual display on rotation.
    private val rotationCheck = object : Runnable {
        override fun run() {
            if (stopping || projection == null) return
            try {
                val metrics = currentDisplayMetrics()
                val newWidth = metrics.widthPixels.coerceAtLeast(1)
                val newHeight = metrics.heightPixels.coerceAtLeast(1)
                val newDensity = metrics.densityDpi.coerceAtLeast(1)
                if (newWidth != captureWidth || newHeight != captureHeight || newDensity != captureDensity) {
                    val vd = display
                    val handler = captureHandler
                    if (vd != null && handler != null) {
                        val replacement = makeReader(newWidth, newHeight, handler)
                        val previous = reader
                        vd.setSurface(null)
                        vd.resize(newWidth, newHeight, newDensity)
                        vd.setSurface(replacement.surface)
                        reader = replacement
                        previous?.setOnImageAvailableListener(null, null)
                        previous?.close()
                        captureWidth = newWidth
                        captureHeight = newHeight
                        captureDensity = newDensity
                        clearRecognition("Поворот: ${newWidth}x${newHeight} · ожидаем анализ", newWidth, newHeight)
                        report(detectorResult)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AimXCapture", "Resize failed", e)
                report("Ошибка поворота: ${e.javaClass.simpleName}")
            }
            if (!stopping && projection != null) captureHandler?.postDelayed(this, 1000L)
        }
    }

    override fun onDestroy() {
        mainHandler.post {
            tableOverlay?.hide(); tableOverlay = null
            visualOverlay?.hide(); visualOverlay = null
            overlay?.hide(); overlay = null
        }
        if (!stopping) {
            stopping = true
            captureHandler?.removeCallbacks(rotationCheck)
            captureHandler = null
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
