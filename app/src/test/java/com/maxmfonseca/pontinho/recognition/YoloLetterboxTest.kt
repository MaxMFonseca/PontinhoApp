package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YoloLetterboxTest {
    @Test
    fun `geometry matches independent square portrait and landscape fixtures`() {
        val cases = YoloGoldenFixture.cases()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val expected = YoloGoldenFixture.geometry(case)
            val actual = LetterboxGeometry.forSource(expected.sourceWidth, expected.sourceHeight)
            assertEquals(case.getString("id"), expected, actual)
        }
    }

    @Test
    fun `geometry rejects padding totals with the wrong Ultralytics split`() {
        val landscape = LetterboxGeometry.forSource(3, 2)
        assertThrows(YoloDecodeException::class.java) {
            landscape.copy(padY = 0.0, padBottom = 213).validate()
        }
        val portrait = LetterboxGeometry.forSource(2, 3)
        assertThrows(YoloDecodeException::class.java) {
            portrait.copy(padX = 0.0, padRight = 213).validate()
        }
    }

    @Test
    fun `prepare matches full pinned Ultralytics byte resize oracles for odd padding and scale`() {
        val cases = YoloGoldenFixture.root.getJSONArray("preprocessCases")
        for (caseIndex in 0 until cases.length()) {
            val case = cases.getJSONObject(caseIndex)
            val width = case.getInt("sourceWidth")
            val height = case.getInt("sourceHeight")
            val rgb = case.getJSONArray("sourceRgb")
            val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until height) for (x in 0 until width) {
                    val offset = (y * width + x) * 3
                    source.setPixel(x, y, Color.rgb(rgb.getInt(offset), rgb.getInt(offset + 1), rgb.getInt(offset + 2)))
                }

                val prepared = YoloLetterbox.prepare(source)

                assertEquals(case.getString("id"), YoloGoldenFixture.geometry(case), prepared.geometry)
                assertEquals(case.getString("id"), case.getString("expectedNormalizedFloat32Sha256"), floatSha256(prepared.pixels))
                val samples = case.getJSONArray("samples")
                for (sampleIndex in 0 until samples.length()) {
                    val sample = samples.getJSONObject(sampleIndex)
                    val expected = sample.getJSONArray("rgb")
                    val offset = (sample.getInt("y") * 640 + sample.getInt("x")) * 3
                    for (channel in 0..2) {
                        assertEquals(
                            case.getString("id"),
                            expected.getInt(channel) / 255f,
                            prepared.pixels[offset + channel],
                            0f,
                        )
                    }
                }
            } finally {
                source.recycle()
            }
        }
    }

    @Test
    fun `prepare produces centered RGB bilinear pixels normalized to zero through one`() {
        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.RED)
            setPixel(1, 0, Color.BLUE)
        }
        try {
            val prepared = YoloLetterbox.prepare(source, size = 4)

            assertArrayEquals(intArrayOf(1, 4, 4, 3), prepared.shape)
            assertEquals(LetterboxGeometry.forSource(2, 1, 4), prepared.geometry)
            val pad = 114f / 255f
            assertPixel(prepared, 0, 0, pad, pad, pad)
            assertPixel(prepared, 0, 1, 1f, 0f, 0f)
            assertPixel(prepared, 1, 1, 191f / 255f, 0f, 64f / 255f)
            assertPixel(prepared, 2, 1, 64f / 255f, 0f, 191f / 255f)
            assertPixel(prepared, 3, 1, 0f, 0f, 1f)
            assertPixel(prepared, 3, 3, pad, pad, pad)
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `prepare rejects transparent pixels and invalid dimensions without recycling input`() {
        val transparent = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(1, 1, Color.TRANSPARENT)
        }
        try {
            assertThrows(IllegalArgumentException::class.java) { YoloLetterbox.prepare(transparent) }
            assertThrows(IllegalArgumentException::class.java) { YoloLetterbox.prepare(transparent, 0) }
            assertFalse(transparent.isRecycled)
        } finally {
            transparent.recycle()
        }
    }

    private fun assertPixel(
        prepared: PreparedYoloImage,
        x: Int,
        y: Int,
        red: Float,
        green: Float,
        blue: Float,
    ) {
        val offset = (y * prepared.geometry.inputWidth + x) * 3
        assertEquals(red, prepared.pixels[offset], 1e-6f)
        assertEquals(green, prepared.pixels[offset + 1], 1e-6f)
        assertEquals(blue, prepared.pixels[offset + 2], 1e-6f)
    }

    private fun floatSha256(values: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { value ->
            bytes.clear()
            bytes.putFloat(value)
            digest.update(bytes.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
