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
) {
    init {
        require(durationMillis >= 0)
        require(modelVersion.isNotBlank())
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

data class RecognitionModelMetadata(
    val modelVersion: String,
    val modelSha256: String,
    val labels: List<String>,
    val inputWidth: Int,
    val inputHeight: Int,
    val confidenceThreshold: Float,
    val iouThreshold: Float,
) {
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
    data class Available(val metadata: RecognitionModelMetadata) : RecognitionAvailability

    data class Unavailable(val reason: RecognitionUnavailableReason) : RecognitionAvailability
}
