package com.maxmfonseca.pontinho.recognition

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YoloPostProcessorTest {
    @Test
    fun `decoder matches every independently generated Python golden`() {
        val root = YoloGoldenFixture.root
        assertEquals(listOf(1, 56, 8400), root.getJSONArray("tensorShape").toIntList())
        assertEquals(root.getJSONArray("labels").toStringList(), YoloCardLabels.values)
        root.getJSONObject("thresholdRepresentation").let { representation ->
            assertEquals("IEEE-754 binary64 parsed directly from JSON", representation.getString("metadata"))
            assertEquals(
                "threshold normalized to IEEE-754 binary32; raw score > threshold",
                representation.getString("confidenceComparison"),
            )
            assertEquals("IEEE-754 binary64", representation.getString("iouAndGeometry"))
        }
        val cases = YoloGoldenFixture.cases()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val output = YoloGoldenFixture.output(case)
            val geometry = YoloGoldenFixture.geometry(case)
            when (case.optString("expectedError")) {
                "DECODE_ERROR" -> assertThrows(case.getString("id"), YoloDecodeException::class.java) {
                    YoloPostProcessor.decode(output, intArrayOf(1, 56, 8400), geometry, YoloGoldenFixture.settings(case))
                }
                "TOO_MANY_DETECTIONS" -> {
                    val error = assertThrows(case.getString("id"), TooManyYoloDetections::class.java) {
                        YoloPostProcessor.decode(output, intArrayOf(1, 56, 8400), geometry, YoloGoldenFixture.settings(case))
                    }
                    assertEquals(case.getString("id"), case.getInt("expectedCount"), error.count)
                    assertDetections(case, error.detections)
                }
                "INCOMPLETE_DETECTIONS" -> {
                    val error = assertThrows(case.getString("id"), IncompleteYoloDetections::class.java) {
                        YoloPostProcessor.decode(
                            output,
                            intArrayOf(1, 56, 8400),
                            geometry,
                            YoloGoldenFixture.settings(case),
                        )
                    }
                    assertEquals(
                        case.getString("id"),
                        case.getJSONArray("expectedIncompleteAnchors").toIntList(),
                        error.anchorIndexes,
                    )
                    assertDetections(case, error.detections)
                }
                else -> assertDetections(
                    case,
                    YoloPostProcessor.decode(
                        output,
                        intArrayOf(1, 56, 8400),
                        geometry,
                        YoloGoldenFixture.settings(case),
                    ),
                )
            }
        }
    }

    @Test
    fun `non finite tensor values fail closed`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val output = FloatArray(56 * 8400)
            output[0] = bad
            assertThrows(YoloDecodeException::class.java) {
                YoloPostProcessor.decode(
                    output,
                    intArrayOf(1, 56, 8400),
                    LetterboxGeometry.forSource(640, 640),
                    YoloGoldenFixture.settings(),
                )
            }
        }
    }

    @Test
    fun `otherwise viable clipped candidates are reported without losing complete detections`() {
        val output = FloatArray(56 * 8400)
        fun candidate(anchor: Int, centerX: Float, score: Float) {
            output[anchor] = centerX
            output[8400 + anchor] = .5f
            output[2 * 8400 + anchor] = .2f
            output[3 * 8400 + anchor] = .3f
            output[4 * 8400 + anchor] = score
        }
        candidate(anchor = 0, centerX = .5f, score = .9f)
        candidate(anchor = 7, centerX = .05f, score = .8f)

        val failure = assertThrows(IncompleteYoloDetections::class.java) {
            YoloPostProcessor.decode(
                output,
                intArrayOf(1, 56, 8400),
                LetterboxGeometry.forSource(640, 640),
                YoloGoldenFixture.settings(),
            )
        }

        assertEquals(listOf(7), failure.anchorIndexes)
        assertEquals(listOf(0), failure.detections.map { it.anchorIndex })
    }

    @Test
    fun `nms suppresses weaker clipped duplicate before incomplete classification`() {
        val output = FloatArray(56 * 8400)
        fun candidate(anchor: Int, centerX: Float, score: Float) {
            output[anchor] = centerX
            output[8400 + anchor] = .5f
            output[2 * 8400 + anchor] = .2f
            output[3 * 8400 + anchor] = .25f
            output[4 * 8400 + anchor] = score
        }
        candidate(anchor = 0, centerX = .1f, score = .9f)
        candidate(anchor = 1, centerX = .09f, score = .8f)

        val detections = YoloPostProcessor.decode(
            output,
            intArrayOf(1, 56, 8400),
            LetterboxGeometry.forSource(640, 640),
            YoloGoldenFixture.settings(),
        )

        assertEquals(listOf(0), detections.map { it.anchorIndex })
    }

    @Test
    fun `surviving clipped candidate invalidates capture after nms`() {
        val output = FloatArray(56 * 8400)
        fun candidate(anchor: Int, centerX: Float, score: Float) {
            output[anchor] = centerX
            output[8400 + anchor] = .5f
            output[2 * 8400 + anchor] = .2f
            output[3 * 8400 + anchor] = .25f
            output[4 * 8400 + anchor] = score
        }
        candidate(anchor = 0, centerX = .1f, score = .8f)
        candidate(anchor = 1, centerX = .09f, score = .9f)

        val failure = assertThrows(IncompleteYoloDetections::class.java) {
            YoloPostProcessor.decode(
                output,
                intArrayOf(1, 56, 8400),
                LetterboxGeometry.forSource(640, 640),
                YoloGoldenFixture.settings(),
            )
        }

        assertEquals(listOf(1), failure.anchorIndexes)
        assertTrue(failure.detections.isEmpty())
    }

    @Test
    fun `low confidence edge noise is not reported as an incomplete card`() {
        val output = FloatArray(56 * 8400).apply {
            this[0] = .05f
            this[8400] = .5f
            this[2 * 8400] = .2f
            this[3 * 8400] = .3f
            this[4 * 8400] = .2f
        }

        val detections = YoloPostProcessor.decode(
            output,
            intArrayOf(1, 56, 8400),
            LetterboxGeometry.forSource(640, 640),
            YoloGoldenFixture.settings(),
        )

        assertTrue(detections.isEmpty())
    }

    @Test
    fun `malformed shape scores extents geometry and settings fail closed`() {
        val validGeometry = LetterboxGeometry.forSource(640, 640)
        val settings = YoloGoldenFixture.settings()
        fun decode(output: FloatArray, shape: IntArray = intArrayOf(1, 56, 8400), geometry: LetterboxGeometry = validGeometry) =
            YoloPostProcessor.decode(output, shape, geometry, settings)

        assertThrows(YoloDecodeException::class.java) { decode(FloatArray(56 * 8400), intArrayOf(56, 8400)) }
        assertThrows(YoloDecodeException::class.java) { decode(FloatArray(56 * 8399), intArrayOf(1, 56, 8399)) }
        assertThrows(YoloDecodeException::class.java) { decode(FloatArray(56 * 8400).apply { this[4 * 8400] = 1.01f }) }
        assertThrows(YoloDecodeException::class.java) { decode(FloatArray(56 * 8400).apply { this[2 * 8400] = -0.01f }) }
        assertThrows(YoloDecodeException::class.java) {
            decode(
                FloatArray(56 * 8400),
                geometry = validGeometry.copy(scale = Double.NaN),
            )
        }
        assertThrows(IllegalArgumentException::class.java) { YoloPostprocessSettings(Double.NaN, 0.5, 10) }
        assertThrows(IllegalArgumentException::class.java) { YoloPostprocessSettings(0.5, Double.POSITIVE_INFINITY, 10) }
        assertThrows(IllegalArgumentException::class.java) { YoloPostprocessSettings(0.5, 0.5, 0) }
    }

    private fun assertDetections(case: JSONObject, actual: List<RawYoloDetection>) {
        val expected = case.getJSONArray("expected")
        val tolerance = YoloGoldenFixture.root.getDouble("normalizedBoxTolerance").toFloat()
        assertEquals(case.getString("id"), expected.length(), actual.size)
        for (index in actual.indices) {
            val want = expected.getJSONObject(index)
            val got = actual[index]
            assertEquals(case.getString("id"), want.getInt("anchorIndex"), got.anchorIndex)
            assertEquals(case.getString("id"), want.getInt("classIndex"), got.classIndex)
            assertEquals(case.getString("id"), want.getString("label"), got.label)
            assertEquals(case.getString("id"), want.getDouble("confidence").toFloat(), got.confidence, 1e-6f)
            val box = want.getJSONArray("normalizedBox")
            assertEquals(case.getString("id"), box.getDouble(0).toFloat(), got.left, tolerance)
            assertEquals(case.getString("id"), box.getDouble(1).toFloat(), got.top, tolerance)
            assertEquals(case.getString("id"), box.getDouble(2).toFloat(), got.right, tolerance)
            assertEquals(case.getString("id"), box.getDouble(3).toFloat(), got.bottom, tolerance)
        }
    }

    private fun org.json.JSONArray.toIntList() = (0 until length()).map(::getInt)
    private fun org.json.JSONArray.toStringList() = (0 until length()).map(::getString)
}
