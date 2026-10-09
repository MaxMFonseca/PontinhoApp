package com.maxmfonseca.pontinho.domain.recognition

/** Retryable capture failures; never converted into a successful zero-point hand. */
sealed class CardRecognitionFailure(message: String) : IllegalStateException(message) {
    class NoCards : CardRecognitionFailure("No complete cards detected")
    class IncompleteCards : CardRecognitionFailure("Potentially truncated cards require a new capture")
    class TooManyCards(val detectedCount: Int) : CardRecognitionFailure("Too many cards detected")
}
