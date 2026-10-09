package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.util.JsonReader
import android.util.JsonToken
import com.maxmfonseca.pontinho.BuildConfig
import com.maxmfonseca.pontinho.domain.recognition.*
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Production gates stay strict; an explicit one-deck experiment is available only in debug. */
class RecognitionAssetVerifier internal constructor(private val allowExperimental: Boolean = false) {
    fun verify(detector: ByteArray?, classifier: ByteArray?, releaseJson: ByteArray?): RecognitionAvailability {
        if (detector == null || classifier == null || releaseJson == null ||
            detector.isEmpty() || classifier.isEmpty() || releaseJson.isEmpty()) {
            return RecognitionAvailability.Unavailable(RecognitionUnavailableReason.MISSING_ASSETS)
        }
        if (releaseJson.size > MAX_RELEASE_JSON_BYTES) return invalid()
        return try {
            val root = parseStrictJson(releaseJson).objectValue()
            val experimental = root.string("kind") == "EXPERIMENTAL_ONE_DECK"
            if (experimental) require(BuildConfig.DEBUG && allowExperimental)
            root.exact(if (experimental) EXPERIMENTAL_FIELDS else RELEASE_FIELDS)
            require(root.int("schemaVersion") == 1)
            if (!experimental) require(root.string("kind") == "TWO_STAGE_CARD_RECOGNITION_RELEASE")
            require((root.getValue("releaseApproved") as? JsonBoolean)?.value == !experimental)
            require(root.strings("labels") == CardClassIndex.labels)
            require(root.int("maximumCardCount") == 10)
            require(root.string("confidenceCombination") == "min(detector,calibratedClassifier)")
            require(root.string("spatialOrder") == "top-left-bottom-right")
            require(root.string("boxPolicy") == NATIVE_BOX_POLICY)
            resizePolicy(root.objectField("resizePolicy"))
            nmsPolicy(root.objectField("nmsPolicy"))
            (if (experimental) EXPERIMENTAL_HASH_FIELDS else HASH_FIELDS).forEach { root.hash(it) }
            val thresholds = root.objectField("thresholds")
            thresholds.exact(setOf("detector_score", "combined_score", "iou", "crop_margin"))
            val detectorThreshold = thresholds.range("detector_score", "0.000000000001", "1").toFloat()
            val classifierThreshold = thresholds.range("combined_score", "0.000000000001", "1").toFloat()
            // Same JSON-number -> binary64 -> binary32 canonicalization as Python.
            val iou = thresholds.range("iou", "0.000000000001", "1").toDouble().toFloat()
            val margin = thresholds.range("crop_margin", "0", "0.25").toFloat()
            val detectorMetadata = stage(root.objectField("detector"), detector, classifier = false)
            val classifierMetadata = stage(root.objectField("classifier"), classifier, classifier = true)
            val bindings = root.objectField("bindings")
            bindings.exact(setOf("detector", "classifier"))
            for ((name, metadata) in listOf("detector" to detectorMetadata, "classifier" to classifierMetadata)) {
                val binding = bindings.objectField(name)
                binding.exact(setOf("contractSha256", "modelHashes"))
                binding.hash("contractSha256")
                val hashes = binding.objectField("modelHashes")
                hashes.exact(setOf("float16", "int8"))
                hashes.keys.forEach { hashes.hash(it) }
                require(hashes.string(metadata.quantizationVariant) == metadata.modelSha256)
            }
            if (experimental) experimentalEvidence(root) else {
                metrics(root.objectField("metrics"))
                benchmark(root.objectField("benchmark"))
            }
            RecognitionAvailability.Available(RecognitionModelBundleMetadata(
                version = if (experimental) "experimental-" + root.string("experimentReportSha256") else root.string("sourceReportSha256"),
                detector = detectorMetadata, classifier = classifierMetadata,
                labels = CardClassIndex.labels.toList(), detectorThreshold = detectorThreshold,
                classifierThreshold = classifierThreshold, iouThreshold = iou, cropMargin = margin, maxCards = 10,
                experimental = experimental,
            ))
        } catch (_: Exception) { invalid() }
    }

    private fun invalid() = RecognitionAvailability.Unavailable(RecognitionUnavailableReason.INVALID_ASSETS)

    private fun experimentalEvidence(root: Map<String, JsonValue>) {
        val evidence = root.objectField("evidence")
        evidence.exact(setOf("trainSceneCount", "validationSceneCount", "experimentalTestSceneCount", "visualDeckCount", "deckDisjoint", "productionHoldout"))
        require(evidence.int("trainSceneCount") > 0 && evidence.int("visualDeckCount") == 1)
        for (name in listOf("deckDisjoint", "productionHoldout")) require((evidence.getValue(name) as? JsonBoolean)?.value == false)
        val measured = root.objectField("metrics")
        measured.exact(setOf("validation", "experimentalTest"))
        for ((partition, countName) in listOf("validation" to "validationSceneCount", "experimentalTest" to "experimentalTestSceneCount")) {
            val row = measured.objectField(partition)
            val rates = setOf("detectorRecall", "detectorFalsePositiveShare", "classifierTop1Accuracy", "exactCardMultisetAccuracy", "exactHandTotalAccuracy")
            row.exact(rates + "sceneCount")
            require(row.int("sceneCount") > 0 && row.int("sceneCount") == evidence.int(countName))
            rates.forEach { row.range(it, "0", "1") }
        }
    }

    private fun stage(value: Map<String, JsonValue>, bytes: ByteArray, classifier: Boolean): RecognitionStageMetadata {
        value.exact(MODEL_FIELDS + if (classifier) setOf("calibration") else emptySet())
        val kind = if (classifier) "classifier" else "detector"
        require(value.string("filename") == "card_$kind.tflite")
        val hash = value.hash("modelSha256")
        require(hash == sha256(bytes) && value.long("modelSizeBytes") == bytes.size.toLong())
        val variant = value.string("variant")
        require(variant in setOf("float16", "int8"))
        val labels = value.strings("labels")
        require(labels == if (classifier) CardClassIndex.labels else listOf("card"))
        require(value.string("interpreterInvocation") == "passed")
        require(value.strings("customOps") == if (classifier) emptyList<String>() else listOf("TFLite_Detection_PostProcess"))
        val size = if (classifier) 224 else 320
        val input = value.objectField("input")
        tensor(input, listOf(1, size, size, 3), variant, classifier)
        val outputs = value.array("outputs").map { it.objectValue() }
        if (classifier) {
            require(outputs.size == 1)
            val output = outputs.single()
            tensor(output, listOf(1, 52), variant, true, setOf("semantic"))
            require(output.string("semantic") == "class_probabilities")
        } else {
            require(outputs.size == 4)
            val max = outputs.first().ints("shape").getOrNull(1) ?: error("Missing box count")
            require(max > 0)
            val semantics = listOf("boxes", "classes", "scores", "count")
            val names = listOf("location", "category", "score", "number of detections")
            require(outputs.map { it.string("tensorName") }.distinct().size == 4)
            outputs.forEachIndexed { index, output ->
                output.exact(setOf("semantic", "tensorName", "metadataName", "shape", "shapeSignature", "dtype"))
                require(output.string("semantic") == semantics[index] && output.string("metadataName") == names[index])
                require(output.string("tensorName").isNotBlank() && output.string("dtype") == "float32")
                val shape = when (index) { 0 -> listOf(1, max, 4); 3 -> listOf(1); else -> listOf(1, max) }
                require(output.ints("shape") == shape && output.ints("shapeSignature") == shape)
            }
        }
        val normalization = value.objectField("normalization")
        normalization.exact(setOf("mean", "std"))
        val mean = normalization.array("mean").map { it.numberValue().also { n -> require(n.toDouble().isFinite()) } }
        val std = normalization.array("std").map { it.numberValue().also { n -> require(n.toDouble().isFinite() && n > BigDecimal.ZERO) } }
        require(mean.size == 3 && std.size == 3)
        if (classifier) require(mean.all { it.compareTo(BigDecimal.ZERO) == 0 } && std.all { it.compareTo(BigDecimal(255)) == 0 })
        // MediaPipe CPU image tasks accept only FLOAT32/UINT8. UINT8 ignores
        // normalization and tensor scale/zero: Python must prepare identical raw pixels.
        require(input.string("dtype") in setOf("float32", "uint8"))
        val meanFloats = mean.map(BigDecimal::toFloat)
        val stdFloats = std.map(BigDecimal::toFloat)
        require(meanFloats.all(Float::isFinite) && stdFloats.all { it.isFinite() && it >= 1.1920929e-7f })
        if (input.string("dtype") == "float32") {
            require(meanFloats.distinct().size == 1 && stdFloats.distinct().size == 1)
        } else {
            val q = if (classifier) input.array("quantization") else {
                val parameters = value.objectField("quantization").objectField("input")
                listOf(parameters.array("scales").single(), parameters.array("zeroPoints").single())
            }
            val scale = q[0].numberValue().toFloat()
            val zero = q[1].numberValue().intValueExact()
            require(scale.isFinite() && scale > 0f && zero in 0..255)
            for (channel in 0..2) for (pixel in 0..255) {
                val prepared = ((pixel.toFloat() - meanFloats[channel]) / stdFloats[channel]) / scale + zero
                require(kotlin.math.round(prepared).coerceIn(0f, 255f) == pixel.toFloat())
            }
        }
        if (classifier) require(outputs.single().string("dtype") in setOf("float32", "uint8"))
        quantization(value.objectField("quantization"), variant, classifier, input.string("dtype"), input.ints("shape").size)
        var temperature = 1f
        if (classifier) {
            val calibration = value.objectField("calibration")
            calibration.exact(setOf("method", "partition", "temperature", "nllBefore", "nllAfter", "sampleCount", "scoreTransform", "evaluation"))
            require(calibration.string("method") == "temperature-scaling" && calibration.string("partition") == "validation")
            require(calibration.string("scoreTransform") == "softmax(log(max(raw_probability,1e-12))/temperature)")
            require(calibration.string("evaluation") == "in-sample-validation-not-holdout-quality")
            temperature = calibration.range("temperature", "0.25", "4").toFloat()
            require(calibration.nonnegative("nllAfter") <= calibration.nonnegative("nllBefore"))
            require(calibration.int("sampleCount") > 0)
        }
        val outputScale = if (classifier && variant == "int8") outputs.single().array("quantization")[0].numberValue().toDouble() else 0.0
        return RecognitionStageMetadata(hash, bytes.size.toLong(), labels, size, size, variant, temperature, outputScale)
    }

    private fun tensor(value: Map<String, JsonValue>, shape: List<Int>, variant: String, quantized: Boolean, extra: Set<String> = emptySet()) {
        value.exact(setOf("name", "shape", "shapeSignature", "dtype") + extra + if (quantized) setOf("quantization") else emptySet())
        require(value.string("name").isNotBlank() && value.ints("shape") == shape && value.ints("shapeSignature") == shape)
        val dtype = value.string("dtype")
        require(if (variant == "float16") dtype == "float32" else dtype in setOf("int8", "uint8"))
        if (quantized) {
            val pair = value.array("quantization")
            require(pair.size == 2)
            val scale = pair[0].numberValue()
            val zero = pair[1].numberValue().intValueExact()
            require(scale.toDouble().isFinite())
            if (variant == "float16") require(scale.compareTo(BigDecimal.ZERO) == 0 && zero == 0)
            else require(scale > BigDecimal.ZERO && zero in if (dtype == "int8") -128..127 else 0..255)
        }
    }

    private fun quantization(value: Map<String, JsonValue>, variant: String, classifier: Boolean, dtype: String, inputRank: Int) {
        value.exact(setOf("variant", "nonemptyConstantTensorTypes", "quantizedConstantCount") + if (classifier) emptySet() else setOf("input"))
        require(value.string("variant") == variant)
        val types = value.strings("nonemptyConstantTensorTypes")
        require(types == types.distinct().sorted())
        if (variant == "float16") {
            require("FLOAT16" in types && types.all { it in if (classifier) setOf("FLOAT16", "INT32") else setOf("FLOAT16", "FLOAT32", "INT32") })
            require(value.int("quantizedConstantCount") == 0)
            if (!classifier) require(value.getValue("input") is JsonNull)
        } else {
            require(types.any { it in setOf("INT8", "UINT8") } && types.all { it in if (classifier) setOf("INT8", "UINT8", "INT32") else setOf("INT8", "UINT8", "INT32", "FLOAT32") })
            require(value.int("quantizedConstantCount") > 0)
            if (!classifier) {
                val input = value.objectField("input")
                input.exact(setOf("scales", "zeroPoints", "quantizedDimension"))
                val scales = input.array("scales")
                val zeroPoints = input.array("zeroPoints")
                require(scales.size == 1 && zeroPoints.size == 1)
                require(scales.single().numberValue().let { it.toDouble().isFinite() && it > BigDecimal.ZERO })
                require(zeroPoints.single().numberValue().intValueExact() in if (dtype == "int8") -128..127 else 0..255)
                require(input.int("quantizedDimension") in 0 until inputRank)
            }
        }
    }

    private fun metrics(value: Map<String, JsonValue>) {
        value.exact(METRIC_FIELDS + setOf("uniquePhotoCount", "unseenDeckCount", "byCardCount", "byDeck"))
        metricRow(value, gate = true)
        require(value.int("sceneCount") >= 200 && value.int("uniquePhotoCount") in 200..value.int("sceneCount"))
        require(value.int("unseenDeckCount") >= 10)
        val buckets = value.objectField("byCardCount")
        require(buckets.keys.containsAll((1..10).map(Int::toString)))
        buckets.forEach { (key, rowValue) ->
            require(key.matches(Regex("0|[1-9][0-9]*")) && key.toInt() >= 0)
            val row = rowValue.objectValue()
            row.exact(METRIC_FIELDS)
            metricRow(row, key.toInt() in 1..10)
            require(row.int("supportedSceneCount") == if (key.toInt() in 1..10) row.int("sceneCount") else 0)
        }
        val decks = value.objectField("byDeck")
        require(decks.size == value.int("unseenDeckCount") && decks.keys.none(String::isBlank))
        decks.values.forEach { it.objectValue().also { row -> row.exact(METRIC_FIELDS); metricRow(row, false) } }
        for (group in listOf(buckets, decks)) {
            for (field in COUNT_FIELDS) {
                require(group.values.sumOf { it.objectValue().long(field) } == value.long(field))
            }
        }
    }

    private fun metricRow(value: Map<String, JsonValue>, gate: Boolean) {
        COUNT_FIELDS.forEach { require(value.long(it) >= 0) }
        require(value.long("sceneCount") > 0 && value.long("supportedSceneCount") <= value.long("sceneCount"))
        RATE_FIELDS.forEach { value.range(it, "0", "1") }
        if (gate) {
            value.range("detectorRecall", "0.97", "1")
            value.range("detectorFalsePositiveShare", "0", "0.01")
            value.range("exactCardMultisetAccuracy", "0.90", "1")
            value.range("exactHandTotalAccuracy", "0.95", "1")
        }
        val tp = value.number("detectorTp")
        val fp = value.number("detectorFp")
        val fn = value.number("detectorFn")
        fun ratio(field: String, numerator: BigDecimal, denominator: BigDecimal) {
            val expected = if (denominator == BigDecimal.ZERO) BigDecimal.ZERO else numerator.divide(denominator, java.math.MathContext.DECIMAL64)
            require(value.number(field).subtract(expected).abs() <= BigDecimal("0.000000000001"))
        }
        ratio("detectorRecall", tp, tp + fn)
        ratio("detectorPrecision", tp, tp + fp)
        ratio("detectorFalsePositiveShare", fp, tp + fp)
    }

    private fun benchmark(value: Map<String, JsonValue>) {
        value.exact(setOf("sha256", "device", "androidVersion", "coldStartMs", "peakMemoryBytes", "medianEndToEndMs", "p95EndToEndMs", "byCardCount"))
        value.hash("sha256")
        require(value.string("device").isNotBlank() && value.string("androidVersion").isNotBlank())
        require(value.nonnegative("coldStartMs") > BigDecimal.ZERO && value.nonnegative("peakMemoryBytes") > BigDecimal.ZERO)
        fun timing(row: Map<String, JsonValue>, median: String, p95: String) {
            val duration = row.range(median, "0", "2000")
            require(duration > BigDecimal.ZERO && row.nonnegative(p95) >= duration)
        }
        timing(value, "medianEndToEndMs", "p95EndToEndMs")
        val counts = value.objectField("byCardCount")
        counts.exact((1..10).map(Int::toString).toSet())
        counts.values.forEach { it.objectValue().also { row ->
            row.exact(setOf("medianMs", "p95Ms")); timing(row, "medianMs", "p95Ms")
        } }
    }

    private fun Map<String, JsonValue>.exact(fields: Set<String>) { require(keys == fields) }
    private fun Map<String, JsonValue>.hash(name: String): String = string(name).also { require(SHA256.matches(it)) }
    private fun Map<String, JsonValue>.strings(name: String) = array(name).map { it.stringValue() }
    private fun nmsPolicy(value: Map<String, JsonValue>) {
        value.exact(setOf("version", "grid", "thresholdRepresentation", "scoreRepresentation", "comparison", "priority", "spatialOrder", "invalidBox"))
        require(value.int("version") == 1 && value.ints("grid") == listOf(320, 320))
        require(value.string("thresholdRepresentation") == "binary64-to-binary32-exact-rational")
        require(value.string("scoreRepresentation") == "binary32")
        require(value.string("comparison") == "strict-greater")
        require(value.string("priority") == "score-desc-left-top-right-bottom-index")
        require(value.string("spatialOrder") == "top-left-bottom-right-index")
        require(value.string("invalidBox") == "reject")
    }

    private fun resizePolicy(value: Map<String, JsonValue>) {
        value.exact(setOf("version", "algorithm", "coordinateTransform", "edgeMode", "rounding", "channels", "alpha", "antialias", "detectorSize", "classifierSize"))
        require(value.int("version") == 1)
        require(value.string("algorithm") == "point-bilinear")
        require(value.string("coordinateTransform") == "half-pixel")
        require(value.string("edgeMode") == "clamp")
        require(value.string("rounding") == "half-up-once")
        require(value.string("channels") == "RGB8")
        require(value.string("alpha") == "opaque-only")
        require((value.getValue("antialias") as? JsonBoolean)?.value == false)
        require(value.ints("detectorSize") == listOf(320, 320))
        require(value.ints("classifierSize") == listOf(224, 224))
    }

    private fun Map<String, JsonValue>.ints(name: String) = array(name).map { it.integer().intValueExact() }
    private fun JsonValue.integer(): BigDecimal {
        require(this is JsonNumber && value.matches(Regex("-?(0|[1-9][0-9]*)")))
        return numberValue()
    }
    private fun Map<String, JsonValue>.range(name: String, minimum: String, maximum: String): BigDecimal =
        number(name).also { require(it >= BigDecimal(minimum) && it <= BigDecimal(maximum)) }
    private fun Map<String, JsonValue>.nonnegative(name: String): BigDecimal =
        number(name).also { require(it.toDouble().isFinite() && it >= BigDecimal.ZERO) }
    private fun parseStrictJson(bytes: ByteArray): JsonValue {
        val reader = JsonReader(
            InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)),
        ).apply { isLenient = false }
        reader.use {
            val value = readValue(reader, depth = 0)
            if (reader.peek() != JsonToken.END_DOCUMENT) error("Trailing JSON content")
            return value
        }
    }

    private fun readValue(reader: JsonReader, depth: Int): JsonValue {
        require(depth <= MAX_JSON_DEPTH) { "JSON nesting is too deep" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val fields = linkedMapOf<String, JsonValue>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name !in fields) { "Duplicate JSON field" }
                    fields[name] = readValue(reader, depth + 1)
                }
                reader.endObject()
                JsonObject(fields)
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val values = mutableListOf<JsonValue>()
                while (reader.hasNext()) values += readValue(reader, depth + 1)
                reader.endArray()
                JsonArray(values)
            }
            JsonToken.STRING -> JsonString(reader.nextString())
            JsonToken.NUMBER -> JsonNumber(reader.nextString())
            JsonToken.BOOLEAN -> JsonBoolean(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull
            }
            else -> error("Invalid JSON token")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun JsonValue.objectValue(): Map<String, JsonValue> =
        (this as? JsonObject)?.fields ?: error("Expected JSON object")

    private fun JsonValue.stringValue(): String =
        (this as? JsonString)?.value ?: error("Expected JSON string")

    private fun JsonValue.numberValue(): BigDecimal =
        (this as? JsonNumber)?.value?.toBigDecimalOrNull() ?: error("Expected number")

    private fun Map<String, JsonValue>.string(name: String): String = getValue(name).stringValue()

    private fun Map<String, JsonValue>.number(name: String): BigDecimal =
        (getValue(name) as? JsonNumber)?.value?.toBigDecimalOrNull() ?: error("Expected number")

    private fun Map<String, JsonValue>.int(name: String): Int = getValue(name).integer().intValueExact()

    private fun Map<String, JsonValue>.long(name: String): Long = getValue(name).integer().longValueExact()

    private fun Map<String, JsonValue>.array(name: String): List<JsonValue> =
        (getValue(name) as? JsonArray)?.values ?: error("Expected array")

    private fun Map<String, JsonValue>.objectField(name: String): Map<String, JsonValue> =
        getValue(name).objectValue()

    private sealed interface JsonValue
    private data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue
    private data class JsonArray(val values: List<JsonValue>) : JsonValue
    private data class JsonString(val value: String) : JsonValue
    private data class JsonNumber(val value: String) : JsonValue
    private data class JsonBoolean(val value: Boolean) : JsonValue
    private data object JsonNull : JsonValue


    private companion object {
        const val MAX_RELEASE_JSON_BYTES = 128 * 1024
        const val MAX_JSON_DEPTH = 16
        val SHA256 = Regex("[0-9a-f]{64}")
        val HASH_FIELDS = setOf("holdoutPartitionSha256", "holdoutIdentity", "holdoutManifestSha256",
            "selectionSha256", "sourceManifestSha256", "sourceAuditReceiptSha256",
            "normalizedDatasetSha256", "sourceReportSha256", "evaluationReceiptSha256")
        val RELEASE_FIELDS = HASH_FIELDS + setOf("schemaVersion", "kind", "releaseApproved", "labels",
            "maximumCardCount", "confidenceCombination", "spatialOrder", "boxPolicy", "resizePolicy", "nmsPolicy", "thresholds", "detector", "classifier",
            "bindings", "benchmark", "metrics")
        val EXPERIMENTAL_HASH_FIELDS = setOf("sourceManifestSha256", "upstreamSplitSha256", "experimentReportSha256")
        val EXPERIMENTAL_FIELDS = RELEASE_FIELDS - HASH_FIELDS - "benchmark" + EXPERIMENTAL_HASH_FIELDS + "evidence"
        val MODEL_FIELDS = setOf("modelSha256", "modelSizeBytes", "filename", "variant", "input",
            "outputs", "customOps", "labels", "normalization", "quantization", "interpreterInvocation")
        val COUNT_FIELDS = setOf("sceneCount", "supportedSceneCount", "detectorTp", "detectorFp", "detectorFn",
            "falsePositives", "falseNegatives")
        val RATE_FIELDS = setOf("detectorRecall", "detectorPrecision", "detectorFalsePositiveShare",
            "perCardRecall", "perCardPrecision", "exactCardMultisetAccuracy", "exactHandTotalAccuracy", "abstentionRate")
        val METRIC_FIELDS = COUNT_FIELDS + RATE_FIELDS
    }
}

class ProcessLifetimeCardRecognitionEngineProvider internal constructor(
    private val factory: () -> CardRecognitionEngine,
) : CardRecognitionEngineProvider {
    private val lock = Any()

    @Volatile
    private var instance: CardRecognitionEngine? = null
    private var closed = false

    override fun get(): CardRecognitionEngine = synchronized(lock) {
        check(!closed) { "Recognition engine provider is closed" }
        instance ?: factory().also { instance = it }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            instance?.close()
        }
    }
}

class AssetGatedCardRecognitionEngineFactory internal constructor(
    private val verifier: RecognitionAssetVerifier,
    private val delegate: CardRecognitionEngineFactory?,
) {
    fun create(detectorModel: ByteArray?, classifierModel: ByteArray?, releaseJson: ByteArray?): CardRecognitionEngine {
        val detector = detectorModel?.copyOf()
        val classifier = classifierModel?.copyOf()
        val availability = verifier.verify(detector, classifier, releaseJson?.copyOf())
        if (availability !is RecognitionAvailability.Available || detector == null || classifier == null) {
            return UnavailableCardRecognitionEngine(availability)
        }
        val factory = delegate ?: return UnavailableCardRecognitionEngine(
            RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED),
        )
        return try {
            factory.create(detector, classifier, availability.metadata as RecognitionModelBundleMetadata)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: LinkageError) {
            UnavailableCardRecognitionEngine(
                RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED),
            )
        } catch (_: Exception) {
            UnavailableCardRecognitionEngine(
                RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED),
            )
        }
    }
}

class UnavailableCardRecognitionEngine internal constructor(
    override val availability: RecognitionAvailability,
) : CardRecognitionEngine {
    override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult =
        error("Card recognition is unavailable")

    override fun close() = Unit
}

object UnavailableCardRecognitionEngineProvider : CardRecognitionEngineProvider {
    private val engine = UnavailableCardRecognitionEngine(
        RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED),
    )

    override fun get(): CardRecognitionEngine = engine

    override fun close() = Unit
}
