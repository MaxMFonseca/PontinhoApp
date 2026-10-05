package com.maxmfonseca.pontinho.domain.recognition

import android.graphics.Bitmap

interface CardRecognitionEngine : AutoCloseable {
    val availability: RecognitionAvailability

    suspend fun recognize(bitmap: Bitmap): CardRecognitionResult
}

interface CardRecognitionEngineProvider : AutoCloseable {
    fun get(): CardRecognitionEngine
}
