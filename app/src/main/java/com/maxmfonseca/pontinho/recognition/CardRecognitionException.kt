package com.maxmfonseca.pontinho.recognition

import com.maxmfonseca.pontinho.domain.recognition.RecognitionDiagnostics

sealed class CardRecognitionException(message: String) : IllegalStateException(message) {
    class Closed : CardRecognitionException("Card recognition engine is closed")
    class InferenceFailed(
        val diagnostics: RecognitionDiagnostics? = null,
    ) : CardRecognitionException("Card recognition failed")
}
