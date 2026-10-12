package com.maxmfonseca.pontinho.domain.recognition

import android.graphics.Bitmap

interface CardRecognitionEngine : AutoCloseable {
    val availability: RecognitionAvailability

    suspend fun recognize(bitmap: Bitmap): CardRecognitionResult
}

interface CardRecognitionEngineProvider : AutoCloseable {
    /** Production overrides with asynchronous verification; static injected engines use this snapshot. */
    val availability: kotlinx.coroutines.flow.StateFlow<RecognitionAvailability>
        get() = kotlinx.coroutines.flow.MutableStateFlow(get().availability)
    fun get(): CardRecognitionEngine
}

fun interface CardRecognitionEngineFactory {
    fun create(
        model: ByteArray,
        metadata: YoloRecognitionMetadata,
    ): CardRecognitionEngine

    /** Temporary source bridge for the legacy runtime, which Stage 8 removes. */
    fun create(
        detectorModel: ByteArray,
        classifierModel: ByteArray,
        metadata: RecognitionModelBundleMetadata,
    ): CardRecognitionEngine = throw UnsupportedOperationException("Two-stage recognition is disabled")
}
