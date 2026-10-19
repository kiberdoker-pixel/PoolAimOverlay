package com.poolaim.overlay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val requestCapture = 1201
    private lateinit var status: TextView
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(CaptureService.EXTRA_STATUS)?.let { status.text = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(android.graphics.Color.rgb(10, 17, 29))
        }
        fun label(value: String, size: Float): TextView = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 12, 0, 16)
        }
        root.addView(label("AIMX 8BP  🎱", 29f))
        root.addView(label("Версия 1.2 · Диагностика захвата экрана", 17f))
        status = label("Захват выключен", 16f)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "ЗАПУСТИТЬ ЗАХВАТ ЭКРАНА"
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), requestCapture)
            }
        })
        root.addView(Button(this).apply {
            text = "ОСТАНОВИТЬ ЗАХВАТ"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "Захват остановлен вручную"
                getSharedPreferences("aimx_capture_status", MODE_PRIVATE).edit()
                    .putString(CaptureService.EXTRA_STATUS, status.text.toString()).apply()
            }
        })
        root.addView(label("Статус захвата и количество кадров отображаются прямо здесь. Если возникнет ошибка, пришли скриншот сообщения. Распознавание линии добавим после проверки.", 14f))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        receiverRegistered = true
        status.text = getSharedPreferences("aimx_capture_status", MODE_PRIVATE)
            .getString(CaptureService.EXTRA_STATUS, "Захват выключен") ?: "Захват выключен"
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    @Deprecated("Android activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCapture) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Разрешение на захват не предоставлено"
            return
        }
        val service = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            status.text = "Команда запуска отправлена · ожидаем ответ службы"
        } catch (e: Exception) {
            status.text = "Не удалось запустить службу: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
