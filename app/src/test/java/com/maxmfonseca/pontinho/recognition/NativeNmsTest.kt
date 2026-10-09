package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.graphics.RectF
import com.maxmfonseca.pontinho.domain.recognition.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
class NativeNmsTest {
    private fun cases() = JSONObject(checkNotNull(javaClass.classLoader!!.getResourceAsStream("native-nms-golden.json")).bufferedReader().use { it.readText() }).getJSONArray("cases")

    @Test fun `all native coordinates round trip exactly and off grid values are rejected`() {
        for (x in 0 until 320) {
            val box = nativeCardBox(.9f, RectF(x.toFloat(), 0f, x + 1f, 320f), 320, 320)
            assertEquals(listOf(x, 0, x + 1, 320), nativeNmsBox(box).bounds)
        }
        assertThrows(IllegalArgumentException::class.java) { nativeNmsBox(RawCardBox(.9f, .101f, .1f, .3f, .4f)) }
    }

    @Test fun `integer NMS goldens include exact limits invalid area and products beyond Long`() {
        val cases = cases()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            fun actual(): List<Int> {
                val inputs = case.getJSONArray("boxes")
                return nativeNms((0 until inputs.length()).map { j ->
                    val b = inputs.getJSONObject(j)
                    NativeNmsBox(b.getDouble("score").toFloat(), b.getJSONArray("bounds").let { values -> (0..3).map(values::getInt) })
                }, case.getDouble("threshold").toFloat())
            }
            if (case.optBoolean("invalid")) assertThrows(IllegalArgumentException::class.java) { actual() }
            else assertEquals(case.getString("id"), case.getJSONArray("expected").let { values -> (0 until values.length()).map(values::getInt) }, actual())
        }
    }

    @Test fun `engine NMS agrees with shared goldens regardless of original resolution`() = runBlocking {
        val cases = cases()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            if (case.optBoolean("invalid") || case.optBoolean("helperOnly")) continue
            val boxes = case.getJSONArray("boxes")
            val raw = (0 until boxes.length()).map { index ->
                val item = boxes.getJSONObject(index); val b = item.getJSONArray("bounds")
                nativeCardBox(item.getDouble("score").toFloat(), RectF(b.getInt(0).toFloat(), b.getInt(1).toFloat(), b.getInt(2).toFloat(), b.getInt(3).toFloat()), 320, 320)
            }
            val expected = case.getJSONArray("expected").let { indices -> (0 until indices.length()).map { index ->
                boxes.getJSONObject(indices.getInt(index)).getJSONArray("bounds").let { b -> (0..3).map(b::getInt) }
            } }
            for ((width, height) in listOf(320 to 320, 1000 to 500, 3000 to 2000)) {
                val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val release = BundleFixture.json().apply { getJSONObject("thresholds").put("iou", case.get("threshold")) }
                val metadata = (RecognitionAssetVerifier().verify(BundleFixture.detector, BundleFixture.classifier, release.toString().encodeToByteArray()) as RecognitionAvailability.Available).metadata as RecognitionModelBundleMetadata
                val engine = TwoStageCardRecognitionEngine(object : CardDetectorRunner {
                    override fun detect(bitmap: Bitmap) = raw
                    override fun close() = Unit
                }, object : CardClassifierRunner {
                    override fun classify(bitmap: Bitmap) = RawCardClassification(FloatArray(52).apply { this[0] = 1f })
                    override fun close() = Unit
                }, metadata, Dispatchers.Unconfined)
                try {
                    val result = engine.recognize(source)
                    assertEquals(case.getString("id"), expected, result.candidates.map { card ->
                        listOf(card.bounds.left, card.bounds.top, card.bounds.right, card.bounds.bottom).map { (it * 320).roundToInt() }
                    })
                } finally { engine.close(); source.recycle() }
            }
        }
    }
}
