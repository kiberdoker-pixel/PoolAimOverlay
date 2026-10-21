package com.poolaim.overlay

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
    fun blankTableMustNotGenerateTrajectory() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(15, 95, 65))
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
        } finally {
            bitmap.recycle()
        }
    }
}
