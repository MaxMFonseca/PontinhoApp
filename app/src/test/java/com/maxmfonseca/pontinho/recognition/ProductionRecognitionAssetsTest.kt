package com.maxmfonseca.pontinho.recognition

import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.YoloRecognitionMetadata
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductionRecognitionAssetsTest {
    @Test fun `main source set contains the exact owner approved recognition bundle`() {
        val model = requireResource("card_detector.tflite")
        val releaseJson = requireResource("card_recognition.release.json")

        assertEquals(12_838_331, model.size)
        assertEquals(APPROVED_MODEL_SHA256, sha256(model))

        val available = RecognitionAssetVerifier(allowExperimental = false)
            .verify(model, releaseJson) as RecognitionAvailability.Available
        val metadata = available.metadata as YoloRecognitionMetadata
        assertFalse(metadata.debugOnly)
        assertTrue(metadata.productionApproved)
        assertEquals(52, metadata.labels.size)
        assertEquals(10, metadata.maxCards)
    }

    private fun requireResource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Missing production recognition resource: $name"
        }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val APPROVED_MODEL_SHA256 =
            "01034db88037605250fd3d78b49ee791a7711b218305e44ebe4ff3c02e2ad277"
    }
}
