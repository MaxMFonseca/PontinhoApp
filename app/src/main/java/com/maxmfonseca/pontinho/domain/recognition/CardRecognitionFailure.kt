package com.maxmfonseca.pontinho.domain.recognition

/** Retryable capture failures; never converted into a successful zero-point hand. */
sealed class CardRecognitionFailure(
    message: String,
    val diagnostics: RecognitionDiagnostics? = null,
) : IllegalStateException(message) {
    class NoCards(diagnostics: RecognitionDiagnostics? = null) :
        CardRecognitionFailure("No complete cards detected", diagnostics)

    class IncompleteCards(diagnostics: RecognitionDiagnostics? = null) :
        CardRecognitionFailure("Potentially truncated cards require a new capture", diagnostics)

    class TooManyCards(
        val detectedCount: Int,
        diagnostics: RecognitionDiagnostics? = null,
    ) : CardRecognitionFailure("Too many cards detected", diagnostics)
}
