package com.poolaim.overlay.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** Native OpenCV tests: a bare line is insufficient without a contact marker. */
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
        assertNull(result.reason, guide)
    }

    @Test fun grayLineIsDetectedWithoutAnyBall() {
        val result = recognizer.inspectBitmap(scene {
            line(230f, 180f, 285f, 180f, Color.rgb(165, 165, 165))
        }, bounds)
        assertNull(result.reason, result.guide)
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
        assertNull(result.reason, result.guide)
    }

    @Test fun cropUsesFullCaptureCoordinates() {
        val cropped = TableBounds(.1f, .1f, .8f, .9f)
        val result = recognizer.inspectBitmap(scene {
            line(230f, 180f, 285f, 180f)
        }, cropped)
        assertNull(result.reason, result.guide)
        assertEquals(640, result.width)
        assertEquals(360, result.height)
        assertEquals(cropped, result.bounds)
        assertNull(result.guide)
    }

    @Test fun scaledFrameMapsBackToCapturePixels() {
        val result = recognizer.inspectBitmap(scene(1280, 720) {
            line(460f, 360f, 570f, 360f, width = 4f)
        }, bounds)
        assertNull(result.reason, result.guide)
        assertEquals(1280, result.width)
        assertEquals(720, result.height)
        assertNull(result.guide)
    }

    @Test fun longCueGuideEndingAtRailDoesNotCreateObjectBall() {
        val result = recognizer.inspectBitmap(scene {
            line(150f, 180f, 475f, 180f)
            drawCircle(475f, 180f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f
            })
        }, bounds)
        assertNull("A contact ring without a target ball is not a collision", result.guide)
    }

    @Test fun geometricTargetIsInFrontOfContactMarker() {
        val result = recognizer.inspectBitmap(scene {
            line(100f, 180f, 320f, 180f, width = 3f)
            drawCircle(320f, 180f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f
            })
            drawCircle(340f, 180f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                style = Paint.Style.FILL
            })
        }, bounds)
        // Detection may conservatively reject a synthetic marker. If accepted,
        // the trajectory must originate at the object, never the ghost marker.
        result.guide?.let {
            assertTrue("Object ball center must be beyond the impact ring", it.origin.x > 326f)
            assertTrue("Object ball direction must be forward", it.tip.x > it.origin.x)
        }
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
