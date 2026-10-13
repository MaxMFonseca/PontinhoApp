package com.maxmfonseca.pontinho.domain.recognition

import com.maxmfonseca.pontinho.domain.model.Card

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }
}

enum class RecognitionWarning {
    LOW_CONFIDENCE,
    NEAR_IMAGE_EDGE,
}

data class RecognizedCard(
    val card: Card.Suited,
    val bounds: NormalizedRect,
    val confidence: Float,
    val warnings: Set<RecognitionWarning>,
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

data class CardRecognitionResult(
    val candidates: List<RecognizedCard>,
    val durationMillis: Long,
    val modelVersion: String,
    val diagnostics: RecognitionDiagnostics? = null,
) {
    init {
        require(durationMillis >= 0)
        require(modelVersion.isNotBlank())
    }
}

/** Numeric-only debug evidence. It deliberately has no image, path, URI, EXIF, or user fields. */
data class RecognitionDiagnostics(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val letterboxScale: Double,
    val padLeft: Int,
    val padTop: Int,
    val padRight: Int,
    val padBottom: Int,
    val rawCandidateCount: Int,
    val maximumScore: Float,
    val confidenceThreshold: Double,
    val iouThreshold: Double,
    val rejectedBelowConfidence: Int,
    val rejectedByClassNms: Int,
    val rejectedByPhysicalNms: Int,
    val rejectedByIndexPairing: Int,
    val rejectedIncomplete: Int,
    val finalBoxes: List<RecognitionDiagnosticBox>,
    val durationMillis: Long,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(letterboxScale.isFinite() && letterboxScale > 0.0)
        require(listOf(padLeft, padTop, padRight, padBottom).all { it >= 0 })
        require(rawCandidateCount >= 0)
        require(maximumScore.isFinite() && maximumScore in 0f..1f)
        require(confidenceThreshold.isFinite() && confidenceThreshold in 0.0..1.0)
        require(iouThreshold.isFinite() && iouThreshold in 0.0..1.0)
        require(
            listOf(
                rejectedBelowConfidence,
                rejectedByClassNms,
                rejectedByPhysicalNms,
                rejectedByIndexPairing,
                rejectedIncomplete,
            ).all { it >= 0 },
        )
        require(durationMillis >= 0)
    }

    /** Stable compact JSON whose values are numbers or numeric arrays only. */
    fun toJson(): String = buildString {
        append("{\"sourceWidth\":").append(sourceWidth)
        append(",\"sourceHeight\":").append(sourceHeight)
        append(",\"letterboxScale\":").append(letterboxScale)
        append(",\"padding\":{")
        append("\"left\":").append(padLeft)
        append(",\"top\":").append(padTop)
        append(",\"right\":").append(padRight)
        append(",\"bottom\":").append(padBottom).append('}')
        append(",\"rawCandidateCount\":").append(rawCandidateCount)
        append(",\"maximumScore\":").append(maximumScore)
        append(",\"confidenceThreshold\":").append(confidenceThreshold)
        append(",\"iouThreshold\":").append(iouThreshold)
        append(",\"rejected\":{")
        append("\"belowConfidence\":").append(rejectedBelowConfidence)
        append(",\"classNms\":").append(rejectedByClassNms)
        append(",\"physicalNms\":").append(rejectedByPhysicalNms)
        append(",\"indexPairing\":").append(rejectedByIndexPairing)
        append(",\"incomplete\":").append(rejectedIncomplete).append('}')
        append(",\"finalBoxes\":[")
        finalBoxes.forEachIndexed { index, box ->
            if (index > 0) append(',')
            append('[').append(box.classIndex)
            append(',').append(box.confidence)
            append(',').append(box.left)
            append(',').append(box.top)
            append(',').append(box.right)
            append(',').append(box.bottom).append(']')
        }
        append("]")
        append(",\"durationMillis\":").append(durationMillis).append('}')
    }
}

data class RecognitionDiagnosticBox(
    val classIndex: Int,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(classIndex in 0..51)
        require(confidence.isFinite() && confidence in 0f..1f)
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }
}

data class ConfirmedCard(
    val card: Card.Suited,
    val recognitionConfidence: Float?,
    val correctedByUser: Boolean,
    val sourceBounds: NormalizedRect?,
) {
    init {
        require(
            recognitionConfidence == null ||
                recognitionConfidence.isFinite() && recognitionConfidence in 0f..1f,
        )
    }
}

sealed interface RecognitionMetadata {
    val modelVersion: String
}

data class YoloRecognitionMetadata(
    val modelSha256: String,
    val modelSizeBytes: Long,
    val quantization: String,
    val labels: List<String>,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputShape: List<Int>,
    val normalizationScale: Double,
    val normalizationOffset: Double,
    val confidenceThreshold: Double,
    val iouThreshold: Double,
    val maxCards: Int,
    val datasetReceiptSha256: String,
    val datasetAuditSha256: String,
    val holdoutReceiptSha256: String,
    val exportInvocationSha256: String,
    val validationEvaluationSha256: String,
    val testEvaluationSha256: String,
    val debugOnly: Boolean,
    val productionApproved: Boolean,
) : RecognitionMetadata {
    override val modelVersion: String = modelSha256
}

/** Compatibility value for existing injected UI fixtures; never produced by the asset verifier. */
data class RecognitionModelMetadata(
    override val modelVersion: String,
    val modelSha256: String,
    val labels: List<String>,
    val inputWidth: Int,
    val inputHeight: Int,
    val confidenceThreshold: Float,
    val iouThreshold: Float,
) : RecognitionMetadata {
    init {
        require(modelVersion.isNotBlank())
        require(modelSha256.matches(Regex("[0-9a-f]{64}")))
        require(
            labels.size == 52 &&
                labels.distinct().size == labels.size &&
                labels.none(String::isBlank),
        )
        require(inputWidth > 0 && inputHeight > 0)
        require(confidenceThreshold.isFinite() && confidenceThreshold in 0f..1f)
        require(iouThreshold.isFinite() && iouThreshold in 0f..1f)
    }
}

enum class RecognitionUnavailableReason {
    MISSING_ASSETS,
    INVALID_ASSETS,
    ENGINE_NOT_INSTALLED,
}

sealed interface RecognitionAvailability {
    data object Checking : RecognitionAvailability

    data class Available(val metadata: RecognitionMetadata) : RecognitionAvailability

    data class Unavailable(val reason: RecognitionUnavailableReason) : RecognitionAvailability
}

/** Compile-only compatibility for the two-stage implementation retained until Stage 8. */
data class RecognitionStageMetadata(
    val modelSha256: String,
    val modelSizeBytes: Long,
    val labels: List<String>,
    val inputWidth: Int,
    val inputHeight: Int,
    val quantizationVariant: String,
    val calibrationTemperature: Float = 1f,
    /** Native MediaPipe scores are dequantized; retain the verified step for rounding bounds. */
    val outputQuantizationScale: Double = 0.0,
)

/** Compile-only compatibility for the two-stage implementation retained until Stage 8. */
data class RecognitionModelBundleMetadata(
    val version: String,
    val detector: RecognitionStageMetadata,
    val classifier: RecognitionStageMetadata,
    val labels: List<String>,
    val detectorThreshold: Float,
    /** The selected combined-score floor: min(detector, calibrated classifier). */
    val classifierThreshold: Float,
    val iouThreshold: Float,
    val cropMargin: Float,
    val maxCards: Int,
    val experimental: Boolean = false,
) : RecognitionMetadata {
    override val modelVersion get() = version
}
