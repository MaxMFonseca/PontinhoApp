package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngine
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionResult
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class RecognitionAssetVerifierTest {
    private val verifier = RecognitionAssetVerifier()
    private val model = "abc".encodeToByteArray()

    @Test
    fun `absent asset pair is unavailable instead of throwing`() {
        assertUnavailable(verifier.verify(null, null))
    }

    @Test
    fun `either incomplete asset pair is unavailable`() {
        assertUnavailable(verifier.verify(model, null))
        assertUnavailable(verifier.verify(null, approvedReleaseJson()))
    }

    @Test
    fun `hash mismatch is unavailable`() {
        assertUnavailable(
            verifier.verify(model, approvedReleaseJson(modelSha256 = "0".repeat(64))),
        )
    }

    @Test
    fun `wrong label order or count is unavailable`() {
        val reversed = CANONICAL_LABELS.reversed()
        assertUnavailable(verifier.verify(model, approvedReleaseJson(labels = reversed)))
        assertUnavailable(verifier.verify(model, approvedReleaseJson(labels = CANONICAL_LABELS.dropLast(1))))
    }

    @Test
    fun `unsupported schema status or model version is unavailable`() {
        assertUnavailable(verifier.verify(model, approvedReleaseJson(schemaVersion = 2)))
        assertUnavailable(verifier.verify(model, approvedReleaseJson(status = "NOT_APPROVED")))
        assertUnavailable(verifier.verify(model, approvedReleaseJson(modelVersion = "latest")))
    }

    @Test
    fun `mismatched input dimensions or quantization contract is unavailable`() {
        assertUnavailable(verifier.verify(model, approvedReleaseJson(inputWidth = 640)))
        assertUnavailable(verifier.verify(model, approvedReleaseJson(inputHeight = 640)))
        assertUnavailable(verifier.verify(model, approvedReleaseJson(quantizationVariant = "float16")))
        assertUnavailable(
            verifier.verify(
                model,
                approvedReleaseJson().decodeToString()
                    .replace(
                        "\"input\":{\"scales\":[0.5],\"zeroPoints\":[128],\"quantizedDimension\":0}",
                        "\"input\":null",
                    )
                    .encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `malformed trailing duplicate and unknown metadata fail closed`() {
        assertUnavailable(verifier.verify(model, "{".encodeToByteArray()))
        assertUnavailable(verifier.verify(model, approvedReleaseJson() + " true".encodeToByteArray()))
        assertUnavailable(
            verifier.verify(
                model,
                approvedReleaseJson().decodeToString()
                    .replace("\"status\":\"APPROVED\"", "\"status\":\"APPROVED\",\"status\":\"APPROVED\"")
                    .encodeToByteArray(),
            ),
        )
        assertUnavailable(
            verifier.verify(
                model,
                approvedReleaseJson().decodeToString().dropLast(1)
                    .plus(",\"unexpected\":true}")
                    .encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `approved pair exposes verified model metadata`() {
        val result = verifier.verify(model, approvedReleaseJson())

        assertTrue(result is RecognitionAvailability.Available)
        result as RecognitionAvailability.Available
        assertEquals("2026.10.15-int8", result.metadata.modelVersion)
        assertEquals(320, result.metadata.inputWidth)
        assertEquals(320, result.metadata.inputHeight)
        assertEquals(0.5f, result.metadata.confidenceThreshold)
        assertEquals(0.4f, result.metadata.iouThreshold)
        assertEquals(CANONICAL_LABELS, result.metadata.labels)
    }

    @Test
    fun `provider accepts an injected fake and constructs it once under concurrency`() {
        val constructions = AtomicInteger()
        val fake = FakeEngine()
        val provider = ProcessLifetimeCardRecognitionEngineProvider {
            constructions.incrementAndGet()
            fake
        }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val futures = List(32) {
            pool.submit<CardRecognitionEngine> {
                start.await()
                provider.get()
            }
        }

        start.countDown()
        futures.forEach { assertSame(fake, it.get(5, TimeUnit.SECONDS)) }
        pool.shutdownNow()
        assertEquals(1, constructions.get())

        provider.close()
        provider.close()
        assertEquals(1, fake.closeCount.get())
    }

    private fun assertUnavailable(value: RecognitionAvailability) {
        assertTrue(value is RecognitionAvailability.Unavailable)
    }

    private fun approvedReleaseJson(
        schemaVersion: Int = 1,
        status: String = "APPROVED",
        modelVersion: String = "2026.10.15-int8",
        modelSha256: String = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        labels: List<String> = CANONICAL_LABELS,
        inputWidth: Int = 320,
        inputHeight: Int = 320,
        quantizationVariant: String = "int8",
    ): ByteArray {
        val quotedLabels = labels.joinToString(",") { "\"$it\"" }
        return """
            {
              "schemaVersion":$schemaVersion,
              "status":"$status",
              "modelVersion":"$modelVersion",
              "modelSha256":"$modelSha256",
              "modelSizeBytes":3,
              "labels":[$quotedLabels],
              "inputWidth":$inputWidth,
              "inputHeight":$inputHeight,
              "quantization":{"variant":"$quantizationVariant","input":{"scales":[0.5],"zeroPoints":[128],"quantizedDimension":0},"nonemptyConstantTensorTypes":["INT8","INT32"],"quantizedConstantCount":2},
              "confidenceThreshold":0.5,
              "iouThreshold":0.4,
              "sourceReportPath":"reports/release.json",
              "sourceReportSha256":"${"1".repeat(64)}",
              "modelContractSha256":"${"2".repeat(64)}",
              "candidateContractSha256":"${"3".repeat(64)}",
              "comparisonSha256":"${"4".repeat(64)}",
              "validationManifestSha256":"${"5".repeat(64)}",
              "thresholdSelectionSha256":"${"6".repeat(64)}",
              "validationReceiptSha256":"${"7".repeat(64)}",
              "sourceAuditReceiptSha256":"${"8".repeat(64)}",
              "sourceManifestSha256":"${"9".repeat(64)}",
              "splitManifestSha256":"${"a".repeat(64)}",
              "trainingInputSha256":"${"b".repeat(64)}",
              "syntheticManifestSha256":null,
              "syntheticReviewSha256":null,
              "syntheticGenerationConfigSha256":null,
              "testManifestSha256":"${"c".repeat(64)}",
              "exactTotalAccuracy":0.96,
              "exactCardMultisetAccuracy":0.95,
              "abstentionRate":0.05,
              "heldOutPhotoCount":200,
              "heldOutDeckCount":10,
              "exportedAt":"2026-10-15T12:00:00Z"
            }
        """.trimIndent().encodeToByteArray()
    }

    private class FakeEngine : CardRecognitionEngine {
        override val availability = RecognitionAvailability.Unavailable
        val closeCount = AtomicInteger()

        override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult = error("not used")

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private companion object {
        val CANONICAL_LABELS = listOf("C", "D", "H", "S").flatMap { suit ->
            listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
                .map { rank -> "$rank$suit" }
        }
    }
}
