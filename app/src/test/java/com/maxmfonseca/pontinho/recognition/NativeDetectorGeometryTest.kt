package com.maxmfonseca.pontinho.recognition

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeDetectorGeometryTest {
    // DetectionTransformationCalculator bounds each float32 origin/extent independently,
    // converting to int toward zero; Java receives only (origin, origin + extent).
    private fun nativeBounds(left: Float, top: Float, right: Float, bottom: Float): RectF {
        fun pixel(value: Float) = (value * 320).toInt().coerceIn(0, 320)
        val x = pixel(left); val y = pixel(top)
        return RectF(x.toFloat(), y.toFloat(), (x + pixel(right - left)).toFloat(), (y + pixel(bottom - top)).toFloat())
    }

    @Test fun `native clamp loses negative origin but production adapter preserves boundary abstention`() {
        val bounds = nativeBounds(-.1f, .1f, .2f, .4f)
        assertEquals(RectF(0f, 32f, 96f, 128f), bounds)
        val box = nativeCardBox(.9f, bounds, 320, 320)
        assertEquals(0f, box.left)
        assertEquals(.3f, box.right)
        assertFalse(isCompleteNativeCard(box, 320, 320))
    }

    @Test fun `integer guard catches exact edges overflow and independently truncated right edge`() {
        for (bounds in listOf(
            nativeBounds(0f, .1f, .2f, .4f), nativeBounds(.1f, 0f, .3f, .4f),
            nativeBounds(.1f, .1f, 1f, .4f), nativeBounds(.1f, .1f, .3f, 1f),
            nativeBounds(.101f, .1f, 1.0001f, .4f), nativeBounds(.1f, .1f, 1.1f, .4f),
            RectF(1f, 32f, 96f, 128f), RectF(32f, 32f, 319f, 128f),
        )) assertFalse(bounds.toString(), isCompleteNativeCard(nativeCardBox(.9f, bounds, 320, 320), 320, 320))
        assertEquals(319f, nativeBounds(.101f, .1f, 1.0001f, .4f).right)
    }

    @Test fun `interior native pixels map deterministically to original resolution`() {
        val box = nativeCardBox(.9f, RectF(2f, 32f, 318f, 128f), 320, 320)
        assertTrue(isCompleteNativeCard(box, 320, 320))
        assertEquals(18.75f, box.left * 3000)
        assertEquals(2981.25f, box.right * 3000)
        assertEquals(200f, box.top * 2000)
        assertEquals(800f, box.bottom * 2000)
    }

    @Test fun `native extent truncation determines the same original crop as Python`() {
        val bounds = nativeBounds(.6f, .1f, .8f, .5f)
        assertEquals(RectF(192f, 32f, 255f, 160f), bounds)
        val box = nativeCardBox(.9f, bounds, 320, 320)
        val source = android.graphics.Bitmap.createBitmap(1000, 500, android.graphics.Bitmap.Config.ARGB_8888)
        val crop = CardCropper.crop(source, com.maxmfonseca.pontinho.domain.recognition.NormalizedRect(box.left, box.top, box.right, box.bottom), .1f)
        try { assertEquals(237, crop.width); assertEquals(240, crop.height) }
        finally { crop.recycle(); source.recycle() }
    }
}
