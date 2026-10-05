package com.maxmfonseca.pontinho.recognition

import android.util.JsonReader
import android.util.JsonToken
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngine
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngineProvider
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelMetadata
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class RecognitionAssetVerifier {
    fun verify(model: ByteArray?, releaseJson: ByteArray?): RecognitionAvailability {
        if (model == null || releaseJson == null || model.isEmpty() || releaseJson.isEmpty()) {
            return RecognitionAvailability.Unavailable
        }
        if (releaseJson.size > MAX_RELEASE_JSON_BYTES) return RecognitionAvailability.Unavailable

        return try {
            val root = parseStrictJson(releaseJson).objectValue()
            if (root.keys != RELEASE_FIELDS) return RecognitionAvailability.Unavailable
            if (root.int("schemaVersion") != RELEASE_SCHEMA_VERSION) return RecognitionAvailability.Unavailable
            if (root.string("status") != APPROVED_STATUS) return RecognitionAvailability.Unavailable

            val modelVersion = root.string("modelVersion")
            if (!MODEL_VERSION.matches(modelVersion)) return RecognitionAvailability.Unavailable
            val modelHash = root.string("modelSha256")
            if (!SHA256.matches(modelHash) || sha256(model) != modelHash) {
                return RecognitionAvailability.Unavailable
            }
            if (root.long("modelSizeBytes") != model.size.toLong()) {
                return RecognitionAvailability.Unavailable
            }

            val labels = root.array("labels").map { it.stringValue() }
            if (labels != CANONICAL_LABELS) return RecognitionAvailability.Unavailable
            val width = root.int("inputWidth")
            val height = root.int("inputHeight")
            if (width != INPUT_WIDTH || height != INPUT_HEIGHT) {
                return RecognitionAvailability.Unavailable
            }
            if (!validQuantization(root.objectField("quantization"))) {
                return RecognitionAvailability.Unavailable
            }

            val confidence = root.unitFloat("confidenceThreshold")
            val iou = root.unitFloat("iouThreshold")
            if (!validReleaseEvidence(root)) return RecognitionAvailability.Unavailable

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
            RecognitionAvailability.Unavailable
        }
    }

    private fun validQuantization(value: Map<String, JsonValue>): Boolean {
        if (value.keys != QUANTIZATION_FIELDS || value.string("variant") != "int8") return false
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
        if (tensorTypes.isEmpty() || tensorTypes.any { it !in ALLOWED_INT8_TENSOR_TYPES }) return false
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
        return EXPORTED_AT.matches(root.string("exportedAt"))
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

        val MODEL_VERSION = Regex("\\d{4}\\.\\d{2}\\.\\d{2}-int8")
        val SHA256 = Regex("[0-9a-f]{64}")
        val EXPORTED_AT = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")
        val CANONICAL_LABELS = listOf("C", "D", "H", "S").flatMap { suit ->
            listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
                .map { rank -> "$rank$suit" }
        }
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
        val ALLOWED_INT8_TENSOR_TYPES = setOf("INT8", "UINT8", "INT32")
    }
}

class ProcessLifetimeCardRecognitionEngineProvider internal constructor(
    private val factory: () -> CardRecognitionEngine,
) : CardRecognitionEngineProvider {
    private val lock = Any()

    @Volatile
    private var instance: CardRecognitionEngine? = null
    private var closed = false

    override fun get(): CardRecognitionEngine = instance ?: synchronized(lock) {
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
