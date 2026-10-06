package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.util.JsonReader
import android.util.JsonToken
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngine
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngineFactory
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngineProvider
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionResult
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelMetadata
import com.maxmfonseca.pontinho.domain.recognition.RecognitionUnavailableReason
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

class RecognitionAssetVerifier {
    fun verify(model: ByteArray?, releaseJson: ByteArray?): RecognitionAvailability {
        if (model == null || releaseJson == null || model.isEmpty() || releaseJson.isEmpty()) {
            return RecognitionAvailability.Unavailable(RecognitionUnavailableReason.MISSING_ASSETS)
        }
        if (releaseJson.size > MAX_RELEASE_JSON_BYTES) return invalid()

        return try {
            val root = parseStrictJson(releaseJson).objectValue()
            if (root.keys != RELEASE_FIELDS) return invalid()
            if (root.int("schemaVersion") != RELEASE_SCHEMA_VERSION) return invalid()
            if (root.string("status") != APPROVED_STATUS) return invalid()

            val modelVersion = root.string("modelVersion")
            if (!MODEL_VERSION.matches(modelVersion)) return invalid()
            val modelHash = root.string("modelSha256")
            if (!SHA256.matches(modelHash) || sha256(model) != modelHash) {
                return invalid()
            }
            if (root.long("modelSizeBytes") != model.size.toLong()) {
                return invalid()
            }

            val labels = root.array("labels").map { it.stringValue() }
            if (labels != CardClassIndex.labels) return invalid()
            val width = root.int("inputWidth")
            val height = root.int("inputHeight")
            if (width != INPUT_WIDTH || height != INPUT_HEIGHT) {
                return invalid()
            }
            if (!validQuantization(root.objectField("quantization"))) {
                return invalid()
            }

            val confidence = root.unitFloat("confidenceThreshold")
            val iou = root.unitFloat("iouThreshold")
            if (!validReleaseEvidence(root)) return invalid()

            RecognitionAvailability.Available(
                RecognitionModelMetadata(
                    modelVersion = modelVersion,
                    modelSha256 = modelHash,
                    labels = labels,
                    inputWidth = width,
                    inputHeight = height,
                    confidenceThreshold = confidence,
                    iouThreshold = iou,
                ),
            )
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun invalid() = RecognitionAvailability.Unavailable(RecognitionUnavailableReason.INVALID_ASSETS)

    private fun validQuantization(value: Map<String, JsonValue>): Boolean {
        if (value.keys != QUANTIZATION_FIELDS) return false
        return when (value.string("variant")) {
            "float16" -> validFloat16Quantization(value)
            "int8" -> validInt8Quantization(value)
            else -> false
        }
    }

    private fun validFloat16Quantization(value: Map<String, JsonValue>): Boolean {
        val tensorTypes = value.array("nonemptyConstantTensorTypes").map { it.stringValue() }
        return value.getValue("input") is JsonNull &&
            tensorTypes == tensorTypes.distinct().sorted() &&
            "FLOAT16" in tensorTypes &&
            tensorTypes.all { it in ALLOWED_FLOAT16_TENSOR_TYPES } &&
            value.int("quantizedConstantCount") == 0
    }

    private fun validInt8Quantization(value: Map<String, JsonValue>): Boolean {
        val input = value.getValue("input")
        if (input !is JsonObject || input.fields.keys != INPUT_QUANTIZATION_FIELDS) return false
        val scales = input.fields.array("scales").map { it.numberValue() }
        val zeroPoints = input.fields.array("zeroPoints").map { it.numberValue() }
        if (scales.size != 1 || !scales.single().toDouble().isFinite() || scales.single() <= BigDecimal.ZERO) {
            return false
        }
        if (zeroPoints.size != 1) return false
        val zeroPoint = runCatching { zeroPoints.single().intValueExact() }.getOrNull() ?: return false
        if (zeroPoint !in MIN_INPUT_ZERO_POINT..MAX_INPUT_ZERO_POINT) return false
        if (input.fields.int("quantizedDimension") != 0) return false
        val tensorTypes = value.array("nonemptyConstantTensorTypes").map { it.stringValue() }
        if (
            tensorTypes != tensorTypes.distinct().sorted() ||
            tensorTypes.none { it in QUANTIZED_BYTE_TENSOR_TYPES } ||
            tensorTypes.any { it !in ALLOWED_INT8_TENSOR_TYPES }
        ) {
            return false
        }
        return value.int("quantizedConstantCount") > 0
    }

    private fun validReleaseEvidence(root: Map<String, JsonValue>): Boolean {
        if (root.string("sourceReportPath").isBlank()) return false
        HASH_FIELDS.forEach { field ->
            if (!SHA256.matches(root.string(field))) return false
        }
        OPTIONAL_HASH_FIELDS.forEach { field ->
            val value = root.getValue(field)
            if (value !is JsonNull && (value !is JsonString || !SHA256.matches(value.value))) return false
        }
        if (root.unitFloat("exactTotalAccuracy") < MIN_EXACT_TOTAL_ACCURACY) return false
        root.unitFloat("exactCardMultisetAccuracy")
        root.unitFloat("abstentionRate")
        if (root.int("heldOutPhotoCount") < MIN_HELD_OUT_PHOTOS) return false
        if (root.int("heldOutDeckCount") < MIN_HELD_OUT_DECKS) return false
        val exportedAt = root.string("exportedAt")
        if (!RFC3339_UTC.matches(exportedAt)) return false
        return runCatching { Instant.parse(exportedAt) }.isSuccess
    }

    private fun parseStrictJson(bytes: ByteArray): JsonValue {
        val reader = JsonReader(
            InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
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

    private fun Map<String, JsonValue>.int(name: String): Int = number(name).intValueExact()

    private fun Map<String, JsonValue>.long(name: String): Long = number(name).longValueExact()

    private fun Map<String, JsonValue>.unitFloat(name: String): Float {
        val number = number(name)
        require(number >= BigDecimal.ZERO && number <= BigDecimal.ONE) { "Expected unit value" }
        return number.toFloat()
    }

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
        const val RELEASE_SCHEMA_VERSION = 1
        const val APPROVED_STATUS = "APPROVED"
        const val INPUT_WIDTH = 320
        const val INPUT_HEIGHT = 320
        const val MIN_HELD_OUT_PHOTOS = 200
        const val MIN_HELD_OUT_DECKS = 10
        const val MIN_EXACT_TOTAL_ACCURACY = 0.95f
        const val MIN_INPUT_ZERO_POINT = -128
        const val MAX_INPUT_ZERO_POINT = 255

        val MODEL_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val RFC3339_UTC = Regex(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z",
        )
        val SHA256 = Regex("[0-9a-f]{64}")
        val RELEASE_FIELDS = setOf(
            "schemaVersion", "status", "modelVersion", "modelSha256", "modelSizeBytes",
            "labels", "inputWidth", "inputHeight", "quantization", "confidenceThreshold",
            "iouThreshold", "sourceReportPath", "sourceReportSha256", "modelContractSha256",
            "candidateContractSha256", "comparisonSha256", "validationManifestSha256",
            "thresholdSelectionSha256", "validationReceiptSha256", "sourceAuditReceiptSha256",
            "sourceManifestSha256", "splitManifestSha256", "trainingInputSha256",
            "syntheticManifestSha256", "syntheticReviewSha256",
            "syntheticGenerationConfigSha256", "testManifestSha256", "exactTotalAccuracy",
            "exactCardMultisetAccuracy", "abstentionRate", "heldOutPhotoCount",
            "heldOutDeckCount", "exportedAt",
        )
        val HASH_FIELDS = setOf(
            "sourceReportSha256", "modelContractSha256", "candidateContractSha256",
            "comparisonSha256", "validationManifestSha256", "thresholdSelectionSha256",
            "validationReceiptSha256", "sourceAuditReceiptSha256", "sourceManifestSha256",
            "splitManifestSha256", "trainingInputSha256", "testManifestSha256",
        )
        val OPTIONAL_HASH_FIELDS = setOf(
            "syntheticManifestSha256", "syntheticReviewSha256",
            "syntheticGenerationConfigSha256",
        )
        val QUANTIZATION_FIELDS = setOf(
            "variant", "input", "nonemptyConstantTensorTypes", "quantizedConstantCount",
        )
        val INPUT_QUANTIZATION_FIELDS = setOf("scales", "zeroPoints", "quantizedDimension")
        val ALLOWED_FLOAT16_TENSOR_TYPES = setOf("FLOAT16", "FLOAT32", "INT32")
        val QUANTIZED_BYTE_TENSOR_TYPES = setOf("INT8", "UINT8")
        val ALLOWED_INT8_TENSOR_TYPES = setOf("FLOAT32", "INT8", "UINT8", "INT32")
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
    fun create(model: ByteArray?, releaseJson: ByteArray?): CardRecognitionEngine {
        val availability = verifier.verify(model, releaseJson)
        if (availability !is RecognitionAvailability.Available || model == null) {
            return UnavailableCardRecognitionEngine(availability)
        }
        val factory = delegate ?: return UnavailableCardRecognitionEngine(
            RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED),
        )
        return try {
            factory.create(model, availability.metadata)
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
