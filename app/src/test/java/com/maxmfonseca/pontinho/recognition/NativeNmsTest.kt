package com.maxmfonseca.pontinho.recognition

import android.graphics.RectF
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

}
