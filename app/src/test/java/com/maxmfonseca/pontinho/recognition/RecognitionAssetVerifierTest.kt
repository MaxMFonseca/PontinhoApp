package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngine
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionResult
import com.maxmfonseca.pontinho.domain.recognition.NormalizedRect
import com.maxmfonseca.pontinho.domain.recognition.RecognizedCard
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelMetadata
import com.maxmfonseca.pontinho.domain.recognition.RecognitionUnavailableReason
import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import com.google.mediapipe.tasks.core.logging.TasksStatsLoggerFactory
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
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
        assertEquals(
            RecognitionAvailability.Unavailable(RecognitionUnavailableReason.MISSING_ASSETS),
            verifier.verify(null, null),
        )
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
        assertUnavailable(verifier.verify(model, approvedReleaseJson(modelVersion = "unsafe/version")))
    }

    @Test
    fun `mismatched input dimensions or quantization contract is unavailable`() {
        assertUnavailable(verifier.verify(model, approvedReleaseJson(inputWidth = 640)))
        assertUnavailable(verifier.verify(model, approvedReleaseJson(inputHeight = 640)))
        assertUnavailable(verifier.verify(model, approvedReleaseJson(quantizationVariant = "fp32")))
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
    fun `experimental assets are rejected by default and accepted only with explicit debug opt in`() {
        val release = experimentalReleaseJson()

        assertUnavailable(verifier.verify(model, release))
        val result = RecognitionAssetVerifier(allowExperimental = true).verify(model, release)

        assertTrue(result is RecognitionAvailability.Available)
        result as RecognitionAvailability.Available
        assertEquals("2026.10.15-beta", result.metadata.modelVersion)
        assertEquals(CANONICAL_LABELS, result.metadata.labels)
    }

    @Test
    fun `debug opt in does not weaken experimental manifest validation`() {
        val debugVerifier = RecognitionAssetVerifier(allowExperimental = true)

        assertUnavailable(
            debugVerifier.verify(
                model,
                experimentalReleaseJson().decodeToString()
                    .replace("\"trainingPhotoCount\":964", "\"trainingPhotoCount\":0")
                    .encodeToByteArray(),
            ),
        )
        assertUnavailable(
            debugVerifier.verify(
                model,
                experimentalReleaseJson().decodeToString()
                    .replace("\"sourceLicense\":\"CC-BY-4.0\"", "\"sourceLicense\":\"unknown\"")
                    .encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `float16 release emitted by the model pipeline is available`() {
        val result = verifier.verify(
            model,
            releaseFixture("card_detector.float16.release.json"),
        )

        assertTrue(result is RecognitionAvailability.Available)
        result as RecognitionAvailability.Available
        assertEquals("cards.release_candidate-1", result.metadata.modelVersion)
    }

    @Test
    fun `int8 release emitted by the model pipeline is available`() {
        val result = verifier.verify(
            model,
            releaseFixture("card_detector.int8.release.json"),
        )

        assertTrue(result is RecognitionAvailability.Available)
        result as RecognitionAvailability.Available
        assertEquals("2026.10.15-int8", result.metadata.modelVersion)
    }

    @Test
    fun `unsafe model version and non UTC timestamp remain unavailable`() {
        assertUnavailable(verifier.verify(model, approvedReleaseJson(modelVersion = "unsafe/version")))
        assertUnavailable(
            verifier.verify(model, approvedReleaseJson(exportedAt = "2026-10-15T09:00:00-03:00")),
        )
    }

    @Test
    fun `real MediaPipe logger factory exercises every event path through the offline transport`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val logger = TasksStatsLoggerFactory.create(context, "ObjectDetector", "IMAGE")

        logger.logSessionStart()
        logger.recordCpuInputArrival(1L)
        logger.recordInvocationEnd(1L)
        logger.logInitError()
        logger.logSessionEnd()
    }

    @Test
    fun `real ObjectDetector reaches model validation through the offline logger linkage`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val invalidModel = ByteBuffer.allocateDirect(8).apply {
            putLong(0x504f4e54494e484fL)
            rewind()
        }

        val error = runCatching {
            ObjectDetector.createFromBuffer(context, invalidModel)
        }.exceptionOrNull()

        assertTrue(error != null)
        assertTrue(generateSequence(error) { it.cause }.none { it is NoClassDefFoundError })
    }

    @Test
    fun `provider rejects every get after close`() {
        val provider = ProcessLifetimeCardRecognitionEngineProvider { FakeEngine() }
        provider.get()
        provider.close()

        assertThrows(IllegalStateException::class.java) { provider.get() }
    }

    @Test
    fun `provider close is visible to concurrent callers and closes one engine once`() {
        val fake = FakeEngine()
        val provider = ProcessLifetimeCardRecognitionEngineProvider { fake }
        provider.get()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val close = pool.submit {
            start.await()
            provider.close()
        }
        val racingGets = List(24) {
            pool.submit {
                start.await()
                runCatching { provider.get() }
            }
        }

        start.countDown()
        close.get(5, TimeUnit.SECONDS)
        racingGets.forEach { it.get(5, TimeUnit.SECONDS) }
        repeat(24) {
            assertThrows(IllegalStateException::class.java) { provider.get() }
        }
        provider.close()
        pool.shutdownNow()
        assertEquals(1, fake.closeCount.get())
    }

    @Test
    fun `verified assets stay unavailable until a real engine factory is installed`() {
        val factory = AssetGatedCardRecognitionEngineFactory(
            verifier = verifier,
            delegate = null,
        )

        val engine = factory.create(model, approvedReleaseJson())

        assertEquals(
            RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED),
            engine.availability,
        )
    }

    @Test
    fun `verified assets are handed to the installed real engine factory`() {
        val fake = FakeEngine()
        val factory = AssetGatedCardRecognitionEngineFactory(
            verifier = verifier,
            delegate = { bytes, metadata ->
                assertEquals("abc", bytes.decodeToString())
                assertEquals("2026.10.15-int8", metadata.modelVersion)
                fake
            },
        )

        assertSame(fake, factory.create(model, approvedReleaseJson()))
    }

    @Test
    fun `recognition value objects reject malformed geometry confidence and metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedRect(left = 0.5f, top = 0f, right = 0.5f, bottom = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecognizedCard(
                card = Card.Suited(Rank.ACE, Suit.CLUBS),
                bounds = NormalizedRect(0f, 0f, 1f, 1f),
                confidence = Float.NaN,
                warnings = emptySet(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CardRecognitionResult(emptyList(), durationMillis = -1, modelVersion = "model")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionModelMetadata(
                modelVersion = "model",
                modelSha256 = "a".repeat(64),
                labels = CANONICAL_LABELS,
                inputWidth = 320,
                inputHeight = 320,
                confidenceThreshold = 1.1f,
                iouThreshold = 0.5f,
            )
        }
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

    private fun releaseFixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "Missing fixture $name" }
            .use { it.readBytes() }

    private fun approvedReleaseJson(
        schemaVersion: Int = 1,
        status: String = "APPROVED",
        modelVersion: String = "2026.10.15-int8",
        modelSha256: String = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        labels: List<String> = CANONICAL_LABELS,
        inputWidth: Int = 320,
        inputHeight: Int = 320,
        quantizationVariant: String = "int8",
        exportedAt: String = "2026-10-15T12:00:00Z",
    ): ByteArray {
        val quotedLabels = labels.joinToString(",") { "\"$it\"" }
        val quantization = if (quantizationVariant == "float16") {
            "{\"variant\":\"float16\",\"input\":null,\"nonemptyConstantTensorTypes\":[\"FLOAT16\",\"FLOAT32\"],\"quantizedConstantCount\":0}"
        } else {
            "{\"variant\":\"$quantizationVariant\",\"input\":{\"scales\":[0.5],\"zeroPoints\":[128],\"quantizedDimension\":0},\"nonemptyConstantTensorTypes\":[\"FLOAT32\",\"INT32\",\"INT8\"],\"quantizedConstantCount\":2}"
        }
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
              "quantization":$quantization,
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
              "exportedAt":"$exportedAt"
            }
        """.trimIndent().encodeToByteArray()
    }

    private fun experimentalReleaseJson(): ByteArray {
        val quotedLabels = CANONICAL_LABELS.joinToString(",") { "\"$it\"" }
        return """
            {
              "schemaVersion":1,
              "status":"EXPERIMENTAL",
              "modelVersion":"2026.10.15-beta",
              "modelSha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
              "modelSizeBytes":3,
              "labels":[$quotedLabels],
              "inputWidth":320,
              "inputHeight":320,
              "quantization":{"variant":"float16","input":null,"nonemptyConstantTensorTypes":["FLOAT16","FLOAT32"],"quantizedConstantCount":0},
              "confidenceThreshold":0.5,
              "iouThreshold":0.4,
              "trainingPhotoCount":964,
              "validationPhotoCount":193,
              "sourceLicense":"CC-BY-4.0",
              "exportedAt":"2026-10-15T12:00:00Z"
            }
        """.trimIndent().encodeToByteArray()
    }

    private class FakeEngine : CardRecognitionEngine {
        override val availability =
            RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED)
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
