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
class RecognitionInfrastructureTest {
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
