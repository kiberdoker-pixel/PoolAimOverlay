package com.poolaim.overlay

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Manual, offline model smoke-test: choose a real screenshot, tap a ball, score its crop. */
class ModelProbeActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var info: TextView
    private lateinit var imageView: ProbeView
    private var image: Bitmap? = null
    private var model: PytorchLiteAdapter? = null
    private var generation = 0
    private var imagePixels: IntArray? = null
    private var detected = emptyList<ScoredProposal>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Android 15 edge-to-edge: keep controls below the status bar.
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val top = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                insets.systemWindowInsetTop
            }
            view.setPadding(0, top, 0, 0)
            insets
        }
        info = TextView(this).apply { text = "Выбери оригинальный скриншот 8 Ball Pool, затем коснись центра шара. Это тест классификатора, НЕ поиск шаров."; textSize = 16f; setPadding(16,16,16,16) }
        root.addView(info)
        root.addView(Button(this).apply { text = "Выбрать скриншот"; setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
            startActivityForResult(intent, 100)
        } })
        root.addView(Button(this).apply { text = "НАЙТИ ШАРЫ АВТОМАТИЧЕСКИ (ЭКСПЕРИМЕНТ)"; setOnClickListener { autoScan() } })
        imageView = ProbeView()
        root.addView(imageView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        executor.execute {
            try {
                val file = File(filesDir, "aimx_patch_classifier_mobile.ptl")
                if (!file.exists() || file.length() == 0L) assets.open("aimx_patch_classifier_mobile.ptl").use { input -> file.outputStream().use { input.copyTo(it) } }
                model = PytorchLiteAdapter(file.absolutePath)
                runOnUiThread { info.text = "Модель загружена. Выбери скриншот и коснись центра шара." }
            } catch (e: Exception) { runOnUiThread { info.text = "Ошибка загрузки модели: ${e.message}" } }
        }
    }
    @Deprecated("Legacy Activity API for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("Не удалось открыть изображение")
            image = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            imagePixels = IntArray(image!!.width * image!!.height).also { image!!.getPixels(it, 0, image!!.width, 0, 0, image!!.width, image!!.height) }
            generation++
            detected = emptyList()
            imageView.invalidate()
            info.text = "Скриншот ${bitmap.width}×${bitmap.height}. Коснись центра шара."
        } catch (e: Exception) { info.text = "Ошибка изображения: ${e.message}" }
    }
    private fun autoScan() {
        val b = image ?: run { info.text = "Сначала выбери скриншот"; return }
        val pixels = imagePixels ?: return
        val version = ++generation
        info.text = "Ищу кандидатов и проверяю нейросетью…"
        executor.execute {
            try {
                val m = model ?: error("Модель ещё загружается")
                val proposals = AutoBallProposals.find(pixels, b.width, b.height)
                // Restrict inference count to avoid prolonged UI wait on phones.
                val scores = PatchBallClassifier(m, threshold = 0.80f).score(pixels, b.width, b.height, proposals)
                val uniqueScores = BallResultFilter.deduplicate(scores, 27f * b.width / 3200f)
                runOnUiThread {
                    if (version == generation) {
                        detected = uniqueScores
                        imageView.invalidate()
                        info.text = "Кандидатов: ${proposals.size}; модель приняла: ${scores.size}; после удаления дублей: ${uniqueScores.size}. Эксперимент: возможны пропуски и ложные срабатывания."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { if (version == generation) info.text = "Ошибка автопоиска: ${e.message}" }
            }
        }
    }
    private inner class ProbeView : View(this@ModelProbeActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var markerX = -1f; private var markerY = -1f
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val b = image ?: return
            val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val left = (width - b.width * scale) / 2f; val top = (height - b.height * scale) / 2f
            canvas.drawBitmap(b, null, android.graphics.RectF(left,top,left+b.width*scale,top+b.height*scale), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.GREEN
            for (hit in detected) {
                val p = hit.proposal
                canvas.drawCircle(left+p.x*scale, top+p.y*scale, 27f * (b.width / 3200f).coerceAtLeast(.25f)*scale, paint)
            }
            paint.style = Paint.Style.FILL
            if (markerX >= 0f) { paint.color = Color.MAGENTA; paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; canvas.drawCircle(left+markerX*scale,top+markerY*scale,42f*scale,paint); paint.style = Paint.Style.FILL; paint.color = Color.WHITE }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_UP) return true
            val b = image ?: return true
            val scale = minOf(width.toFloat()/b.width,height.toFloat()/b.height)
            val x = (event.x-(width-b.width*scale)/2f)/scale
            val y = (event.y-(height-b.height*scale)/2f)/scale
            if (x !in 0f..b.width.toFloat() || y !in 0f..b.height.toFloat()) return true
            markerX=x; markerY=y; invalidate()
            val snapshot = generation
            info.text = "Анализирую точку (${x.toInt()}, ${y.toInt()})…"
            executor.execute {
                try {
                    val m = model ?: error("Модель ещё загружается")
                    val pixels = imagePixels ?: error("Пиксели изображения не готовы")
                    val result = PatchBallClassifier(m, threshold = 0f).score(pixels,b.width,b.height,listOf(BallProposal(x,y,27f)))
                    val probability = if (result.isEmpty()) "нет оценки" else "${(result[0].probability*100).toInt()}%"
                    runOnUiThread { if (snapshot==generation) info.text = "Вероятность «шар»: $probability. Это экспериментальный классификатор, не детектор." }
                } catch (e: Exception) { runOnUiThread { if (snapshot==generation) info.text = "Ошибка анализа: ${e.message}" } }
            }
            return true
        }
    }
    override fun onDestroy() { executor.execute { model?.close() }; executor.shutdown(); super.onDestroy() }
}
