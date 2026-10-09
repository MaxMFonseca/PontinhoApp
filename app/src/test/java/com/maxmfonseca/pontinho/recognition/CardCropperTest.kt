package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.graphics.Color
import com.maxmfonseca.pontinho.domain.recognition.NormalizedRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class CardCropperTest {
    @Test fun `closing MediaPipe image never recycles borrowed bitmap even on failure`() {
        val source = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        var owned: Bitmap? = null
        assertThrows(IllegalStateException::class.java) {
            withOwnedMediaPipeImage(source) { image ->
                owned = com.google.mediapipe.framework.image.BitmapExtractor.extract(image)
                assertNotSame(source, owned)
                error("inference failed")
            }
        }
        assertTrue(owned!!.isRecycled)
        assertFalse(source.isRecycled)
        source.recycle()
    }
    @Test fun `crop uses original pixels and selected proportional margin`() {
        val source = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        source.setPixel(450, 200, Color.RED)
        val crop = CardCropper.crop(source, NormalizedRect(.25f, .25f, .5f, .75f), .1f)
        assertEquals(600, crop.width)
        assertEquals(600, crop.height)
        assertEquals(Color.RED, crop.getPixel(0, 0))
        crop.recycle()
        assertFalse(source.isRecycled)
        source.recycle()
    }

    @Test fun `edge padding clips and full frame always creates independently owned pixels`() {
        val source = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val crop = CardCropper.crop(source, NormalizedRect(0f, 0f, .5f, .5f), .1f)
        assertEquals(55, crop.width)
        assertEquals(110, crop.height)
        val full = CardCropper.crop(source, NormalizedRect(0f, 0f, 1f, 1f), 0f)
        assertNotSame(source, full)
        full.recycle()
        assertFalse(source.isRecycled)
        crop.recycle()
        source.recycle()
    }
}
