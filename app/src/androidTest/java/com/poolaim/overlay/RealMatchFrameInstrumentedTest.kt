package com.poolaim.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poolaim.overlay.recognition.OpenCvGuideRecognizer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The JPEG is a small, lossy thumbnail from the user's 24910.mp4 at 30 seconds.
 * This is an integration smoke test, NOT evidence of guide-direction accuracy.
 * Do not infer false-negative rates from this downsampled fixture.
 */
@RunWith(AndroidJUnit4::class)
class RealMatchFrameInstrumentedTest {
    @Test fun realMatchThumbnailPassesThroughNativeRecognizer() {
        val bytes = Base64.decode(FRAME_24910_030, Base64.DEFAULT)
        org.junit.Assert.assertEquals("Match JPEG fixture length changed", 1594, bytes.size)
        val thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Embedded JPEG fixture could not be decoded; replace with verified source frame", thumb)
        val frame = Bitmap.createScaledBitmap(thumb, 640, 288, false)
        try {
            val recognizer = OpenCvGuideRecognizer()
            val method = recognizer.javaClass.methods.single {
                it.name == "inspectBitmap" && it.parameterTypes.size == 2
            }
            val ctor = method.parameterTypes[1].declaredConstructors.single {
                it.parameterTypes.size == 4 &&
                    it.parameterTypes.all { type -> type == Float::class.javaPrimitiveType }
            }
            ctor.isAccessible = true
            val bounds = ctor.newInstance(0.05f, 0.05f, 0.95f, 0.95f)
            val result = method.invoke(recognizer, frame, bounds)
            assertNotNull("Native recognizer must return a result", result)
            val reason = result.javaClass.getMethod("getReason").invoke(result).toString()
            assertFalse("Native OpenCV must be loaded: $reason",
                reason.contains("OpenCV", ignoreCase = true))
        } finally {
            frame.recycle()
            thumb.recycle()
        }
    }

    companion object {
        private const val FRAME_24910_030 = "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"
    }
}
