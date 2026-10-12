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

/** Verifies the exact single-stage bundle emitted by the pinned Stage 5 exporter. */
class RecognitionAssetVerifier internal constructor(private val allowExperimental: Boolean = false) {
    fun verify(model: ByteArray?, releaseJson: ByteArray?): RecognitionAvailability {
        if (model == null || releaseJson == null || model.isEmpty() || releaseJson.isEmpty()) {
            return unavailable(RecognitionUnavailableReason.MISSING_ASSETS)
        }
        if (releaseJson.size > MAX_RELEASE_JSON_BYTES) return invalid()
        return try {
            val root = parseStrictJson(releaseJson).objectValue()
            root.exact(ROOT_FIELDS)
            require(root.int("schemaVersion") == 1)
            require(root.string("candidateStatus") == "DEBUG_ONLY")
            require(root.boolean("productionApproved") == false)
            require(BuildConfig.DEBUG && allowExperimental)

            val modelObject = root.objectField("model")
            modelObject.exact(MODEL_FIELDS)
            require(modelObject.string("file") == "card_detector.tflite")
            val modelSha256 = modelObject.hash("sha256")
            require(modelSha256 == sha256(model))
            require(modelObject.long("sizeBytes") == model.size.toLong() && model.isNotEmpty())
            val quantization = modelObject.string("quantization")
            require(quantization in setOf("fp32", "int8"))
            val checkpointSha256 = modelObject.hash("sourceCheckpointSha256")
            internalQuantization(modelObject.objectField("internalQuantization"), quantization)

            val input = root.objectField("input")
            input.exact(INPUT_FIELDS)
            require(input.ints("shape") == listOf(1, YOLO_INPUT_SIZE, YOLO_INPUT_SIZE, 3))
            require(input.string("dtype") == "float32")
            require(input.string("colorSpace") == "RGB")
            require(input.getValue("quantization") is JsonNull)
            require(input.string("resizePolicy") == "centered-letterbox-bilinear")
            val normalization = input.objectField("normalization")
            normalization.exact(setOf("scale", "offset"))
            val normalizationScale = normalization.finite("scale")
            val normalizationOffset = normalization.finite("offset")
            require(normalizationScale.toDouble() == 1.0 / 255.0)
            require(normalizationOffset.compareTo(BigDecimal.ZERO) == 0)

            val output = root.objectField("output")
            output.exact(OUTPUT_FIELDS)
            val outputShape = output.ints("shape")
            require(outputShape == listOf(1, 56, 8400))
            require(output.string("dtype") == "float32")
            require(output.getValue("quantization") is JsonNull)
            require(output.string("layout") == "channels-first")
            require(output.boolean("raw"))
            require(output.string("boxEncoding") == "xywh")
            require(output.string("boxCoordinateUnits") == "normalized-input-dimensions")
            require(output.int("classCount") == 52)
            val reference = output.objectField("boxCoordinateReference")
            reference.exact(setOf("width", "height"))
            require(reference.int("width") == YOLO_INPUT_SIZE && reference.int("height") == YOLO_INPUT_SIZE)

            val labels = root.strings("labels")
            require(labels == YoloCardLabels.values)
            val thresholds = root.objectField("thresholds")
            thresholds.exact(THRESHOLD_FIELDS)
            val confidence = thresholds.exclusiveUnit("confidence")
            val iou = thresholds.exclusiveUnit("iou")
            require(thresholds.string("comparison") == "strict-greater-than")
            require(thresholds.string("selectedOn") == "validation")
            thresholds.hash("selectionSha256")
            require(thresholds.hash("sourceCheckpointSha256") == checkpointSha256)
            nms(root.objectField("nms"))
            require(root.int("maxCards") == 10)

            RecognitionAvailability.Available(
                YoloRecognitionMetadata(
                    modelSha256 = modelSha256,
                    modelSizeBytes = model.size.toLong(),
                    quantization = quantization,
                    labels = labels.toList(),
                    inputWidth = YOLO_INPUT_SIZE,
                    inputHeight = YOLO_INPUT_SIZE,
                    outputShape = outputShape.toList(),
                    normalizationScale = normalizationScale.toDouble(),
                    normalizationOffset = normalizationOffset.toDouble(),
                    confidenceThreshold = confidence.toDouble(),
                    iouThreshold = iou.toDouble(),
                    maxCards = 10,
                    datasetReceiptSha256 = root.hash("datasetReceiptSha256"),
                    datasetAuditSha256 = root.hash("datasetAuditSha256"),
                    holdoutReceiptSha256 = root.hash("holdoutReceiptSha256"),
                    exportInvocationSha256 = root.hash("exportInvocationSha256"),
                    validationEvaluationSha256 = root.hash("validationEvaluationSha256"),
                    testEvaluationSha256 = root.hash("testEvaluationSha256"),
                    debugOnly = true,
                    productionApproved = root.boolean("productionApproved"),
                ),
            )
        } catch (_: Exception) {
            invalid()
        }
    }

    /** Legacy callers compile until Stage 8 removes them, but no classifier bundle is admitted. */
    fun verify(detector: ByteArray?, classifier: ByteArray?, releaseJson: ByteArray?): RecognitionAvailability =
        unavailable(
            if (detector == null || classifier == null || releaseJson == null) RecognitionUnavailableReason.MISSING_ASSETS
            else RecognitionUnavailableReason.INVALID_ASSETS,
        )

    private fun internalQuantization(value: Map<String, JsonValue>, variant: String) {
        value.exact(INTERNAL_QUANTIZATION_FIELDS)
        val int8Tensors = value.int("int8TensorCount")
        val weighted = value.int("weightedComputeOperatorCount")
        val quantized = value.int("quantizedComputeOperatorCount")
        val coverage = value.finite("quantizedComputeCoverage")
        require(int8Tensors >= 0 && weighted >= 0 && quantized in 0..weighted)
        require(coverage >= BigDecimal.ZERO && coverage <= BigDecimal.ONE)
        val expectedCoverage = if (weighted == 0) 0.0 else quantized.toDouble() / weighted.toDouble()
        require(coverage.toDouble() == expectedCoverage)
        val codes = value.strings("operatorCodes")
        require(codes == codes.distinct())
        require(codes.all { it in WEIGHTED_OPERATOR_CODES })
        if (variant == "int8") {
            require(int8Tensors > 0 && weighted > 0 && quantized > 0)
            require(coverage >= BigDecimal("0.8"))
        } else {
            require(int8Tensors == 0 && quantized == 0 && coverage.compareTo(BigDecimal.ZERO) == 0 && codes.isEmpty())
        }
    }

    private fun nms(value: Map<String, JsonValue>) {
        value.exact(NMS_FIELDS)
        require(value.boolean("classAwareFirst"))
        require(value.boolean("physicalDuplicateSuppression"))
        require(value.finite("physicalDuplicateIou").compareTo(BigDecimal("0.95")) == 0)
        require(value.string("tieBreak") == "score-desc-box-class-anchor")
        require(value.string("finalOrder") == "left-top-right-bottom-class-score-anchor")
    }

    private fun unavailable(reason: RecognitionUnavailableReason) = RecognitionAvailability.Unavailable(reason)
    private fun invalid() = unavailable(RecognitionUnavailableReason.INVALID_ASSETS)

    private fun parseStrictJson(bytes: ByteArray): JsonValue {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = JsonReader(InputStreamReader(ByteArrayInputStream(bytes), decoder)).apply { isLenient = false }
        reader.use {
            val value = readValue(reader, 0)
            if (reader.peek() != JsonToken.END_DOCUMENT) error("Trailing JSON content")
            return value
        }
    }

    private fun readValue(reader: JsonReader, depth: Int): JsonValue {
        require(depth <= MAX_JSON_DEPTH)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val fields = linkedMapOf<String, JsonValue>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name !in fields)
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
            JsonToken.NULL -> { reader.nextNull(); JsonNull }
            else -> error("Invalid JSON token")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun JsonValue.objectValue() = (this as? JsonObject)?.fields ?: error("Expected object")
    private fun JsonValue.stringValue() = (this as? JsonString)?.value ?: error("Expected string")
    private fun Map<String, JsonValue>.exact(expected: Set<String>) = require(keys == expected)
    private fun Map<String, JsonValue>.objectField(name: String) = getValue(name).objectValue()
    private fun Map<String, JsonValue>.string(name: String) = getValue(name).stringValue()
    private fun Map<String, JsonValue>.boolean(name: String) = (getValue(name) as? JsonBoolean)?.value ?: error("Expected boolean")
    private fun Map<String, JsonValue>.array(name: String) = (getValue(name) as? JsonArray)?.values ?: error("Expected array")
    private fun Map<String, JsonValue>.strings(name: String) = array(name).map { it.stringValue() }
    private fun Map<String, JsonValue>.number(name: String) =
        (getValue(name) as? JsonNumber)?.value?.toBigDecimalOrNull() ?: error("Expected number")
    private fun Map<String, JsonValue>.finite(name: String) = number(name).also { require(it.toDouble().isFinite()) }
    private fun Map<String, JsonValue>.integer(name: String) = number(name).also {
        require((getValue(name) as JsonNumber).value.matches(INTEGER))
    }
    private fun Map<String, JsonValue>.int(name: String) = integer(name).intValueExact()
    private fun Map<String, JsonValue>.long(name: String) = integer(name).longValueExact()
    private fun Map<String, JsonValue>.ints(name: String) = array(name).map {
        val number = (it as? JsonNumber)?.value ?: error("Expected integer")
        require(number.matches(INTEGER))
        number.toBigDecimal().intValueExact()
    }
    private fun Map<String, JsonValue>.hash(name: String) = string(name).also { require(it.matches(SHA256)) }
    private fun Map<String, JsonValue>.exclusiveUnit(name: String) = finite(name).also {
        require(it > BigDecimal.ZERO && it < BigDecimal.ONE)
    }

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
        const val YOLO_INPUT_SIZE = 640
        val SHA256 = Regex("[0-9a-f]{64}")
        val INTEGER = Regex("-?(0|[1-9][0-9]*)")
        val ROOT_FIELDS = setOf("schemaVersion", "candidateStatus", "productionApproved", "model", "input", "output",
            "labels", "thresholds", "nms", "maxCards", "datasetReceiptSha256", "datasetAuditSha256",
            "holdoutReceiptSha256", "exportInvocationSha256", "validationEvaluationSha256", "testEvaluationSha256")
        val MODEL_FIELDS = setOf("file", "sha256", "sizeBytes", "quantization", "sourceCheckpointSha256", "internalQuantization")
        val INTERNAL_QUANTIZATION_FIELDS = setOf("int8TensorCount", "weightedComputeOperatorCount",
            "quantizedComputeOperatorCount", "quantizedComputeCoverage", "operatorCodes")
        val INPUT_FIELDS = setOf("shape", "dtype", "colorSpace", "normalization", "quantization", "resizePolicy")
        val OUTPUT_FIELDS = setOf("shape", "dtype", "quantization", "layout", "raw", "boxEncoding",
            "boxCoordinateUnits", "boxCoordinateReference", "classCount")
        val THRESHOLD_FIELDS = setOf("confidence", "iou", "comparison", "selectedOn", "selectionSha256", "sourceCheckpointSha256")
        val NMS_FIELDS = setOf("classAwareFirst", "physicalDuplicateSuppression", "physicalDuplicateIou", "tieBreak", "finalOrder")
        val WEIGHTED_OPERATOR_CODES = setOf("CONV_2D", "DEPTHWISE_CONV_2D", "FULLY_CONNECTED")
    }
}

class ProcessLifetimeCardRecognitionEngineProvider internal constructor(
    private val factory: () -> CardRecognitionEngine,
) : CardRecognitionEngineProvider {
    private val lock = Any()
    @Volatile private var instance: CardRecognitionEngine? = null
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
    private val delegate: ((ByteArray, ByteArray, RecognitionModelBundleMetadata) -> CardRecognitionEngine)?,
) {
    fun create(modelSource: ByteArray?, releaseSource: ByteArray?): CardRecognitionEngine {
        val availability = verifier.verify(modelSource?.copyOf(), releaseSource?.copyOf())
        if (availability !is RecognitionAvailability.Available || modelSource == null) return UnavailableCardRecognitionEngine(availability)
        return UnavailableCardRecognitionEngine(
            RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED),
        )
    }

    fun create(detectorModel: ByteArray?, classifierModel: ByteArray?, releaseJson: ByteArray?): CardRecognitionEngine {
        val availability = verifier.verify(detectorModel, classifierModel, releaseJson)
        if (availability !is RecognitionAvailability.Available || detectorModel == null || classifierModel == null) {
            return UnavailableCardRecognitionEngine(availability)
        }
        val legacy = delegate ?: return UnavailableCardRecognitionEngine(
            RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED),
        )
        return try {
            legacy(detectorModel.copyOf(), classifierModel.copyOf(), availability.metadata as RecognitionModelBundleMetadata)
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (_: Throwable) {
            UnavailableCardRecognitionEngine(RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED))
        }
    }
}

class UnavailableCardRecognitionEngine internal constructor(
    override val availability: RecognitionAvailability,
) : CardRecognitionEngine {
    override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult = error("Card recognition is unavailable")
    override fun close() = Unit
}

object UnavailableCardRecognitionEngineProvider : CardRecognitionEngineProvider {
    private val engine = UnavailableCardRecognitionEngine(
        RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED),
    )
    override fun get(): CardRecognitionEngine = engine
    override fun close() = Unit
}
