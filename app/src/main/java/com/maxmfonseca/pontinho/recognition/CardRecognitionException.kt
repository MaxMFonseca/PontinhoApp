package com.maxmfonseca.pontinho.recognition

sealed class CardRecognitionException(message: String) : IllegalStateException(message) {
    class Closed : CardRecognitionException("Card recognition engine is closed")
    class InferenceFailed : CardRecognitionException("Card recognition failed")
}
