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
        val thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(thumb)
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
        private const val FRAME_24910_030 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABsSFBcUERsXFhceHBsgKEIrKCUlKFE6PTBCYFVlZF9VXVtqeJmBanGQc1tdhbWGkJ6jq62rZ4C8ybqmx5moq6T/2wBDARweHigjKE4rK06kbl1upKSkpKSkpKSkpKSkpKSkpKSkpKSkpKSkpKSkpKSkpKSkpKSkpKSkpKT/wAARCABIAKADASIAAhEBAxEB/8QAGQAAAwEBAQAAAAAAAAAAAAAAAAIDBAEF/8QALBAAAgEDAgMJAQADAQAAAAAAAQIAAxEhEjEEE1EUIjJBUmFxkaGBBSOxYv/EABgBAAMBAQAAAAAAAAAAAAAAAAABAgME/8QAIBEAAgIBBQEBAQAAAAAAAAAAAAECERIhMUFRcRNCMv/aAAwDAQACEQMRAD8A8MKJ6IocL2DVoHN09c3nnjN52OUcuSJKxdIhpEacLinYk5lPQpKwCLfOI6VdFFqfLU6vM7iNynbK2ZTkHrA0Km9hn5kNp7lqDIhQd8fMpqApinpQ9697Zj8iqbY2+Zzs9X0/9hYYM0ql6YbUlrbYmamiPVJqMABkjrG7PV9A/YjrVoDmkAW9pCTpmktaHFKl2mmFKsjHbpNr8GjDbQADnSMn4mFOdWZa62J8jbpL6+LAIGkX/wDMTUqM8XbaMqUab5NVUN/CQbx+yC5BqKNPiurd38nBw9UMG05HnmXL8UWqnH+0WbBmrkGDIuz1KVOj2cC1iGVTcjrKpR4ejXUterSItcDz6QVuJV0caboukYO04rV0RQSqqja72OIWDhoZ6qAVWAUqL7EWtE0iUqVzxNR6rHvH2iylqQ1RQ06fJwM9ZBlAE1tRpjhg+q7EbX2mVtoUK7Bdo0Vdo0YBHp0VrrZhlb2IiTRwVu9nrE90Utn4UpjSLAd0jYHb4lTSYAP3wjX0kneCKSo8pUKSALmw2E1rW0cmVKmR0d7us5HvOhG9TfcuqZEYJGtCbM+hvU33A07izEkdCbzUKc466Vuq3IGwzf3g2C1MwpaRZSVHQYhob1N9zXy5wpGLUyaG9Tfc4VPqb7mopFKQCzKVPqb7iut1OokjoZpKSbr3TAdmJqC0gFQZbzJiMhXeaOMOllI3xIsbgk9BMFuztey8F1ta2o2iNtGittGSC7RpxFJW+w6xjYeE394rGAAGW+vOaOByzY2Bmaaf8f4m+DFyh/mXhtWypc+QlKdmFxFp+EfEqs3OEZVzHCwXecvdrRDRxzYDukrfNhmNTW5v7Tl7kDaXFgIiibCw95JlIBIIx7StW98bjIkme4sBa8BUdSzCKdN7Xz/2dQW/sWpRRm1ZGbm3n5xhS5FZZKoO6fiXaSqeE/EZJg4/xL8CQPh/gl+P8S/AkW8H8Ew5Z3fmPgkVto0VtpRJ1WJUAnAnYq7RoIAmngcFvgzNL8IbaifeJ7opfzLw3o3dHxKK0zjUqKxBCtsesYPNzhZqVswyTvIK+ROVG1UyA+j3iY1vRpC+8rrmOm4CABr2FrmPzItxu06ZoYgxLC2+eslzJzmR0KyoNs3uZwtJVCRyyCLMDfN4heJajkqKM0nUbun4il5N2uDKJIcdkr/JE+D+CU4s6tJHtJHw/wAE5+Wd35j4LFbaNFbaUScVgBO6hCEVgGoTlTU6qFfA8r7GEInqUjUtdFQIzF7edzO9oo9P0whJykuTTCD3R0cVS6fph2qj6f2EIZy7D5w6OjiqIGFHxcxKvFjlnld1/IwhDOXYvnDoKXFjljm95/Mx+10vT+mEIZy7D5w6Odqo+kfcDxNLp+mEIZy7D5w6Odoo9P0zjVqbAqp0k7HpCEM5dh84dGWnqRWDNg+V9zKNWBphbZGLwhKRDYmoTjMCIQjsk//Z"
    }
}
