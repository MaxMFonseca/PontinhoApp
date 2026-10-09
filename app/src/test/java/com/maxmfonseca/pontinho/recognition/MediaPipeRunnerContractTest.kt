package com.maxmfonseca.pontinho.recognition

import com.google.mediapipe.tasks.components.containers.Category
import org.junit.Assert.*
import org.junit.Test

class MediaPipeRunnerContractTest {
    @Test fun `native score ranking maps back to canonical zero based indices`() {
        val categories = (0..51).reversed().map { Category.create(if (it == 13) .9f else .1f / 51, it, "", "") }
        val scores = canonicalClassification(categories).probabilities
        assertEquals(52, scores.size)
        assertEquals(.9f, scores[13])
        assertEquals(.1f / 51, scores[0])
    }

    @Test fun `missing duplicated and unknown native class indices fail closed`() {
        val categories = (0..51).map { Category.create(1f / 52, it, "", "") }
        for (invalid in listOf(categories.dropLast(1), categories.dropLast(1) + categories.first(),
            categories.dropLast(1) + Category.create(.1f, 52, "", ""))) {
            assertThrows(IllegalArgumentException::class.java) { canonicalClassification(invalid) }
        }
    }
}
