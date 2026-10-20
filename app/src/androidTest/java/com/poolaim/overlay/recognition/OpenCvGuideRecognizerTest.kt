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
import kotlin.math.abs

/** Exercises the real native OpenCV pipeline. Synthetic fixtures are not game validation. */
@RunWith(AndroidJUnit4::class)
class OpenCvGuideRecognizerTest {
    private val images = mutableListOf<Bitmap>()
    private val bounds = TableBounds(0f, 0f, 1f, 1f)
    private val recognizer = OpenCvGuideRecognizer()

    @Before
    fun requireNativeRuntime() {
        // A native-loader failure must fail these tests, rather than make every negative pass.
        assertTrue("OpenCV Android native library did not initialize", OpenCVLoader.initLocal())
    }

    @After
    fun recycleImages() {
        images.forEach { it.recycle() }
    }

    @Test
    fun blankClothDoesNotProduceAGuide() {
        assertNull(recognizer.inspectBitmap(scene(), bounds).guide)
    }

    @Test
    fun saturatedCyanOverlayIsNotAWhiteGameGuide() {
        val image = scene {
            ball(220f, 180f)
            line(230f, 180f, 285f, 180f, Color.CYAN)
        }
        assertNull(recognizer.inspectBitmap(image, bounds).guide)
    }

    @Test
    fun coloredBallWithoutAGuideDoesNotProduceATrajectory() {
        assertNull(recognizer.inspectBitmap(scene { ball(220f, 180f) }, bounds).guide)
    }

    @Test
    fun longCueLikeLineIsRejectedEvenWithABallAtOneEnd() {
        val image = scene {
            ball(150f, 180f)
            line(160f, 180f, 410f, 180f)
        }
        assertNull(recognizer.inspectBitmap(image, bounds).guide)
    }

    @Test
    fun unattachedShortWhiteLineIsRejected() {
        val image = scene { line(230f, 180f, 285f, 180f) }
        assertNull(recognizer.inspectBitmap(image, bounds).guide)
    }

    @Test
    fun whiteCueBallIsNotAcceptedAsAColoredObjectAnchor() {
        val image = scene {
            ball(220f, 180f, color = Color.WHITE)
            line(230f, 180f, 285f, 180f)
        }
        assertNull(recognizer.inspectBitmap(image, bounds).guide)
    }

    @Test
    fun hollowWhiteGhostCircleIsNotAnObjectBall() {
        val image = scene {
            drawCircle(220f, 180f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f
            })
            line(230f, 180f, 285f, 180f)
        }
        assertNull(recognizer.inspectBitmap(image, bounds).guide)
    }

    @Test
    fun thickWhiteTextLikeBarIsNotAThinGuide() {
        val image = scene {
            ball(220f, 180f)
            drawRect(230f, 175f, 285f, 185f, Paint().apply { color = Color.WHITE })
        }
        assertNull(recognizer.inspectBitmap(image, bounds).guide)
    }

    @Test
    fun uniqueShortGuideHasDirectionAwayFromItsColoredAnchor() {
        val image = scene {
            ball(220f, 180f)
            line(230f, 180f, 285f, 180f)
        }
        val result = recognizer.inspectBitmap(image, bounds)
        assertEquals(640, result.width)
        assertEquals(360, result.height)
        val guide = result.guide
        assertNotNull("Expected a unique synthetic guide: ${result.reason}", guide)
        guide!!
        assertEquals(220f, guide.origin.x, 5f)
        assertEquals(180f, guide.origin.y, 5f)
        assertTrue("Direction must point away from the ball", guide.tip.x > guide.origin.x + 35f)
        assertTrue(abs(guide.tip.y - guide.origin.y) < 5f)
        assertTrue("Ball radius must be measured in capture pixels", guide.ballRadius in 7f..14f)
        assertTrue("Evidence score must pass the conservative gate", guide.score >= .90f)
    }

    @Test
    fun mirroredGuidePointsLeftRatherThanUsingHoughEndpointOrder() {
        val image = scene {
            ball(420f, 180f)
            line(410f, 180f, 355f, 180f)
        }
        val result = recognizer.inspectBitmap(image, bounds)
        val guide = result.guide
        assertNotNull("Expected a mirrored synthetic guide: ${result.reason}", guide)
        guide!!
        assertEquals(420f, guide.origin.x, 5f)
        assertEquals(180f, guide.origin.y, 5f)
        assertTrue("Direction must point left from the anchor", guide.tip.x < guide.origin.x - 35f)
        assertTrue(abs(guide.tip.y - guide.origin.y) < 5f)
    }

    @Test
    fun verticalShortGuidePointsAwayFromItsAnchor() {
        val image = scene {
            ball(320f, 140f)
            line(320f, 150f, 320f, 205f)
        }
        val result = recognizer.inspectBitmap(image, bounds)
        val guide = result.guide
        assertNotNull("Expected a vertical synthetic guide: ${result.reason}", guide)
        guide!!
        assertEquals(320f, guide.origin.x, 5f)
        assertEquals(140f, guide.origin.y, 5f)
        assertTrue(guide.tip.y > guide.origin.y + 35f)
        assertTrue(abs(guide.tip.x - guide.origin.x) < 5f)
    }

    @Test
    fun diagonalShortGuideHasBothForwardComponents() {
        val image = scene {
            ball(220f, 180f)
            line(227f, 187f, 266f, 226f)
        }
        val result = recognizer.inspectBitmap(image, bounds)
        val guide = result.guide
        assertNotNull("Expected a diagonal synthetic guide: ${result.reason}", guide)
        guide!!
        assertEquals(220f, guide.origin.x, 5f)
        assertEquals(180f, guide.origin.y, 5f)
        assertTrue(guide.tip.x > guide.origin.x + 25f)
        assertTrue(guide.tip.y > guide.origin.y + 25f)
        assertTrue(abs((guide.tip.x - guide.origin.x) - (guide.tip.y - guide.origin.y)) < 8f)
    }

    @Test
    fun twoDistinctPlausibleGuidesAreAmbiguous() {
        val image = scene {
            ball(220f, 110f)
            line(230f, 110f, 285f, 110f)
            ball(400f, 250f)
            line(390f, 250f, 335f, 250f)
        }
        assertNull(recognizer.inspectBitmap(image, bounds).guide)
    }

    @Test
    fun aBallAtBothEndsDoesNotEstablishDirection() {
        val image = scene {
            ball(220f, 180f)
            ball(295f, 180f)
            line(230f, 180f, 285f, 180f)
        }
        assertNull(recognizer.inspectBitmap(image, bounds).guide)
    }

    @Test
    fun incomingCueGuideBetweenColoredAndWhiteBallsDoesNotReverseDirection() {
        val image = scene {
            ball(220f, 180f)
            ball(295f, 180f, color = Color.WHITE)
            line(230f, 180f, 285f, 180f)
        }
        assertNull(recognizer.inspectBitmap(image, bounds).guide)
    }

    @Test
    fun incomingCueGuideEndingAtAGhostCircleDoesNotBecomeAnObjectGuide() {
        val image = scene {
            ball(220f, 180f)
            drawCircle(295f, 180f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f
            })
            line(230f, 180f, 285f, 180f)
        }
        assertNull(recognizer.inspectBitmap(image, bounds).guide)
    }

    @Test
    fun configuredTableCropExcludesAnOtherwiseValidGuide() {
        val image = scene {
            ball(220f, 180f)
            line(230f, 180f, 285f, 180f)
        }
        val cropped = TableBounds(.5f, 0f, 1f, 1f)
        val result = recognizer.inspectBitmap(image, cropped)
        assertNull(result.guide)
        assertEquals(cropped, result.bounds)
        assertEquals(640, result.width)
        assertEquals(360, result.height)
    }

    @Test
    fun shiftedRoiPreservesFullCaptureCoordinates() {
        val image = scene {
            ball(220f, 180f)
            line(230f, 180f, 285f, 180f)
        }
        val shifted = TableBounds(.1f, .1f, .8f, .9f)
        val result = recognizer.inspectBitmap(image, shifted)
        val guide = result.guide
        assertNotNull("Expected a guide inside the shifted crop: ${result.reason}", guide)
        guide!!
        assertEquals(shifted, result.bounds)
        assertEquals(220f, guide.origin.x, 5f)
        assertEquals(180f, guide.origin.y, 5f)
        assertTrue(guide.tip.x > 270f)
        assertEquals(180f, guide.tip.y, 5f)
    }

    @Test
    fun scaledFramePreservesBallRadiusAndCaptureCoordinates() {
        val image = scene(width = 1280, height = 720) {
            ball(440f, 360f, radius = 20f)
            line(460f, 360f, 570f, 360f, width = 4f)
        }
        val result = recognizer.inspectBitmap(image, bounds)
        val guide = result.guide
        assertNotNull("Expected a guide in the scaled frame: ${result.reason}", guide)
        guide!!
        assertEquals(1280, result.width)
        assertEquals(720, result.height)
        assertEquals(440f, guide.origin.x, 8f)
        assertEquals(360f, guide.origin.y, 8f)
        assertTrue(guide.tip.x > guide.origin.x + 70f)
        assertEquals(360f, guide.tip.y, 8f)
        assertTrue("Radius must be mapped back from resized ROI", guide.ballRadius in 14f..28f)
    }

    @Test
    fun differentShortAndLongGuidesFromTheSameAnchorRemainAmbiguous() {
        val image = scene {
            ball(220f, 180f)
            line(230f, 180f, 285f, 180f)
            // A real second stroke has its own cap; it must survive endpoint pruning.
            line(229.85f, 178.26f, 259.39f, 173.05f)
        }
        val result = recognizer.inspectBitmap(image, bounds)
        assertNull("Distinct visible directions must not be merged into one guide",
            result.guide)
        assertEquals("Both directions must remain competing hypotheses",
            "Неоднозначное направление линии", result.reason)
    }

    @Test
    fun invalidTableBoundsFailClosed() {
        val image = scene {
            ball(220f, 180f)
            line(230f, 180f, 285f, 180f)
        }
        listOf(
            TableBounds(Float.NaN, 0f, 1f, 1f),
            TableBounds(.8f, 0f, .2f, 1f),
            TableBounds(-.1f, 0f, 1f, 1f)
        ).forEach { invalid ->
            assertNull("Invalid bounds must not produce a guide: $invalid",
                recognizer.inspectBitmap(image, invalid).guide)
        }
    }

    private fun scene(width: Int = 640, height: Int = 360, draw: Canvas.() -> Unit = {}): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
            images += image
            Canvas(image).apply {
                drawColor(Color.rgb(20, 110, 95))
                draw()
            }
        }

    private fun Canvas.ball(x: Float, y: Float, radius: Float = 10f, color: Int = Color.rgb(215, 45, 45)) {
        drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        })
    }

    private fun Canvas.line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int = Color.WHITE, width: Float = 2f) {
        drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = width
            strokeCap = Paint.Cap.BUTT
        })
    }
}
