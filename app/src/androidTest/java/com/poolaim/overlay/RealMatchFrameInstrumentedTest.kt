package com.poolaim.overlay

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poolaim.overlay.recognition.OpenCvGuideRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real match frame at 30 seconds from 24910.mp4, reduced to a 32x18,
 * 16-colour indexed thumbnail. Smoke test only: NOT trajectory accuracy.
 * Indexed pixels avoid fragile embedded JPEG/base64 truncation.
 */
@RunWith(AndroidJUnit4::class)
class RealMatchFrameInstrumentedTest {
    @Test fun realMatchThumbnailPassesThroughNativeRecognizer() {
        val palette = intArrayOf(6403277,5154757,4890040,3511481,3703456,2260134,5264471,2384513,6173495,2961213,5447457,2565429,1911105,1975873,1712439,1383729)
        val pixels = Base64.decode(PIXELS, Base64.DEFAULT)
        assertEquals("Real-match indexed fixture must contain 32x18 pixels", 32 * 18, pixels.size)
        val colors = IntArray(pixels.size) { i -> 0xff000000.toInt() or palette[pixels[i].toInt() and 0xff] }
        val thumb = Bitmap.createBitmap(colors, 32, 18, Bitmap.Config.ARGB_8888)
        val frame = Bitmap.createScaledBitmap(thumb, 640, 360, false)
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
            assertNotNull(result)
            val reason = result!!.javaClass.getMethod("getReason").invoke(result).toString()
            assertFalse("OpenCV unavailable: $reason", reason.contains("OpenCV", ignoreCase = true))
            // This 32x18 fixture has destroyed the thin aiming guide: the
            // recognizer must abstain rather than hallucinate a trajectory.
            val guide = result.javaClass.getMethod("getGuide").invoke(result)
            assertNull("No reliable aiming evidence in a 32x18 thumbnail: $reason", guide)
        } finally {
            frame.recycle()
            thumb.recycle()
        }
    }
    companion object {
        private const val PIXELS = "DwsGDw8PDw8JCQ8JCQgICwsGBgsODwkJDAcPDw8JDg8OCQYMDQ0NCwsLCwkJCAgICAYGCQYGBgkGBgwNDAYMDg4NDA0NDQkLCwsLCwsKCgoKCQYLCQgICAYGCQ0MCQwODg0NDQwLCggICAgICAgICgoICAgICAgICAoLDQwHDA8ODQ0NDQoMBwcHBwUFBQUHBwUFBQUHBwcHDAoNDAwMDg4NDQ0OCAcHBAQFBQUFBQMDBQUFBQUFBQUHCAkMDQ0ODg0NDQ4IBQUEBQMDAwMEAgMDAwMDAwUFBQUICQwNDQ4ODQ0NDggHBQMDAwIBAQECAQEBAQIDAwMFBQgJCQ0NDg4NDQ0LCAQDAwIBAQAAAQAAAAIBAQECAwMECAkMDQ0ODg0NCQsIBAMDAQEAAAECAAAAAQIAAgECAwQICQkNDQ4PDQkLDggEAwIBAAAAAAAAAAAAAgAEAgEDBAgJCQ0NDgsJDQ0OCAQDAgEAAAEBAAAAAQIBAAABAgMECAkJDQ0OCw0NDQ4IBAMDAQEBAgIAAAAAAQEBAQEDAwQICQkNDQ4ODQ0NDggEAwMDAQEBAQAAAAABAQECAwMDBQgJCQ0NDg4NDQ0OCAUFBQMDAgQCAQEBAQECAgMDBQUFCAkJDQ0ODg0NDQ0IBwUFBQUDBAQDAwMDAwQEBQUFBQcICQkNDQ4ODQ0NDAsLBwcHBwQHBwQHBwQEBwcHBwcHDAsJCQ0NDg8PDw8PCwoKCgoKCgoKCgoKCgoKCgoKCgoKCw4PDw8P"
    }
}
