package com.poolaim.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import kotlin.math.max

/**
 * Diagnostic prototype, NOT a reliable object-ball trajectory detector.
 * Samples captured pixels and searches for thin bright runs.
 * Does not save, transmit, or draw any captured content.
 */
internal object LineDetector {
    fun inspect(image: Image): String {
        val plane = image.planes.firstOrNull() ?: return "Нет пикселей"
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 4 || image.width < 1 || image.height < 1) return "Неизвестный формат"
        val buffer = plane.buffer.duplicate()
        val width = image.width
        val height = image.height
        val step = max(1, max(width / 180, height / 110))
        val cols = (width + step - 1) / step
        val rows = (height + step - 1) / step
        val bright = BooleanArray(cols * rows)
        var turquoise = 0
        var white = 0
        for (y in 0 until rows) {
            val py = minOf(height - 1, y * step)
            for (x in 0 until cols) {
                val px = minOf(width - 1, x * step)
                val pos = py.toLong() * rowStride + px.toLong() * pixelStride
                if (pos + 2 >= buffer.limit()) continue
                val offset = pos.toInt()
                // RGBA_8888 image plane is RGBA byte order.
                val r = buffer.get(offset).toInt() and 255
                val g = buffer.get(offset + 1).toInt() and 255
                val b = buffer.get(offset + 2).toInt() and 255
                if (g > 65 && b > 65 && g > r * 1.22 && b > r * 1.12) turquoise++
                val isWhite = r > 188 && g > 188 && b > 188 &&
                    maxOf(r, g, b) - minOf(r, g, b) < 45
                bright[y * cols + x] = isWhite
                if (isWhite) white++
            }
        }
        // This is a rough candidate score, not a classification of the two ball paths.
        val directions = arrayOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
        var longest = 0
        for ((dx, dy) in directions) {
            for (y in 0 until rows) for (x in 0 until cols) {
                if (!bright[y * cols + x]) continue
                val beforeX = x - dx
                val beforeY = y - dy
                if (beforeX in 0 until cols && beforeY in 0 until rows &&
                    bright[beforeY * cols + beforeX]) continue
                var cx = x
                var cy = y
                var length = 0
                while (cx in 0 until cols && cy in 0 until rows && bright[cy * cols + cx]) {
                    length++
                    cx += dx
                    cy += dy
                }
                longest = max(longest, length)
            }
        }
        return "Бирюза:$turquoise Белые:$white Отрезок:$longest"
    }
}
