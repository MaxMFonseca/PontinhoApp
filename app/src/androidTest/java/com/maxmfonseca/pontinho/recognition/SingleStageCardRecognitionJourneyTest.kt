package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.YoloRecognitionMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingleStageCardRecognitionJourneyTest {
    @Test
    fun shippedCandidateMatchesLicensedValidationFixture() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = instrumentation.targetContext.assets.open("card_detector.tflite").use { it.readBytes() }
        val release = instrumentation.targetContext.assets.open("card_recognition.release.json").use { it.readBytes() }
        val fixture = instrumentation.context.assets
            .open("recognition/task10-playing-cards-validation.json")
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        val bitmap = instrumentation.context.assets
            .open("recognition/task10-playing-cards-validation.jpg")
            .use(BitmapFactory::decodeStream)
        val available = RecognitionAssetVerifier(allowExperimental = true).verify(model, release)
        assertTrue("The shipped debug pair must pass strict verification", available is RecognitionAvailability.Available)
        val metadata = (available as RecognitionAvailability.Available).metadata as YoloRecognitionMetadata
        assertEquals("public-domain validation image; runtime parity only", fixture.getString("evidenceScope"))
        assertEquals("Public Domain", fixture.getString("license"))
        assertEquals(metadata.modelSha256, fixture.getString("candidateSha256"))

        val engine = SingleStageCardRecognitionEngineFactory().create(model, metadata)
        try {
            val result = engine.recognize(requireNotNull(bitmap))
            val expected = fixture.getJSONArray("detections")
            assertEquals(expected.length(), result.candidates.size)
            val tolerance = fixture.getDouble("normalizedBoxTolerance")
            repeat(expected.length()) { index ->
                val detection = expected.getJSONObject(index)
                val bounds = detection.getJSONArray("bounds")
                val actual = result.candidates[index]
                assertEquals(detection.getString("label"), actual.card.asYoloLabel())
                assertEquals(detection.getDouble("confidence"), actual.confidence.toDouble(), tolerance)
                assertEquals(bounds.getDouble(0), actual.bounds.left.toDouble(), tolerance)
                assertEquals(bounds.getDouble(1), actual.bounds.top.toDouble(), tolerance)
                assertEquals(bounds.getDouble(2), actual.bounds.right.toDouble(), tolerance)
                assertEquals(bounds.getDouble(3), actual.bounds.bottom.toDouble(), tolerance)
            }
        } finally {
            bitmap?.recycle()
            engine.close()
        }
    }

    @Test
    fun verifiedPairRunsThroughSingleStageProviderAndFactory() = runBlocking {
        var closes = 0
        val provider = VerifiedCardRecognitionEngineProvider(
            readAssets = { MODEL to releaseBytes() },
            factory = SingleStageCardRecognitionEngineFactory(
                runnerFactory = { _, _ ->
                    object : YoloRunner {
                        override fun run(input: PreparedYoloImage): YoloTensorOutput {
                            val values = FloatArray(56 * 8400)
                            values[0] = .5f
                            values[8400] = .5f
                            values[2 * 8400] = .2f
                            values[3 * 8400] = .3f
                            values[(4 + 37) * 8400] = .9f
                            return YoloTensorOutput(values, intArrayOf(1, 56, 8400))
                        }

                        override fun close() { closes++ }
                    }
                },
                dispatcher = Dispatchers.Unconfined,
            ),
            dispatcher = Dispatchers.Unconfined,
        )
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val engine = provider.get()
            val result = engine.recognize(bitmap)

            assertEquals(RecognitionAvailability.Available(metadata()), engine.availability)
            assertEquals(listOf(Card.Suited(Rank.ACE, Suit.DIAMONDS)), result.candidates.map { it.card })
        } finally {
            bitmap.recycle()
            provider.close()
        }
        assertEquals(1, closes)
    }

    @Test
    fun bundledLiteRtReachesModelValidationWithoutLinkageFailure() {
        val failure = runCatching {
            SingleStageCardRecognitionEngineFactory().create(byteArrayOf(1, 2, 3), metadata()).close()
        }.exceptionOrNull()

        assertNotNull("Invalid TFLite bytes must fail model loading", failure)
        assertFalse("Bundled LiteRT native libraries must link", failure is LinkageError)
    }

    private fun metadata() = (RecognitionAssetVerifier(allowExperimental = true)
        .verify(MODEL, releaseBytes()) as RecognitionAvailability.Available)
        .metadata as YoloRecognitionMetadata

    private fun releaseBytes() = JSONObject().apply {
        put("schemaVersion", 1)
        put("candidateStatus", "DEBUG_ONLY")
        put("productionApproved", false)
        put("model", JSONObject().apply {
            put("file", "card_detector.tflite")
            put("sha256", MODEL_HASH)
            put("sizeBytes", MODEL.size)
            put("quantization", "fp32")
            put("sourceCheckpointSha256", "a".repeat(64))
            put("internalQuantization", JSONObject().apply {
                put("int8TensorCount", 0)
                put("weightedComputeOperatorCount", 1)
                put("quantizedComputeOperatorCount", 0)
                put("quantizedComputeCoverage", 0.0)
                put("operatorCodes", JSONArray())
            })
        })
        put("input", JSONObject().apply {
            put("shape", JSONArray(listOf(1, 640, 640, 3)))
            put("dtype", "float32")
            put("colorSpace", "RGB")
            put("normalization", JSONObject().put("scale", 1.0 / 255.0).put("offset", 0.0))
            put("quantization", JSONObject.NULL)
            put("resizePolicy", "centered-letterbox-bilinear")
        })
        put("output", JSONObject().apply {
            put("shape", JSONArray(listOf(1, 56, 8400)))
            put("dtype", "float32")
            put("quantization", JSONObject.NULL)
            put("layout", "channels-first")
            put("raw", true)
            put("boxEncoding", "xywh")
            put("boxCoordinateUnits", "normalized-input-dimensions")
            put("boxCoordinateReference", JSONObject().put("width", 640).put("height", 640))
            put("classCount", 52)
        })
        put("labels", JSONArray(LABELS))
        put("thresholds", JSONObject().apply {
            put("confidence", .3)
            put("iou", .45)
            put("comparison", "strict-greater-than")
            put("selectedOn", "validation")
            put("selectionSha256", "b".repeat(64))
            put("sourceCheckpointSha256", "a".repeat(64))
        })
        put("nms", JSONObject().apply {
            put("classAwareFirst", true)
            put("physicalDuplicateSuppression", true)
            put("physicalDuplicateIou", .95)
            put("tieBreak", "score-desc-box-class-anchor")
            put("finalOrder", "left-top-right-bottom-class-score-anchor")
        })
        put("maxCards", 10)
        put("datasetReceiptSha256", "d".repeat(64))
        put("datasetAuditSha256", "e".repeat(64))
        put("holdoutReceiptSha256", "1".repeat(64))
        put("exportInvocationSha256", "2".repeat(64))
        put("validationEvaluationSha256", "4".repeat(64))
        put("testEvaluationSha256", "5".repeat(64))
    }.toString().encodeToByteArray()

    private fun Card.asYoloLabel(): String = when (this) {
        is Card.Suited -> buildString {
            append(
                when (rank) {
                    Rank.ACE -> "A"
                    Rank.TWO -> "2"
                    Rank.THREE -> "3"
                    Rank.FOUR -> "4"
                    Rank.FIVE -> "5"
                    Rank.SIX -> "6"
                    Rank.SEVEN -> "7"
                    Rank.EIGHT -> "8"
                    Rank.NINE -> "9"
                    Rank.TEN -> "10"
                    Rank.JACK -> "J"
                    Rank.QUEEN -> "Q"
                    Rank.KING -> "K"
                },
            )
            append(
                when (suit) {
                    Suit.CLUBS -> "C"
                    Suit.DIAMONDS -> "D"
                    Suit.HEARTS -> "H"
                    Suit.SPADES -> "S"
                },
            )
        }
    }

    private companion object {
        val MODEL = "abc".encodeToByteArray()
        const val MODEL_HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val LABELS = YoloCardLabels.values
    }
}
