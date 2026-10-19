package com.poolaim.overlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val requestCapture = 1201
    private lateinit var status: TextView

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
        root.addView(label("Версия 1.2 · Проверка захвата экрана", 17f))
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
                status.text = "Захват остановлен"
            }
        })
        root.addView(label("Android запросит разрешение на запись экрана. После подтверждения кадры обрабатываются только на устройстве. Количество кадров видно в уведомлении AimX 8BP. Распознавание линии добавим следующим этапом.", 14f))
        setContentView(root)
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
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        status.text = "Запуск захвата… проверь уведомление"
    }
}
