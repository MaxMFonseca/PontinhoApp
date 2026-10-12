package com.maxmfonseca.pontinho.recognition

import com.maxmfonseca.pontinho.BuildConfig
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.YoloRecognitionMetadata
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecognitionAssetVerifierTest {
    @Test fun `complete single detector metadata exposes the runtime contract`() {
        val metadata = (verify(YoloBundleFixture.json(), true) as RecognitionAvailability.Available)
            .metadata as YoloRecognitionMetadata
        assertEquals(YoloBundleFixture.modelHash, metadata.modelVersion)
        assertEquals(YoloBundleFixture.modelHash, metadata.modelSha256)
        assertEquals(3, metadata.modelSizeBytes)
        assertEquals("int8", metadata.quantization)
        assertEquals(YoloBundleFixture.labels, metadata.labels)
        assertEquals(640, metadata.inputWidth)
        assertEquals(640, metadata.inputHeight)
        assertEquals(listOf(1, 56, 8400), metadata.outputShape)
        assertEquals(1.0 / 255.0, metadata.normalizationScale, 0.0)
        assertEquals(0.0, metadata.normalizationOffset, 0.0)
        assertEquals(.3, metadata.confidenceThreshold, 0.0)
        assertEquals(.45, metadata.iouThreshold, 0.0)
        assertEquals(10, metadata.maxCards)
        assertEquals("d".repeat(64), metadata.datasetReceiptSha256)
        assertEquals("e".repeat(64), metadata.datasetAuditSha256)
        assertEquals("4".repeat(64), metadata.validationEvaluationSha256)
        assertEquals("5".repeat(64), metadata.testEvaluationSha256)
        assertTrue(metadata.debugOnly)
        assertFalse(metadata.productionApproved)
    }

    @Test fun `missing empty stale or mismatched assets fail closed`() {
        val json = YoloBundleFixture.bytes()
        for ((model, release) in listOf(
            null to json, YoloBundleFixture.model to null, byteArrayOf() to json,
            YoloBundleFixture.model to byteArrayOf(), "changed".encodeToByteArray() to json,
        )) assertInvalid(model, release, true)
        reject(YoloBundleFixture.json().apply { getJSONObject("model").put("sha256", "0".repeat(64)) })
        reject(YoloBundleFixture.json().apply { getJSONObject("model").put("sizeBytes", 4) })
    }

    @Test fun `schema rejects every missing or extra field including classifier and crop fields`() {
        val paths = listOf("", "model", "model.internalQuantization", "input", "input.normalization",
            "output", "output.boxCoordinateReference", "thresholds", "nms")
        paths.forEach { path ->
            val keys = YoloBundleFixture.json().at(path).keys().asSequence().toList()
            keys.forEach { field -> reject(YoloBundleFixture.json().apply { at(path).remove(field) }) }
            reject(YoloBundleFixture.json().apply { at(path).put("unexpected", true) })
        }
        for (legacy in listOf("classifier", "cropMargin", "classifierThreshold"))
            reject(YoloBundleFixture.json().put(legacy, JSONObject()))
    }

    @Test fun `labels input output normalization and postprocessing declarations are exact`() {
        reject(YoloBundleFixture.json().put("labels", JSONArray(YoloBundleFixture.labels.reversed())))
        reject(YoloBundleFixture.json().apply { getJSONObject("input").put("shape", JSONArray(listOf(1, 320, 320, 3))) })
        reject(YoloBundleFixture.json().apply { getJSONObject("input").put("dtype", "uint8") })
        reject(YoloBundleFixture.json().apply { getJSONObject("input").put("quantization", JSONArray(listOf(.1, 0))) })
        reject(YoloBundleFixture.json().apply { getJSONObject("input").put("resizePolicy", "stretch") })
        reject(YoloBundleFixture.json().apply { getJSONObject("input").getJSONObject("normalization").put("scale", .5) })
        reject(YoloBundleFixture.json().apply { getJSONObject("input").getJSONObject("normalization").put("offset", 1) })
        reject(YoloBundleFixture.json().apply { getJSONObject("output").put("shape", JSONArray(listOf(1, 55, 8400))) })
        reject(YoloBundleFixture.json().apply { getJSONObject("output").put("raw", false) })
        reject(YoloBundleFixture.json().apply { getJSONObject("output").put("layout", "channels-last") })
        reject(YoloBundleFixture.json().apply { getJSONObject("output").put("classCount", 51) })
        reject(YoloBundleFixture.json().apply { getJSONObject("output").put("boxEncoding", "xyxy") })
        reject(YoloBundleFixture.json().apply { getJSONObject("output").put("boxCoordinateUnits", "pixels") })
        reject(YoloBundleFixture.json().apply { getJSONObject("output").getJSONObject("boxCoordinateReference").put("width", 320) })
        reject(YoloBundleFixture.json().apply { getJSONObject("nms").put("physicalDuplicateIou", .9) })
        reject(YoloBundleFixture.json().put("maxCards", 11))
    }

    @Test fun `declared model quantization is internally consistent for int8 and fp32`() {
        val fp32 = YoloBundleFixture.json().apply {
            val model = getJSONObject("model").put("quantization", "fp32")
            model.getJSONObject("internalQuantization")
                .put("int8TensorCount", 0)
                .put("quantizedComputeOperatorCount", 0)
                .put("quantizedComputeCoverage", 0)
                .put("operatorCodes", JSONArray())
        }
        assertTrue(verify(fp32, true) is RecognitionAvailability.Available)
        reject(YoloBundleFixture.json().apply {
            getJSONObject("model").getJSONObject("internalQuantization").put("quantizedComputeCoverage", .8)
        })
        reject(YoloBundleFixture.json().apply {
            getJSONObject("model").getJSONObject("internalQuantization")
                .put("quantizedComputeOperatorCount", 20).put("quantizedComputeCoverage", 1)
        })
        reject(YoloBundleFixture.json().apply {
            getJSONObject("model").getJSONObject("internalQuantization").put("operatorCodes", JSONArray(listOf("ADD")))
        })
    }

    @Test fun `thresholds hashes and numeric values reject invalid or non finite values`() {
        for (field in listOf("confidence", "iou")) for (bad in listOf(0, 1, -.1, 1.1, "0.3", true, JSONObject.NULL))
            reject(YoloBundleFixture.json().apply { getJSONObject("thresholds").put(field, bad) })
        listOf("datasetReceiptSha256", "datasetAuditSha256", "holdoutReceiptSha256", "exportInvocationSha256",
            "validationEvaluationSha256", "testEvaluationSha256").forEach { field ->
            reject(YoloBundleFixture.json().put(field, "not-a-hash"))
        }
        reject(YoloBundleFixture.json().apply { getJSONObject("thresholds").put("sourceCheckpointSha256", "f".repeat(64)) })
        val valid = YoloBundleFixture.bytes().decodeToString()
        for (text in listOf("{", "$valid true",
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replace("\"confidence\":0.3", "\"confidence\":NaN"), " ".repeat(128 * 1024) + valid))
            assertInvalid(YoloBundleFixture.model, text.encodeToByteArray(), true)
    }

    @Test fun `debug candidates require an explicit debug gate and no release status is invented`() {
        assertTrue(verify() is RecognitionAvailability.Unavailable)
        if (BuildConfig.DEBUG) assertTrue(verify(allowDebugOnly = true) is RecognitionAvailability.Available)
        reject(YoloBundleFixture.json().put("productionApproved", true))
        reject(YoloBundleFixture.json().put("candidateStatus", "PRODUCTION_APPROVED"))
        val production = YoloBundleFixture.json().put("candidateStatus", "PRODUCTION_APPROVED").put("productionApproved", true)
        reject(production)
        reject(YoloBundleFixture.json().put("candidateStatus", "EXPERIMENTAL_ONE_DECK"))
    }

    private fun verify(json: JSONObject = YoloBundleFixture.json(), allowDebugOnly: Boolean = false) =
        RecognitionAssetVerifier(allowExperimental = allowDebugOnly)
            .verify(YoloBundleFixture.model, json.toString().encodeToByteArray())
    private fun reject(json: JSONObject) = assertTrue(json.toString(), verify(json, true) is RecognitionAvailability.Unavailable)
    private fun assertInvalid(model: ByteArray?, release: ByteArray?, allowDebugOnly: Boolean) = assertTrue(
        RecognitionAssetVerifier(allowExperimental = allowDebugOnly).verify(model, release) is RecognitionAvailability.Unavailable,
    )
    private fun JSONObject.at(path: String): JSONObject =
        if (path.isEmpty()) this else path.split('.').fold(this) { current, field -> current.getJSONObject(field) }
}
