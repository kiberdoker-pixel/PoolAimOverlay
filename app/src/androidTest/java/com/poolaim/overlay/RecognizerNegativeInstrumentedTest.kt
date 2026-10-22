package com.poolaim.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poolaim.overlay.recognition.OpenCvGuideRecognizer
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native OpenCV smoke test. It intentionally asserts only the negative case:
 * an empty cloth-colored frame must never produce an object-ball guide.
 * Real match-frame accuracy still needs curated image fixtures.
 */
@RunWith(AndroidJUnit4::class)
class RecognizerNegativeInstrumentedTest {
    @Test
    fun incomingCueGuideWithoutOutgoingBranchMustNotGenerateTrajectory() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(16, 125, 162))
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE
            paint.strokeWidth = 2f
            canvas.drawLine(120f, 275f, 320f, 170f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawCircle(320f, 170f, 11f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.YELLOW
            canvas.drawCircle(341f, 158f, 10f, paint)
            assertNoGuide(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun disconnectedShortWhiteLineMustNotCreateObjectBallTrajectory() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(16, 125, 162))
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 2f
            }
            // Incoming cue line ends at a hollow ghost-ball marker.
            canvas.drawLine(90f, 275f, 300f, 175f, paint)
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(300f, 175f, 11f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.YELLOW
            canvas.drawCircle(322f, 164f, 10f, paint)
            // Unrelated short line nearby: must not be treated as the outgoing guide.
            paint.color = Color.WHITE
            canvas.drawLine(410f, 95f, 451f, 78f, paint)
            assertNoGuide(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun outgoingLookingLineWithoutObjectBallMustNotCreateTrajectory() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(16, 125, 162))
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 2f
            }
            canvas.drawLine(110f, 270f, 315f, 175f, paint)
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(315f, 175f, 11f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawLine(335f, 165f, 379f, 145f, paint)
            // No colored target ball exists; white guide geometry alone is insufficient.
            assertNoGuide(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun multipleColoredBallsWithoutOutgoingGuideMustNotGenerateTrajectory() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(16, 125, 162))
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 2f
            }
            canvas.drawLine(110f, 270f, 315f, 175f, paint)
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(315f, 175f, 11f, paint)
            paint.style = Paint.Style.FILL
            // Nearby colored balls can make the target ambiguous, but neither
            // ball has an outgoing white branch. No trajectory may be invented.
            paint.color = Color.YELLOW
            canvas.drawCircle(336f, 164f, 10f, paint)
            paint.color = Color.RED
            canvas.drawCircle(358f, 179f, 10f, paint)
            paint.color = Color.BLUE
            canvas.drawCircle(385f, 120f, 10f, paint)
            assertNoGuide(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun incomingGuideAndColoredBallWithDisconnectedParallelBranchMustBeRejected() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(16, 125, 162))
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 2f
            }
            canvas.drawLine(110f, 270f, 315f, 175f, paint)
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(315f, 175f, 11f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.YELLOW
            canvas.drawCircle(336f, 164f, 10f, paint)
            // A line that looks like an outgoing branch but begins far beyond
            // the ball must not count as connected evidence.
            paint.color = Color.WHITE
            canvas.drawLine(385f, 138f, 427f, 116f, paint)
            assertNoGuide(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun attachedOutgoingGuideShouldProduceForwardObjectBallTrajectory() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(16, 125, 162))
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 2f
            }
            // A long incoming cue guide terminates at a hollow impact marker.
            canvas.drawLine(110f, 270f, 315f, 175f, paint)
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(315f, 175f, 11f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.YELLOW
            canvas.drawCircle(336f, 164f, 10f, paint)
            // The short white guide starts at the far edge of the target ball
            // and points away from the impact marker.
            paint.color = Color.WHITE
            canvas.drawLine(345f, 159f, 389f, 136f, paint)

            val recognizer = OpenCvGuideRecognizer()
            val method = recognizer.javaClass.methods.single {
                it.name == "inspectBitmap" && it.parameterTypes.size == 2
            }
            val ctor = method.parameterTypes[1].declaredConstructors.first {
                it.parameterTypes.size == 4 &&
                    it.parameterTypes.all { type -> type == Float::class.javaPrimitiveType }
            }
            ctor.isAccessible = true
            val bounds = ctor.newInstance(0.05f, 0.05f, 0.95f, 0.95f)
            val result = method.invoke(recognizer, bitmap, bounds)
            val reason = result.javaClass.getMethod("getReason").invoke(result).toString()
            assertTrue("OpenCV unavailable: $reason", !reason.contains("OpenCV", ignoreCase = true))
            val guide = result.javaClass.getMethod("getGuide").invoke(result)
            assertTrue("Attached outgoing guide should be detected: $reason", guide != null)
            val origin = guide!!.javaClass.getMethod("getOrigin").invoke(guide)
            val tip = guide.javaClass.getMethod("getTip").invoke(guide)
            val ox = (origin.javaClass.getMethod("getX").invoke(origin) as Number).toFloat()
            val tx = (tip.javaClass.getMethod("getX").invoke(tip) as Number).toFloat()
            assertTrue("Outgoing direction must be forward, not reversed", tx > ox)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun fragmentedIncomingGuideWithAttachedOutgoingBranchShouldRemainForward() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(16, 125, 162))
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(170, 170, 170)
                strokeWidth = 2f
            }
            // A dotted native guide, unlike the uninterrupted synthetic fixture.
            val startX = 110f
            val startY = 270f
            val endX = 315f
            val endY = 175f
            for (step in 0 until 16) {
                val a = step / 16f
                val b = (step + .72f) / 16f
                canvas.drawLine(
                    startX + (endX - startX) * a,
                    startY + (endY - startY) * a,
                    startX + (endX - startX) * b,
                    startY + (endY - startY) * b,
                    paint
                )
            }
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(315f, 175f, 11f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.YELLOW
            canvas.drawCircle(336f, 164f, 10f, paint)
            paint.color = Color.WHITE
            canvas.drawLine(345f, 159f, 389f, 136f, paint)

            val recognizer = OpenCvGuideRecognizer()
            val method = recognizer.javaClass.methods.single {
                it.name == "inspectBitmap" && it.parameterTypes.size == 2
            }
            val ctor = method.parameterTypes[1].declaredConstructors.first {
                it.parameterTypes.size == 4 &&
                    it.parameterTypes.all { type -> type == Float::class.javaPrimitiveType }
            }
            ctor.isAccessible = true
            val bounds = ctor.newInstance(0.05f, 0.05f, 0.95f, 0.95f)
            val result = method.invoke(recognizer, bitmap, bounds)
            val reason = result.javaClass.getMethod("getReason").invoke(result).toString()
            val guide = result.javaClass.getMethod("getGuide").invoke(result)
            assertTrue("Fragmented guide should be detected: $reason", guide != null)
            val origin = guide!!.javaClass.getMethod("getOrigin").invoke(guide)
            val tip = guide.javaClass.getMethod("getTip").invoke(guide)
            val ox = (origin.javaClass.getMethod("getX").invoke(origin) as Number).toFloat()
            val tx = (tip.javaClass.getMethod("getX").invoke(tip) as Number).toFloat()
            assertTrue("Fragmented guide must point forward", tx > ox)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun blankTableMustNotGenerateTrajectory() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(15, 95, 65))
            assertNoGuide(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertNoGuide(bitmap: Bitmap) {
            // Keep the actual recognizer in the loop. Obtain the TableBounds
            // model through reflection to avoid coupling to its package name.
            val recognizer = OpenCvGuideRecognizer()
            val method = recognizer.javaClass.methods.single {
                it.name == "inspectBitmap" && it.parameterTypes.size == 2
            }
            val boundsClass = method.parameterTypes[1]
            val ctor = boundsClass.declaredConstructors.firstOrNull { c ->
                c.parameterTypes.size == 4 &&
                    c.parameterTypes.all { it == Float::class.javaPrimitiveType }
            } ?: error("Expected four-float TableBounds constructor; update fixture adapter")
            ctor.isAccessible = true
            val bounds = ctor.newInstance(0.05f, 0.05f, 0.95f, 0.95f)
            val result = method.invoke(recognizer, bitmap, bounds)
            val guide = result.javaClass.getMethod("getGuide").invoke(result)
            assertNull("No aiming evidence: must not draw a line", guide)
            val reason = result.javaClass.getMethod("getReason").invoke(result).toString()
            assertTrue("Unexpected result reason: $reason", reason.isNotBlank())
            assertTrue("OpenCV was unavailable; negative test is inconclusive: $reason",
                !reason.contains("OpenCV", ignoreCase = true))
    }
}
