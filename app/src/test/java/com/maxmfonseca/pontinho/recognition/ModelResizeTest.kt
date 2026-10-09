package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mediapipe.framework.image.BitmapExtractor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelResizeTest {
    @Test fun `real MediaPipe preparation matches shared exact RGB goldens at every target size`() {
        val fixture = JSONObject(checkNotNull(javaClass.classLoader!!.getResourceAsStream("model-resize-golden.json")).bufferedReader().use { it.readText() })
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val sourceSize = case.getJSONArray("sourceSize")
            val target = case.getJSONArray("targetSize")
            val source = Bitmap.createBitmap(sourceSize.getInt(0), sourceSize.getInt(1), Bitmap.Config.ARGB_8888)
            for (y in 0 until source.height) for (x in 0 until source.width) {
                fun channel(c: Int): Int = if (case.has("rgb")) case.getJSONArray("rgb").getInt((y * source.width + x) * 3 + c)
                    else case.getJSONArray("pattern").getJSONArray(c).let { (it.getInt(0) * x + it.getInt(1) * y + it.getInt(2)) % 256 }
                source.setPixel(x, y, Color.rgb(channel(0), channel(1), channel(2)))
            }
            val before = fingerprint(source)
            var owned: Bitmap? = null
            try {
                withOwnedMediaPipeImage(source, target.getInt(0), target.getInt(1)) { image ->
                    val bitmap = BitmapExtractor.extract(image).also { owned = it }
                    assertNotSame(source, bitmap)
                    assertEquals(case.getString("id"), case.getString("expectedFnv1a64Rgb"), fingerprint(bitmap))
                    val samples = case.getJSONArray("samples")
                    for (s in 0 until samples.length()) {
                        val sample = samples.getJSONObject(s)
                        val rgb = sample.getJSONArray("rgb")
                        assertEquals(case.getString("id"), Color.rgb(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2)), bitmap.getPixel(sample.getInt("x"), sample.getInt("y")))
                    }
                }
                assertTrue(owned!!.isRecycled)
                assertFalse(source.isRecycled)
                assertEquals(before, fingerprint(source))
            } finally { source.recycle() }
        }
    }

    @Test fun `transparent input fails before inference even if hidden between sample locations`() {
        val source = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE); setPixel(0, 0, Color.TRANSPARENT) }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                withOwnedMediaPipeImage(source, 1, 1) { error("transparent pixels must never reach inference") }
            }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    private fun fingerprint(bitmap: Bitmap): String {
        var hash = 0xcbf29ce484222325uL
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            assertEquals(255, Color.alpha(pixel))
            for (shift in listOf(16, 8, 0)) hash = (hash xor ((pixel ushr shift) and 255).toULong()) * 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }
}
