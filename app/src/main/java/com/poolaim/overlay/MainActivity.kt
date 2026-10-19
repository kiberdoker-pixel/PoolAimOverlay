package com.poolaim.overlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private var permissionSettingsOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 56, 36, 36)
        }
        root.addView(TextView(this).apply {
            text = "Pool Aim Overlay 🎱\n\nРучной прицел поверх игры. Сначала разреши показ поверх других окон.\n\nВ режиме настройки перетаскивай маркеры: белый — биток, жёлтый — прицельный шар, голубой — направление удара. Нажми «Играть», чтобы касания проходили в игру.\n\nПрогноз приблизительный: не учитывает силу, вращение и особенности физики 8 Ball Pool."
            textSize = 18f
        })
        val start = Button(this).apply { text = "Запустить прицел" }
        root.addView(start)
        root.addView(Button(this).apply {
            text = "🧠 Тест нейросети на скриншоте"
            setOnClickListener { startActivity(Intent(this@MainActivity, ModelProbeActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Остановить прицел"
            setOnClickListener { stopService(Intent(this@MainActivity, AimService::class.java)) }
        })
        setContentView(root)

        start.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openOverlayPermissionSettings()
            } else {
                val i = Intent(this, AimService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                moveTaskToBack(true)
            }
        }

        // Проверяем только при создании экрана, а не при каждом возвращении из настроек.
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermissionSettings()
        }
    }

    private fun openOverlayPermissionSettings() {
        if (permissionSettingsOpened) return
        permissionSettingsOpened = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            // Резервный вариант для устройств с нестандартной прошивкой.
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        // После возврата из настроек разрешаем снова открыть их кнопкой,
        // но не отправляем пользователя обратно автоматически по кругу.
        permissionSettingsOpened = false
    }
}
