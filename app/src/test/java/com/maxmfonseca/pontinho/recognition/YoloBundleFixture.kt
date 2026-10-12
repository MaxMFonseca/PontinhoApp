package com.maxmfonseca.pontinho.recognition

import org.json.JSONArray
import org.json.JSONObject

internal object YoloBundleFixture {
    val model: ByteArray = "abc".encodeToByteArray()
    const val modelHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    val labels = listOf(
        "10C", "10D", "10H", "10S", "2C", "2D", "2H", "2S", "3C", "3D", "3H", "3S",
        "4C", "4D", "4H", "4S", "5C", "5D", "5H", "5S", "6C", "6D", "6H", "6S",
        "7C", "7D", "7H", "7S", "8C", "8D", "8H", "8S", "9C", "9D", "9H", "9S",
        "AC", "AD", "AH", "AS", "JC", "JD", "JH", "JS", "KC", "KD", "KH", "KS",
        "QC", "QD", "QH", "QS",
    )

    fun json(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("candidateStatus", "DEBUG_ONLY")
        put("productionApproved", false)
        put("model", JSONObject().apply {
            put("file", "card_detector.tflite")
            put("sha256", modelHash)
            put("sizeBytes", model.size)
            put("quantization", "int8")
            put("sourceCheckpointSha256", "a".repeat(64))
            put("internalQuantization", JSONObject().apply {
                put("int8TensorCount", 48)
                put("weightedComputeOperatorCount", 24)
                put("quantizedComputeOperatorCount", 24)
                put("quantizedComputeCoverage", 1.0)
                put("operatorCodes", JSONArray(listOf("CONV_2D", "DEPTHWISE_CONV_2D")))
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
        put("labels", JSONArray(labels))
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
    }

    fun bytes(): ByteArray = json().toString().encodeToByteArray()
}
