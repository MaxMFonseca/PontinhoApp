package com.maxmfonseca.pontinho.recognition

import com.maxmfonseca.pontinho.domain.recognition.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecognitionAssetVerifierTest {
    @Test fun `release requires exact integer NMS and threshold representation`() {
        reject(BundleFixture.json().apply { remove("nmsPolicy") })
        for ((field, value) in listOf("version" to 2, "comparison" to "greater-or-equal", "thresholdRepresentation" to "float-iou", "scoreRepresentation" to "binary64")) {
            reject(BundleFixture.json().apply { getJSONObject("nmsPolicy").put(field, value) })
        }
        reject(BundleFixture.json().apply { getJSONObject("nmsPolicy").put("grid", JSONArray(listOf(1000, 500))) })
        reject(BundleFixture.json().apply { getJSONObject("nmsPolicy").put("extra", 1) })
    }
    @Test fun `release without exact resize algorithm and parameters never becomes available`() {
        reject(BundleFixture.json().apply { remove("resizePolicy") })
        for ((field, value) in listOf("version" to 2, "coordinateTransform" to "align-corners", "rounding" to "floor", "antialias" to true, "alpha" to "ignore")) {
            reject(BundleFixture.json().apply { getJSONObject("resizePolicy").put(field, value) })
        }
        reject(BundleFixture.json().apply { getJSONObject("resizePolicy").put("classifierSize", JSONArray(listOf(320, 320))) })
        reject(BundleFixture.json().apply { getJSONObject("resizePolicy").put("unknownParameter", 1) })
    }
    @Test fun `release requires current native border policy evidence`() {
        assertEquals("mediapipe-integer-interior-1px-v1", BundleFixture.json().getString("boxPolicy"))
        reject(BundleFixture.json().apply { remove("boxPolicy") })
        reject(BundleFixture.json().apply { put("boxPolicy", "allow-clipped-v0") })
    }
    @Test fun `actual Model Maker unsigned contract remains executable with nonzero zero point`() {
        val release = int8().apply {
            val detector = getJSONObject("detector")
            detector.getJSONObject("normalization").put("mean", JSONArray(listOf(127, 127, 127)))
                .put("std", JSONArray(listOf(128, 128, 128)))
            detector.getJSONObject("quantization").getJSONObject("input")
                .put("scales", JSONArray(listOf(1.0 / 128))).put("zeroPoints", JSONArray(listOf(127)))
        }
        assertTrue(verify(release) is RecognitionAvailability.Available)
    }
    @Test fun `MediaPipe rejects signed or non equivalent input quantization before availability`() {
        for (kind in listOf("detector", "classifier")) {
            reject(int8().apply {
                getJSONObject(kind).getJSONObject("input").put("dtype", "int8")
            })
            for ((scale, zero) in listOf(.5 to 128, (1.0 / 128) to 0, (1.0 / 255) to 1)) {
                reject(int8().apply {
                    val model = getJSONObject(kind)
                    if (kind == "classifier") model.getJSONObject("input").put("quantization", JSONArray(listOf(scale, zero)))
                    else model.getJSONObject("quantization").getJSONObject("input")
                        .put("scales", JSONArray(listOf(scale))).put("zeroPoints", JSONArray(listOf(zero)))
                })
            }
        }
        reject(int8().apply { getJSONObject("classifier").getJSONArray("outputs").getJSONObject(0).put("dtype", "int8") })
        reject(BundleFixture.json().apply {
            getJSONObject("detector").getJSONObject("normalization").put("mean", JSONArray(listOf(0, 1, 0)))
        })
    }
    @Test fun `runtime receives exact classifier output quantization step`() {
        val metadata = (verify(int8()) as RecognitionAvailability.Available).metadata as RecognitionModelBundleMetadata
        assertEquals(1.0 / 256, metadata.classifier.outputQuantizationScale, 0.0)
        val floating = (verify() as RecognitionAvailability.Available).metadata as RecognitionModelBundleMetadata
        assertEquals(0.0, floating.classifier.outputQuantizationScale, 0.0)
    }
    private fun verify(json: JSONObject = BundleFixture.json()) =
        RecognitionAssetVerifier().verify(BundleFixture.detector, BundleFixture.classifier, json.toString().encodeToByteArray())
    private fun reject(json: JSONObject) = assertTrue(json.toString(), verify(json) is RecognitionAvailability.Unavailable)

    @Test fun `complete release exposes both models and selected thresholds`() {
        val metadata = (verify() as RecognitionAvailability.Available).metadata as RecognitionModelBundleMetadata
        assertEquals(BundleFixture.detectorHash, metadata.detector.modelSha256)
        assertEquals(BundleFixture.classifierHash, metadata.classifier.modelSha256)
        assertEquals(320, metadata.detector.inputWidth)
        assertEquals(224, metadata.classifier.inputWidth)
        assertEquals(BundleFixture.labels, metadata.labels)
        assertEquals(.5f, metadata.detectorThreshold)
        assertEquals(.6f, metadata.classifierThreshold)
        assertEquals(.4f, metadata.iouThreshold)
        assertEquals(.1f, metadata.cropMargin)
        assertEquals(10, metadata.maxCards)
        assertEquals(1f, metadata.classifier.calibrationTemperature)
    }

    @Test fun `missing empty or mixed assets never become available`() {
        for (assets in listOf(Triple(null, BundleFixture.classifier, BundleFixture.bytes()),
            Triple(BundleFixture.detector, null, BundleFixture.bytes()), Triple(BundleFixture.detector, BundleFixture.classifier, null),
            Triple(byteArrayOf(), BundleFixture.classifier, BundleFixture.bytes()),
            Triple(BundleFixture.classifier, BundleFixture.detector, BundleFixture.bytes()))) {
            assertTrue(RecognitionAssetVerifier().verify(assets.first, assets.second, assets.third) is RecognitionAvailability.Unavailable)
        }
        for (kind in listOf("detector", "classifier")) {
            reject(BundleFixture.json().apply { getJSONObject(kind).put("modelSha256", "0".repeat(64)) })
            reject(BundleFixture.json().apply { getJSONObject(kind).put("modelSizeBytes", 4) })
            reject(BundleFixture.json().apply { getJSONObject(kind).put("modelSizeBytes", "3") })
            reject(BundleFixture.json().apply { getJSONObject("bindings").getJSONObject(kind).getJSONObject("modelHashes").put("float16", "0".repeat(64)) })
        }
    }

    @Test fun `every required field and nested field is strict`() {
        val paths = listOf("", "detector", "classifier", "thresholds", "bindings", "bindings.detector",
            "bindings.detector.modelHashes", "benchmark", "benchmark.byCardCount.10",
            "metrics", "metrics.byCardCount.10", "metrics.byDeck.deck-1",
            "detector.input", "classifier.input", "detector.quantization", "classifier.quantization",
            "classifier.calibration", "classifier.normalization")
        fun JSONObject.at(path: String): JSONObject = if (path.isEmpty()) this else path.split('.').fold(this) { obj, field -> obj.getJSONObject(field) }
        for (path in paths) {
            val keys = BundleFixture.json().at(path).keys().asSequence().toList()
            keys.forEach { field -> reject(BundleFixture.json().apply { at(path).remove(field) }) }
            reject(BundleFixture.json().apply { at(path).put("unexpected", true) })
        }
    }

    @Test fun `malformed duplicate trailing coercible and excessive JSON fails closed`() {
        val valid = BundleFixture.bytes().decodeToString()
        for (text in listOf("{", "$valid true", valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            valid.replace("\"releaseApproved\":true", "\"releaseApproved\":\"true\""),
            valid.replace("\"detector_score\":0.5", "\"detector_score\":NaN"), " ".repeat(128 * 1024) + valid)) {
            assertTrue(RecognitionAssetVerifier().verify(BundleFixture.detector, BundleFixture.classifier, text.encodeToByteArray()) is RecognitionAvailability.Unavailable)
        }
    }

    @Test fun `labels dimensions count calibration and threshold bounds are enforced`() {
        reject(BundleFixture.json().put("schemaVersion", 2))
        reject(BundleFixture.json().put("kind", "CARD_DETECTOR_RELEASE"))
        reject(BundleFixture.json().put("confidenceCombination", "max(detector,calibratedClassifier)"))
        reject(BundleFixture.json().put("spatialOrder", "rank"))
        reject(BundleFixture.json().put("maximumCardCount", 11))
        reject(BundleFixture.json().put("releaseApproved", false))
        reject(BundleFixture.json().put("labels", JSONArray(BundleFixture.labels.reversed())))
        reject(BundleFixture.json().apply { getJSONObject("detector").put("labels", JSONArray(listOf("AC"))) })
        reject(BundleFixture.json().apply { getJSONObject("classifier").put("labels", JSONArray(BundleFixture.labels.reversed())) })
        for (field in listOf("detector_score", "combined_score", "iou", "crop_margin")) {
            for (bad in listOf(-.01, 1.01, "0.5", true, JSONObject.NULL)) reject(BundleFixture.json().apply { getJSONObject("thresholds").put(field, bad) })
        }
        reject(BundleFixture.json().apply { getJSONObject("thresholds").put("crop_margin", .25001) })
        reject(BundleFixture.json().apply { getJSONObject("thresholds").put("detector_score", 0) })
        reject(BundleFixture.json().apply { getJSONObject("classifier").getJSONObject("input").put("shape", JSONArray(listOf(1, 320, 320, 3))) })
        reject(BundleFixture.json().apply { getJSONObject("classifier").getJSONObject("calibration").put("temperature", 0) })
        reject(BundleFixture.json().apply { getJSONObject("classifier").getJSONObject("calibration").put("partition", "test") })
    }

    @Test fun `all global and supported count quality floors and benchmark ceilings fail closed`() {
        val failures = mapOf("detectorRecall" to .969999999, "detectorFalsePositiveShare" to .010000001,
            "exactCardMultisetAccuracy" to .899999999, "exactHandTotalAccuracy" to .949999999)
        for ((field, bad) in failures) {
            reject(BundleFixture.json().apply { getJSONObject("metrics").put(field, bad) })
            for (count in 1..10) reject(BundleFixture.json().apply { getJSONObject("metrics").getJSONObject("byCardCount").getJSONObject("$count").put(field, bad) })
        }
        for ((field, bad) in mapOf("sceneCount" to 199, "uniquePhotoCount" to 199, "unseenDeckCount" to 9)) {
            reject(BundleFixture.json().apply { getJSONObject("metrics").put(field, bad) })
        }
        for (count in 1..10) {
            reject(BundleFixture.json().apply { getJSONObject("metrics").getJSONObject("byCardCount").remove("$count") })
            reject(BundleFixture.json().apply { getJSONObject("benchmark").getJSONObject("byCardCount").getJSONObject("$count").put("medianMs", 2000.00001) })
        }
        reject(BundleFixture.json().apply { getJSONObject("benchmark").put("medianEndToEndMs", 2000.00001) })
    }

    @Test fun `legacy single detector never satisfies two model contract`() {
        val legacy = checkNotNull(javaClass.classLoader!!.getResourceAsStream("card_detector.int8.release.json")).use { it.readBytes() }
        assertTrue(RecognitionAssetVerifier().verify(BundleFixture.detector, BundleFixture.classifier, legacy) is RecognitionAvailability.Unavailable)
    }

    private fun int8() = BundleFixture.json().apply {
            for (kind in listOf("detector", "classifier")) {
                val model = getJSONObject(kind)
                model.put("variant", "int8")
                val q = model.getJSONObject("quantization").put("variant", "int8")
                    .put("nonemptyConstantTensorTypes", JSONArray(listOf("INT32", "INT8")))
                    .put("quantizedConstantCount", 2)
                model.getJSONObject("input").put("dtype", "uint8")
                if (kind == "detector") q.put("input", JSONObject().put("scales", JSONArray(listOf(1.0 / 255)))
                    .put("zeroPoints", JSONArray(listOf(0))).put("quantizedDimension", 0))
                else {
                    model.getJSONObject("input").put("quantization", JSONArray(listOf(1.0 / 255, 0)))
                    model.getJSONArray("outputs").getJSONObject(0).put("dtype", "uint8").put("quantization", JSONArray(listOf(.00390625, 0)))
                }
                getJSONObject("bindings").getJSONObject(kind).getJSONObject("modelHashes")
                    .put("int8", model.getString("modelSha256"))
            }
        }
    @Test fun `integer variants preserve quantization and reject corrupt tensor contracts`() {
        assertTrue(verify(int8()) is RecognitionAvailability.Available)
        reject(int8().apply { getJSONObject("detector").getJSONObject("quantization").getJSONObject("input").put("zeroPoints", JSONArray(listOf(-1))) })
        reject(int8().apply { getJSONObject("classifier").getJSONObject("input").put("quantization", JSONArray(listOf(0, 0))) })
        reject(int8().apply { getJSONObject("classifier").getJSONArray("outputs").getJSONObject(0).put("quantization", JSONArray(listOf(.1, 256))) })
        reject(int8().apply { getJSONObject("detector").getJSONArray("outputs").getJSONObject(0).put("semantic", "classes") })
        reject(int8().apply { getJSONObject("detector").getJSONObject("quantization").put("quantizedConstantCount", 0) })
        reject(int8().apply { getJSONObject("classifier").getJSONObject("normalization").put("std", JSONArray(listOf(1, 1, 1))) })
    }

    @Test fun `detector per tensor quantization accepts every dimension within input rank`() {
        for (dimension in 0..3) {
            val release = int8().apply {
                getJSONObject("detector").getJSONObject("quantization").getJSONObject("input")
                    .put("quantizedDimension", dimension)
            }
            assertTrue("Valid input dimension $dimension", verify(release) is RecognitionAvailability.Available)
        }
    }

    @Test fun `detector quantized dimension rejects outside rank and non integer types`() {
        for (dimension in listOf(-1, 4, Int.MAX_VALUE, 1.5, "1", true, JSONObject.NULL)) {
            reject(int8().apply {
                getJSONObject("detector").getJSONObject("quantization").getJSONObject("input")
                    .put("quantizedDimension", dimension)
            })
        }
    }

    @Test fun `nonzero detector dimension still requires one scale and one zero point`() {
        for ((scales, zeros) in listOf(emptyList<Double>() to listOf(128), listOf(.5) to emptyList<Int>(),
            listOf(.5, .5, .5) to listOf(128, 128, 128), listOf(.5) to listOf(128, 128))) {
            reject(int8().apply {
                getJSONObject("detector").getJSONObject("quantization").getJSONObject("input")
                    .put("quantizedDimension", 3).put("scales", JSONArray(scales)).put("zeroPoints", JSONArray(zeros))
            })
        }
    }

    @Test fun `classifier schema preserves scalar quantization without a dimension field`() {
        assertTrue(verify(int8()) is RecognitionAvailability.Available)
        for (tensor in listOf("input", "output")) {
            reject(int8().apply {
                val classifier = getJSONObject("classifier")
                val detail = if (tensor == "input") classifier.getJSONObject("input")
                    else classifier.getJSONArray("outputs").getJSONObject(0)
                detail.put("quantizedDimension", 1)
            })
        }
    }

    @Test fun `approved accuracy latency and margin boundaries are inclusive`() {
        val json = BundleFixture.json()
        val metrics = json.getJSONObject("metrics")
        (listOf(metrics) + (1..10).map { metrics.getJSONObject("byCardCount").getJSONObject("$it") }).forEach {
            it.put("exactCardMultisetAccuracy", .9).put("exactHandTotalAccuracy", .95)
        }
        json.getJSONObject("thresholds").put("crop_margin", .25)
        json.getJSONObject("benchmark").put("medianEndToEndMs", 2000).put("p95EndToEndMs", 2200)
        (1..10).forEach { json.getJSONObject("benchmark").getJSONObject("byCardCount").getJSONObject("$it").put("medianMs", 2000).put("p95Ms", 2200) }
        assertTrue(verify(json) is RecognitionAvailability.Available)
    }
}
