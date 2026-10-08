package com.maxmfonseca.pontinho.recognition

import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import com.maxmfonseca.pontinho.domain.recognition.RecognitionWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDetectionPostProcessorTest {
    @Test fun `shared evaluator fixtures retain the same equal confidence detection`() {
        val rows = checkNotNull(javaClass.classLoader!!.getResourceAsStream("nms-ties.csv"))
            .bufferedReader().use { it.readLines() }.drop(1).map { it.split(',') }
        rows.groupBy { it[0] }.forEach { (name, group) ->
            fun detection(row: List<String>) = RawCardDetection(row[2].toInt(), row[4].toFloat(),
                row[5].toFloat(), row[6].toFloat(), row[7].toFloat(), row[8].toFloat())
            val expected = group.single { it[9] == "true" }
            for (input in listOf(group, group.reversed())) {
                val kept = processor.process(input.map(::detection)).cards.single()
                assertEquals(name, expected[3], CardClassIndex.labels[CardClassIndex.indexOf(kept.card)])
                assertEquals(name, listOf(expected[5], expected[6], expected[7], expected[8]).map(String::toFloat),
                    listOf(kept.bounds.left, kept.bounds.top, kept.bounds.right, kept.bounds.bottom))
            }
        }
    }

    private val processor = CardDetectionPostProcessor(
        DetectionPostProcessingConfig(
            confidenceThreshold = 0.50f,
            lowConfidenceThreshold = 0.60f,
            iouThreshold = 0.50f,
            edgeThreshold = 0.05f,
            expectedCardCount = 1..15,
        ),
    )

    @Test
    fun `maps the exact verifier label order and filters invalid detections`() {
        val result = processor.process(
            listOf(
                raw(0, 0.9f, 0.1f, 0.1f, 0.2f, 0.2f),
                raw(13, 0.8f, 0.3f, 0.1f, 0.4f, 0.2f),
                raw(51, 0.7f, 0.5f, 0.1f, 0.6f, 0.2f),
                raw(52, 0.9f, 0.1f, 0.3f, 0.2f, 0.4f),
                raw(1, 0.49f, 0.2f, 0.3f, 0.3f, 0.4f),
                raw(1, Float.NaN, 0.2f, 0.3f, 0.3f, 0.4f),
                raw(1, 0.9f, Float.NaN, 0.3f, 0.3f, 0.4f),
                raw(1, 0.9f, 0.2f, 0.3f, 0.2f, 0.4f),
            ),
        )

        assertEquals(
            listOf(
                Rank.ACE to Suit.CLUBS,
                Rank.ACE to Suit.DIAMONDS,
                Rank.KING to Suit.SPADES,
            ),
            result.cards.map { it.card.rank to it.card.suit },
        )
    }

    @Test
    fun `clips boxes rejects zero clipped area and assigns threshold and edge warnings`() {
        val result = processor.process(
            listOf(
                raw(1, 0.55f, -0.2f, 0.02f, 0.20f, 0.30f),
                raw(2, 0.9f, -0.2f, 0.2f, 0f, 0.4f),
            ),
        )

        assertEquals(1, result.cards.size)
        assertEquals(0f, result.cards.single().bounds.left)
        assertEquals(setOf(RecognitionWarning.LOW_CONFIDENCE, RecognitionWarning.NEAR_IMAGE_EDGE), result.cards.single().warnings)
    }

    @Test
    fun `NMS removes duplicate detector boxes but retains spatially distinct same class cards`() {
        val result = processor.process(
            listOf(
                raw(10, 0.80f, 0.10f, 0.10f, 0.40f, 0.50f),
                raw(11, 0.95f, 0.11f, 0.11f, 0.41f, 0.51f),
                raw(11, 0.70f, 0.60f, 0.10f, 0.90f, 0.50f),
            ),
        )

        assertEquals(2, result.cards.size)
        assertEquals(listOf(11, 11), result.cards.map { CardClassIndex.indexOf(it.card) })
        assertEquals(listOf(0.60f, 0.11f), result.cards.map { it.bounds.left })
    }

    @Test
    fun `NMS retains a detection exactly at the configured IoU threshold`() {
        val result = processor.process(
            listOf(
                raw(0, 0.9f, 0f, 0f, 1f, 1f),
                raw(1, 0.8f, 0f, 0f, 1f, 0.5f),
            ),
        )

        assertEquals(2, result.cards.size)
        assertEquals(setOf(0, 1), result.cards.map { CardClassIndex.indexOf(it.card) }.toSet())
    }

    @Test
    fun `output order and confidence tie breakers are stable regardless of detector order`() {
        val detections = listOf(
            raw(3, 0.7f, 0.5f, 0.5f, 0.6f, 0.6f),
            raw(2, 0.8f, 0.1f, 0.5f, 0.2f, 0.6f),
            raw(1, 0.9f, 0.5f, 0.1f, 0.6f, 0.2f),
            raw(0, 0.9f, 0.1f, 0.1f, 0.2f, 0.2f),
        )

        val forward = processor.process(detections).cards
        val reverse = processor.process(detections.reversed()).cards

        assertEquals(listOf(0, 1, 2, 3), forward.map { CardClassIndex.indexOf(it.card) })
        assertEquals(forward, reverse)
    }

    @Test
    fun `count outside expected range is reported without discarding cards`() {
        val empty = processor.process(emptyList())
        assertEquals(setOf(DetectionSetWarning.UNEXPECTED_CARD_COUNT), empty.warnings)

        val sixteen = processor.process((0 until 16).map { index ->
            val row = index / 8
            val column = index % 8
            raw(index, 0.9f, column * 0.11f, row * 0.4f, column * 0.11f + 0.08f, row * 0.4f + 0.2f)
        })
        assertEquals(16, sixteen.cards.size)
        assertEquals(setOf(DetectionSetWarning.UNEXPECTED_CARD_COUNT), sixteen.warnings)
    }

    @Test
    fun `configuration rejects non finite and inconsistent thresholds`() {
        assertThrows(IllegalArgumentException::class.java) {
            DetectionPostProcessingConfig(Float.NaN, 0.6f, 0.5f, 0.05f, 1..15)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DetectionPostProcessingConfig(0.7f, 0.6f, 0.5f, 0.05f, 1..15)
        }
        DetectionPostProcessingConfig(0.5f, 0.6f, 0f, 0.05f, 1..15)
        DetectionPostProcessingConfig(0.5f, 0.6f, 1f, 0.05f, 1..15)
        assertThrows(IllegalArgumentException::class.java) {
            DetectionPostProcessingConfig(0.5f, 0.6f, -0.01f, 0.05f, 1..15)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DetectionPostProcessingConfig(0.5f, 0.6f, 0.5f, -0.01f, 1..15)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DetectionPostProcessingConfig(0.5f, 0.6f, 0.5f, 0.05f, 2..1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DetectionPostProcessingConfig(0.5f, 0.6f, 0.5f, 0.05f, 1..53)
        }
    }

    @Test
    fun `NMS endpoint thresholds have deterministic frozen evaluator behavior`() {
        val overlapping = listOf(
            raw(0, 0.9f, 0f, 0f, 1f, 1f),
            raw(1, 0.8f, 0f, 0f, 1f, 0.9f),
        )
        val zeroThreshold = CardDetectionPostProcessor(
            DetectionPostProcessingConfig(0.5f, 0.6f, 0f, 0.05f, 1..15),
        )
        val oneThreshold = CardDetectionPostProcessor(
            DetectionPostProcessingConfig(0.5f, 0.6f, 1f, 0.05f, 1..15),
        )

        assertEquals(1, zeroThreshold.process(overlapping).cards.size)
        assertEquals(2, oneThreshold.process(overlapping).cards.size)
    }

    @Test
    fun `canonical class labels are exact and shared with release verification`() {
        assertEquals(52, CardClassIndex.labels.size)
        assertEquals("AC", CardClassIndex.labels.first())
        assertEquals("KD", CardClassIndex.labels[25])
        assertEquals("KS", CardClassIndex.labels.last())
        assertTrue(CardClassIndex.labels.toSet().size == 52)
    }

    private fun raw(
        classIndex: Int,
        confidence: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = RawCardDetection(classIndex, confidence, left, top, right, bottom)
}
