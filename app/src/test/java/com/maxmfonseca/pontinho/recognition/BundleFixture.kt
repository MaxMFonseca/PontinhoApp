package com.maxmfonseca.pontinho.recognition

import org.json.JSONArray
import org.json.JSONObject

/** Synthetic contract-only evidence. Never packaged as model/release assets. */
internal object BundleFixture {
    val detector = "abc".encodeToByteArray()
    val classifier = "def".encodeToByteArray()
    const val detectorHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    const val classifierHash = "cb8379ac2098aa165029e3938a51da0bcecfc008fd6795f401178647f96c5b34"
    val labels = listOf("C", "D", "H", "S").flatMap { s ->
        listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K").map { "$it$s" }
    }
    fun json(): JSONObject {
        fun tensor(shape: List<Int>, classifier: Boolean) = JSONObject().apply {
            put("name", "input"); put("shape", JSONArray(shape)); put("shapeSignature", JSONArray(shape))
            put("dtype", "float32")
            if (classifier) put("quantization", JSONArray(listOf(0, 0)))
        }
        fun model(classifier: Boolean) = JSONObject().apply {
            put("filename", if (classifier) "card_classifier.tflite" else "card_detector.tflite")
            put("modelSha256", if (classifier) classifierHash else detectorHash)
            put("modelSizeBytes", 3); put("variant", "float16")
            put("labels", JSONArray(if (classifier) labels else listOf("card")))
            val size = if (classifier) 224 else 320
            put("input", tensor(listOf(1, size, size, 3), classifier))
            put("outputs", JSONArray().apply {
                if (classifier) put(tensor(listOf(1, 52), true).put("name", "scores").put("semantic", "class_probabilities"))
                else listOf("boxes", "classes", "scores", "count").forEachIndexed { i, semantic ->
                    val shape = when (i) { 0 -> listOf(1, 25, 4); 3 -> listOf(1); else -> listOf(1, 25) }
                    put(JSONObject().put("semantic", semantic).put("tensorName", semantic)
                        .put("metadataName", listOf("location", "category", "score", "number of detections")[i])
                        .put("shape", JSONArray(shape)).put("shapeSignature", JSONArray(shape)).put("dtype", "float32"))
                }
            })
            put("customOps", JSONArray(if (classifier) emptyList<String>() else listOf("TFLite_Detection_PostProcess")))
            put("normalization", JSONObject().put("mean", JSONArray(listOf(0, 0, 0))).put("std", JSONArray(listOf(255, 255, 255))))
            put("quantization", JSONObject().put("variant", "float16")
                .put("nonemptyConstantTensorTypes", JSONArray(listOf("FLOAT16", "INT32")))
                .put("quantizedConstantCount", 0).apply { if (!classifier) put("input", JSONObject.NULL) })
            put("interpreterInvocation", "passed")
            if (classifier) put("calibration", JSONObject().put("method", "temperature-scaling")
                .put("partition", "validation").put("temperature", 1).put("nllBefore", .2).put("nllAfter", .1)
                .put("sampleCount", 200).put("scoreTransform", "softmax(log(max(raw_probability,1e-12))/temperature)")
                .put("evaluation", "in-sample-validation-not-holdout-quality"))
        }
        fun metrics(count: Int, cards: Int) = JSONObject().apply {
            put("sceneCount", count); put("supportedSceneCount", count)
            put("detectorTp", cards); put("detectorFp", 0); put("detectorFn", 0)
            put("falsePositives", 0); put("falseNegatives", 0)
            listOf("detectorRecall", "detectorPrecision", "perCardRecall", "perCardPrecision", "exactCardMultisetAccuracy", "exactHandTotalAccuracy").forEach { put(it, 1) }
            put("detectorFalsePositiveShare", 0); put("abstentionRate", 0)
        }
        return JSONObject().apply {
            put("schemaVersion", 1); put("kind", "TWO_STAGE_CARD_RECOGNITION_RELEASE"); put("releaseApproved", true)
            put("labels", JSONArray(labels)); put("maximumCardCount", 10)
            put("confidenceCombination", "min(detector,calibratedClassifier)"); put("spatialOrder", "top-left-bottom-right")
            put("boxPolicy", "mediapipe-integer-interior-1px-v1")
            put("nmsPolicy", JSONObject().put("version", 1).put("grid", JSONArray(listOf(320, 320)))
                .put("thresholdRepresentation", "binary64-to-binary32-exact-rational")
                .put("scoreRepresentation", "binary32").put("comparison", "strict-greater")
                .put("priority", "score-desc-left-top-right-bottom-index")
                .put("spatialOrder", "top-left-bottom-right-index").put("invalidBox", "reject"))
            put("resizePolicy", JSONObject().put("version", 1).put("algorithm", "point-bilinear")
                .put("coordinateTransform", "half-pixel").put("edgeMode", "clamp").put("rounding", "half-up-once")
                .put("channels", "RGB8").put("alpha", "opaque-only").put("antialias", false)
                .put("detectorSize", JSONArray(listOf(320, 320))).put("classifierSize", JSONArray(listOf(224, 224))))
            put("thresholds", JSONObject().put("detector_score", .5).put("combined_score", .6).put("iou", .4).put("crop_margin", .1))
            put("detector", model(false)); put("classifier", model(true))
            listOf("holdoutPartitionSha256", "holdoutIdentity", "holdoutManifestSha256", "selectionSha256", "sourceManifestSha256", "sourceAuditReceiptSha256", "normalizedDatasetSha256", "sourceReportSha256", "evaluationReceiptSha256").forEach { put(it, "a".repeat(64)) }
            put("bindings", JSONObject().apply {
                listOf("detector", "classifier").forEach { kind -> put(kind, JSONObject().put("contractSha256", "b".repeat(64))
                    .put("modelHashes", JSONObject().put("float16", if (kind == "detector") detectorHash else classifierHash).put("int8", "c".repeat(64)))) }
            })
            put("metrics", metrics(200, 1100).put("uniquePhotoCount", 200).put("unseenDeckCount", 10)
                .put("byCardCount", JSONObject().apply { (1..10).forEach { put("$it", metrics(20, 20 * it)) } })
                .put("byDeck", JSONObject().apply { (1..10).forEach { put("deck-$it", metrics(20, 110)) } }))
            put("benchmark", JSONObject().put("sha256", "d".repeat(64)).put("device", "fixture device")
                .put("androidVersion", "15").put("coldStartMs", 1500).put("peakMemoryBytes", 100000000)
                .put("medianEndToEndMs", 1200).put("p95EndToEndMs", 1400)
                .put("byCardCount", JSONObject().apply { (1..10).forEach { put("$it", JSONObject().put("medianMs", 1200).put("p95Ms", 1400)) } }))
        }
    }
    fun bytes() = json().toString().encodeToByteArray()

    fun legacyMetadata() = com.maxmfonseca.pontinho.domain.recognition.RecognitionModelBundleMetadata(
        version = "a".repeat(64),
        detector = com.maxmfonseca.pontinho.domain.recognition.RecognitionStageMetadata(
            modelSha256 = detectorHash,
            modelSizeBytes = detector.size.toLong(),
            labels = listOf("card"),
            inputWidth = 320,
            inputHeight = 320,
            quantizationVariant = "float16",
        ),
        classifier = com.maxmfonseca.pontinho.domain.recognition.RecognitionStageMetadata(
            modelSha256 = classifierHash,
            modelSizeBytes = classifier.size.toLong(),
            labels = labels,
            inputWidth = 224,
            inputHeight = 224,
            quantizationVariant = "float16",
        ),
        labels = labels,
        detectorThreshold = .5f,
        classifierThreshold = .6f,
        iouThreshold = .4f,
        cropMargin = .1f,
        maxCards = 10,
    )
}
