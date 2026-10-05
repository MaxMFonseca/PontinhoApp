package com.maxmfonseca.pontinho.domain.recognition

import com.maxmfonseca.pontinho.domain.model.Card

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

enum class RecognitionWarning {
    LOW_CONFIDENCE,
    NEAR_IMAGE_EDGE,
}

data class RecognizedCard(
    val card: Card.Suited,
    val bounds: NormalizedRect,
    val confidence: Float,
    val warnings: Set<RecognitionWarning>,
)

data class CardRecognitionResult(
    val candidates: List<RecognizedCard>,
    val durationMillis: Long,
    val modelVersion: String,
)

data class ConfirmedCard(
    val card: Card.Suited,
    val recognitionConfidence: Float?,
    val correctedByUser: Boolean,
    val sourceBounds: NormalizedRect?,
)

data class RecognitionModelMetadata(
    val modelVersion: String,
    val modelSha256: String,
    val labels: List<String>,
    val inputWidth: Int,
    val inputHeight: Int,
    val confidenceThreshold: Float,
    val iouThreshold: Float,
)

sealed interface RecognitionAvailability {
    data class Available(val metadata: RecognitionModelMetadata) : RecognitionAvailability

    data object Unavailable : RecognitionAvailability
}
