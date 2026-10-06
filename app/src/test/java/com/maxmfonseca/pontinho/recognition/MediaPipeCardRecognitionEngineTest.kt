package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelMetadata
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaPipeCardRecognitionEngineTest {
    @Test
    fun `maps MediaPipe zero-based category indices through the shared postprocessor`() = runBlocking {
        val runner = FakeRunner(
            detections = listOf(
                detection(classIndex = 13, left = 0.5f),
                detection(classIndex = 0, left = 0.1f),
            ),
        )
        val engine = engine(runner)

        val result = engine.recognize(bitmap())

        assertEquals(
            listOf(
                Card.Suited(Rank.ACE, Suit.CLUBS),
                Card.Suited(Rank.ACE, Suit.DIAMONDS),
            ),
            result.candidates.map { it.card },
        )
        assertEquals("release-1", result.modelVersion)
    }

    @Test
    fun `uses verified thresholds and deterministic elapsed time`() = runBlocking {
        val runner = FakeRunner(
            detections = listOf(
                detection(classIndex = 0, confidence = 0.59f, left = 0.1f),
                detection(classIndex = 1, confidence = 0.6f, left = 0.5f),
            ),
        )
        val times = ArrayDeque(listOf(2_000_000_000L, 2_123_999_999L))
        val engine = engine(runner, nanoTime = times::removeFirst)

        val result = engine.recognize(bitmap())

        assertEquals(listOf(Card.Suited(Rank.TWO, Suit.CLUBS)), result.candidates.map { it.card })
        assertEquals(123L, result.durationMillis)
    }

    @Test
    fun `runs native inference off the caller thread`() = runBlocking {
        val callerThread = Thread.currentThread()
        val runner = FakeRunner()
        val engine = engine(runner, dispatcher = Dispatchers.IO)

        engine.recognize(bitmap())

        assertTrue(runner.detectThreads.single() !== callerThread)
    }

    @Test
    fun `serializes concurrent inference`() = runBlocking {
        val runner = BlockingRunner()
        val engine = engine(runner, dispatcher = Dispatchers.IO)

        val first = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        assertTrue(runner.firstEntered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        Thread.sleep(100)

        assertEquals(1, runner.detectCalls.get())
        assertEquals(1, runner.maxConcurrent.get())
        runner.release.countDown()
        first.await()
        second.await()
        assertEquals(2, runner.detectCalls.get())
        assertEquals(1, runner.maxConcurrent.get())
    }

    @Test
    fun `cancellation before inference never calls native runner`() = runTest {
        val runner = FakeRunner()
        val engine = engine(runner, dispatcher = StandardTestDispatcher(testScheduler))

        val job = async { engine.recognize(bitmap()) }
        job.cancelAndJoin()

        assertEquals(0, runner.detectCalls.get())
    }

    @Test
    fun `cancellation during synchronous native call is rethrown after it returns`() = runBlocking {
        val runner = BlockingRunner()
        val engine = engine(runner, dispatcher = Dispatchers.IO)
        val job = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        assertTrue(runner.firstEntered.await(5, TimeUnit.SECONDS))

        job.cancel()
        runner.release.countDown()

        assertThrows(CancellationException::class.java) { runBlocking { job.await() } }
        assertEquals(1, runner.detectCalls.get())
    }

    @Test
    fun `native failures become privacy-safe typed failures`() {
        val secret = "/private/cache/scan-123.jpg"
        val engine = engine(FakeRunner(failure = IllegalStateException(secret)))

        val error = assertThrows(CardRecognitionException.InferenceFailed::class.java) {
            runBlocking { engine.recognize(bitmap()) }
        }

        assertFalse(error.message.orEmpty().contains(secret))
        assertEquals("Card recognition failed", error.message)
        assertNull(error.cause)
    }

    @Test
    fun `close owns runner exactly once and rejects recognition afterwards`() {
        val runner = FakeRunner()
        val engine = engine(runner)

        engine.close()
        engine.close()

        assertEquals(1, runner.closeCalls.get())
        assertThrows(CardRecognitionException.Closed::class.java) {
            runBlocking { engine.recognize(bitmap()) }
        }
        assertEquals(0, runner.detectCalls.get())
    }

    @Test
    fun `engine never recycles caller-owned bitmap`() = runBlocking {
        val bitmap = bitmap()
        val engine = engine(FakeRunner())

        engine.recognize(bitmap)
        engine.close()

        assertFalse(bitmap.isRecycled)
    }

    @Test
    fun `gated factory fails closed when verified runner creation fails`() {
        val model = "abc".encodeToByteArray()
        val factory = AssetGatedCardRecognitionEngineFactory(
            verifier = RecognitionAssetVerifier(),
            delegate = MediaPipeCardRecognitionEngineFactory(
                runnerFactory = MediaPipeDetectorRunnerFactory { _, _ -> error("native model path") },
            ),
        )

        val engine = factory.create(model, approvedReleaseJson())

        assertEquals(
            RecognitionAvailability.Unavailable(
                com.maxmfonseca.pontinho.domain.recognition.RecognitionUnavailableReason.ENGINE_NOT_INSTALLED,
            ),
            engine.availability,
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.recognize(bitmap()) }
        }
    }

    private fun engine(
        runner: MediaPipeDetectorRunner,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        nanoTime: () -> Long = { 0L },
    ) = MediaPipeCardRecognitionEngine(
        runner = runner,
        metadata = metadata(),
        dispatcher = dispatcher,
        nanoTime = nanoTime,
    )

    private fun metadata() = RecognitionModelMetadata(
        modelVersion = "release-1",
        modelSha256 = "a".repeat(64),
        labels = CardClassIndex.labels,
        inputWidth = 320,
        inputHeight = 320,
        confidenceThreshold = 0.6f,
        iouThreshold = 0.4f,
    )

    private fun bitmap() = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

    private fun detection(
        classIndex: Int,
        confidence: Float = 0.9f,
        left: Float,
    ) = RawCardDetection(classIndex, confidence, left, 0.1f, left + 0.2f, 0.4f)

    private open class FakeRunner(
        private val detections: List<RawCardDetection> = emptyList(),
        private val failure: Throwable? = null,
    ) : MediaPipeDetectorRunner {
        val detectCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val detectThreads = mutableListOf<Thread>()

        override fun detect(bitmap: Bitmap): List<RawCardDetection> {
            detectCalls.incrementAndGet()
            detectThreads += Thread.currentThread()
            failure?.let { throw it }
            return detections
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private class BlockingRunner : MediaPipeDetectorRunner {
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val detectCalls = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val concurrent = AtomicInteger()

        override fun detect(bitmap: Bitmap): List<RawCardDetection> {
            detectCalls.incrementAndGet()
            val active = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, active) }
            firstEntered.countDown()
            release.await(5, TimeUnit.SECONDS)
            concurrent.decrementAndGet()
            return emptyList()
        }

        override fun close() = Unit
    }

    private fun approvedReleaseJson(): ByteArray {
        val labels = CardClassIndex.labels.joinToString(",") { "\"$it\"" }
        return """
            {
              "schemaVersion":1,"status":"APPROVED","modelVersion":"release-1",
              "modelSha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","modelSizeBytes":3,
              "labels":[$labels],"inputWidth":320,"inputHeight":320,
              "quantization":{"variant":"int8","input":{"scales":[0.5],"zeroPoints":[128],"quantizedDimension":0},"nonemptyConstantTensorTypes":["INT32","INT8"],"quantizedConstantCount":2},
              "confidenceThreshold":0.6,"iouThreshold":0.4,"sourceReportPath":"reports/release.json",
              "sourceReportSha256":"${"1".repeat(64)}","modelContractSha256":"${"2".repeat(64)}","candidateContractSha256":"${"3".repeat(64)}",
              "comparisonSha256":"${"4".repeat(64)}","validationManifestSha256":"${"5".repeat(64)}","thresholdSelectionSha256":"${"6".repeat(64)}",
              "validationReceiptSha256":"${"7".repeat(64)}","sourceAuditReceiptSha256":"${"8".repeat(64)}","sourceManifestSha256":"${"9".repeat(64)}",
              "splitManifestSha256":"${"a".repeat(64)}","trainingInputSha256":"${"b".repeat(64)}","syntheticManifestSha256":null,
              "syntheticReviewSha256":null,"syntheticGenerationConfigSha256":null,"testManifestSha256":"${"c".repeat(64)}",
              "exactTotalAccuracy":0.96,"exactCardMultisetAccuracy":0.95,"abstentionRate":0.05,"heldOutPhotoCount":200,
              "heldOutDeckCount":10,"exportedAt":"2026-10-15T12:00:00Z"
            }
        """.trimIndent().encodeToByteArray()
    }
}
