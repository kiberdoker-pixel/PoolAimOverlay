package com.poolaim.overlay.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** Native OpenCV tests for the line-only mode; no ball detection is involved. */
@RunWith(AndroidJUnit4::class)
class OpenCvGuideRecognizerTest {
    private val images = mutableListOf<Bitmap>()
    private val recognizer = OpenCvGuideRecognizer()
    private val bounds = TableBounds(0f, 0f, 1f, 1f)

    @Before fun nativeLibraryLoads() {
        assertTrue(OpenCVLoader.initLocal())
    }

    @After fun recycle() { images.forEach { it.recycle() } }

    @Test fun blankTableHasNoLine() {
        assertNull(recognizer.inspectBitmap(scene(), bounds).guide)
    }

    @Test fun whiteLineIsDetectedWithoutAnyBall() {
        val result = recognizer.inspectBitmap(scene {
            line(230f, 180f, 285f, 180f)
        }, bounds)
        val guide = result.guide
        assertNotNull(result.reason, guide)
        assertTrue(guide!!.tip.x > guide.origin.x)
        assertTrue(guide.tip.x - guide.origin.x > 35f)
    }

    @Test fun grayLineIsDetectedWithoutAnyBall() {
        val result = recognizer.inspectBitmap(scene {
            line(230f, 180f, 285f, 180f, Color.rgb(165, 165, 165))
        }, bounds)
        assertNotNull(result.reason, result.guide)
    }

    @Test fun saturatedCyanOverlayIsIgnored() {
        val result = recognizer.inspectBitmap(scene {
            line(230f, 180f, 285f, 180f, Color.CYAN)
        }, bounds)
        assertNull(result.guide)
    }

    @Test fun longLineIsAcceptedWithoutAnyBall() {
        val result = recognizer.inspectBitmap(scene {
            line(160f, 180f, 410f, 180f)
        }, bounds)
        assertNotNull(result.reason, result.guide)
    }

    @Test fun cropUsesFullCaptureCoordinates() {
        val cropped = TableBounds(.1f, .1f, .8f, .9f)
        val result = recognizer.inspectBitmap(scene {
            line(230f, 180f, 285f, 180f)
        }, cropped)
        assertNotNull(result.reason, result.guide)
        assertEquals(640, result.width)
        assertEquals(360, result.height)
        assertEquals(cropped, result.bounds)
        assertTrue(result.guide!!.origin.x in 225f..290f)
        assertTrue(result.guide!!.tip.x in 225f..290f)
    }

    @Test fun scaledFrameMapsBackToCapturePixels() {
        val result = recognizer.inspectBitmap(scene(1280, 720) {
            line(460f, 360f, 570f, 360f, width = 4f)
        }, bounds)
        assertNotNull(result.reason, result.guide)
        assertEquals(1280, result.width)
        assertEquals(720, result.height)
        assertTrue(result.guide!!.origin.x in 450f..580f)
        assertTrue(result.guide!!.tip.x in 450f..580f)
    }

    @Test fun invalidBoundsReject() {
        val result = recognizer.inspectBitmap(scene {
            line(230f, 180f, 285f, 180f)
        }, TableBounds(-.1f, 0f, 1f, 1f))
        assertNull(result.guide)
    }

    private fun scene(width: Int = 640, height: Int = 360, draw: Canvas.() -> Unit = {}): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            images += bitmap
            Canvas(bitmap).apply {
                drawColor(Color.rgb(20, 110, 95))
                draw()
            }
        }

    private fun Canvas.line(
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Int = Color.WHITE, width: Float = 2f
    ) {
        drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = width
            strokeCap = Paint.Cap.BUTT
        })
    }
}
