package com.maxmfonseca.pontinho.recognition

import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import com.maxmfonseca.pontinho.domain.recognition.NormalizedRect
import com.maxmfonseca.pontinho.domain.recognition.RecognizedCard
import com.maxmfonseca.pontinho.domain.recognition.RecognitionWarning
import kotlin.math.max
import kotlin.math.min

data class RawCardDetection(
    val classIndex: Int,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class DetectionPostProcessingConfig(
    val confidenceThreshold: Float,
    val lowConfidenceThreshold: Float,
    val iouThreshold: Float,
    val edgeThreshold: Float,
    val expectedCardCount: IntRange = 1..15,
) {
    init {
        require(confidenceThreshold.isFinite() && confidenceThreshold in 0f..1f)
        require(lowConfidenceThreshold.isFinite() && lowConfidenceThreshold in confidenceThreshold..1f)
        require(iouThreshold.isFinite() && iouThreshold in 0f..1f)
        require(edgeThreshold.isFinite() && edgeThreshold in 0f..<0.5f)
        require(
            !expectedCardCount.isEmpty() &&
                expectedCardCount.first >= 0 &&
                expectedCardCount.last <= CardClassIndex.labels.size,
        )
    }
}

enum class DetectionSetWarning {
    UNEXPECTED_CARD_COUNT,
}

data class CardDetectionPostProcessingResult(
    val cards: List<RecognizedCard>,
    val warnings: Set<DetectionSetWarning>,
)

internal object CardClassIndex {
    private val suits = listOf(Suit.CLUBS, Suit.DIAMONDS, Suit.HEARTS, Suit.SPADES)
    private val ranks = Rank.entries

    val labels: List<String> = suits.flatMap { suit ->
        ranks.map { rank -> "${rank.label}${suit.label}" }
    }

    fun cardAt(index: Int): Card.Suited? {
        if (index !in labels.indices) return null
        return Card.Suited(ranks[index % ranks.size], suits[index / ranks.size])
    }

    fun indexOf(card: Card.Suited): Int = suits.indexOf(card.suit) * ranks.size + ranks.indexOf(card.rank)

    private val Rank.label: String
        get() = when (this) {
            Rank.ACE -> "A"
            Rank.JACK -> "J"
            Rank.QUEEN -> "Q"
            Rank.KING -> "K"
            else -> canonicalValue.toString()
        }

    private val Suit.label: String
        get() = when (this) {
            Suit.CLUBS -> "C"
            Suit.DIAMONDS -> "D"
            Suit.HEARTS -> "H"
            Suit.SPADES -> "S"
        }
}

class CardDetectionPostProcessor(
    private val config: DetectionPostProcessingConfig,
) {
    fun process(rawDetections: List<RawCardDetection>): CardDetectionPostProcessingResult {
        val candidates = rawDetections.mapIndexedNotNull { index, raw -> normalize(raw)?.copy(sourceIndex = index) }
            .sortedWith(suppressionOrder)
        val retained = mutableListOf<Candidate>()
        candidates.forEach { candidate ->
            if (retained.none { intersectionOverUnion(it.bounds, candidate.bounds) > config.iouThreshold }) {
                retained += candidate
            }
        }

        val cards = retained.sortedWith(spatialOrder).map { candidate ->
            val warnings = buildSet {
                if (candidate.confidence < config.lowConfidenceThreshold) add(RecognitionWarning.LOW_CONFIDENCE)
                if (candidate.bounds.nearEdge(config.edgeThreshold)) add(RecognitionWarning.NEAR_IMAGE_EDGE)
            }
            RecognizedCard(candidate.card, candidate.bounds, candidate.confidence, warnings)
        }
        val warnings = if (cards.size in config.expectedCardCount) {
            emptySet()
        } else {
            setOf(DetectionSetWarning.UNEXPECTED_CARD_COUNT)
        }
        return CardDetectionPostProcessingResult(cards, warnings)
    }

    private fun normalize(raw: RawCardDetection): Candidate? {
        if (!raw.confidence.isFinite() || raw.confidence < config.confidenceThreshold || raw.confidence > 1f) {
            return null
        }
        if (listOf(raw.left, raw.top, raw.right, raw.bottom).any { !it.isFinite() }) return null
        val card = CardClassIndex.cardAt(raw.classIndex) ?: return null
        val left = raw.left.coerceIn(0f, 1f)
        val top = raw.top.coerceIn(0f, 1f)
        val right = raw.right.coerceIn(0f, 1f)
        val bottom = raw.bottom.coerceIn(0f, 1f)
        if (left >= right || top >= bottom) return null
        return Candidate(card, raw.classIndex, raw.confidence, NormalizedRect(left, top, right, bottom))
    }

    private fun intersectionOverUnion(first: NormalizedRect, second: NormalizedRect): Float {
        val intersectionWidth = max(0f, min(first.right, second.right) - max(first.left, second.left))
        val intersectionHeight = max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
        val intersection = intersectionWidth * intersectionHeight
        val firstArea = (first.right - first.left) * (first.bottom - first.top)
        val secondArea = (second.right - second.left) * (second.bottom - second.top)
        return intersection / (firstArea + secondArea - intersection)
    }

    private fun NormalizedRect.nearEdge(threshold: Float): Boolean =
        left <= threshold || top <= threshold || right >= 1f - threshold || bottom >= 1f - threshold

    private data class Candidate(
        val card: Card.Suited,
        val classIndex: Int,
        val confidence: Float,
        val bounds: NormalizedRect,
        val sourceIndex: Int = 0,
    )

    private companion object {
        // Shared with evaluate.py: confidence desc, canonical class index, L/T/R/B, input index.
        val suppressionOrder = compareByDescending<Candidate> { it.confidence }
            .thenBy { it.classIndex }
            .thenBy { it.bounds.left }
            .thenBy { it.bounds.top }
            .thenBy { it.bounds.right }
            .thenBy { it.bounds.bottom }
            .thenBy { it.sourceIndex }

        val spatialOrder = compareBy<Candidate> { it.bounds.top }
            .thenBy { it.bounds.left }
            .thenBy { it.bounds.bottom }
            .thenBy { it.bounds.right }
            .thenBy { it.classIndex }
            .thenByDescending { it.confidence }
    }
}
