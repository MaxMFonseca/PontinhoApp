package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelBundleMetadata
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TwoStageCardRecognitionLifecycleTest {
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
        val engine = engine(runner, dispatcher = Dispatchers.Default)

        val first = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        assertTrue(runner.firstEntered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            engine.recognize(bitmap())
        }

        assertEquals(1, runner.detectCalls.get())
        assertEquals(1, runner.maxConcurrent.get())
        runner.release.countDown()
        first.await()
        second.await()
        assertEquals(2, runner.detectCalls.get())
        assertEquals(1, runner.maxConcurrent.get())
    }

    @Test
    fun `queued cancellation returns before active native inference finishes`() = runBlocking {
        val runner = BlockingRunner()
        val engine = engine(runner, dispatcher = Dispatchers.Default)
        val first = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        assertTrue(runner.firstEntered.await(5, TimeUnit.SECONDS))
        val queuedStarted = CountDownLatch(1)
        val queued = async(Dispatchers.Default) {
            queuedStarted.countDown()
            engine.recognize(bitmap())
        }
        assertTrue(queuedStarted.await(5, TimeUnit.SECONDS))

        try {
            withTimeout(1_000) {
                queued.cancelAndJoin()
            }
            assertEquals(1, runner.detectCalls.get())
            assertFalse(runner.release.await(0, TimeUnit.MILLISECONDS))
        } finally {
            runner.release.countDown()
            first.await()
        }
    }

    @Test
    fun `close rejects queued inference and waits only for active native call`() = runBlocking {
        val runner = BlockingRunner()
        val engine = engine(runner, dispatcher = Dispatchers.Default)
        val active = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        assertTrue(runner.firstEntered.await(5, TimeUnit.SECONDS))
        val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runCatching { engine.recognize(bitmap()) }
        }
        val closeEntered = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closer = Thread {
            closeEntered.countDown()
            engine.close()
            closeReturned.countDown()
        }.apply { start() }
        assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
        assertFalse(closeReturned.await(250, TimeUnit.MILLISECONDS))

        runner.release.countDown()
        active.await()
        closer.join(5_000)

        assertTrue(closeReturned.await(0, TimeUnit.MILLISECONDS))
        assertTrue(queued.await().exceptionOrNull() is CardRecognitionException.Closed)
        assertEquals(1, runner.detectCalls.get())
        assertEquals(1, runner.closeCalls.get())
    }

    @Test
    fun `concurrent close calls close runner once and all return`() {
        val runner = FakeRunner()
        val engine = engine(runner)
        val callers = 8
        val start = CyclicBarrier(callers)
        val pool = Executors.newFixedThreadPool(callers)
        val futures = List(callers) {
            pool.submit {
                start.await(5, TimeUnit.SECONDS)
                engine.close()
            }
        }

        futures.forEach { it.get(5, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertEquals(1, runner.closeCalls.get())
        assertThrows(CardRecognitionException.Closed::class.java) {
            runBlocking { engine.recognize(bitmap()) }
        }
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
    fun `native linkage failures become privacy-safe typed failures`() {
        val engine = engine(FakeRunner(failure = UnsatisfiedLinkError("/private/lib.so")))

        val error = assertThrows(CardRecognitionException.InferenceFailed::class.java) {
            runBlocking { engine.recognize(bitmap()) }
        }

        assertEquals("Card recognition failed", error.message)
        assertNull(error.cause)
    }

    @Test
    fun `fatal VM errors from inference are not swallowed`() {
        val fatal = OutOfMemoryError("native allocation")
        val engine = engine(FakeRunner(failure = fatal))

        val thrown = assertThrows(OutOfMemoryError::class.java) {
            runBlocking { engine.recognize(bitmap()) }
        }

        assertTrue(thrown === fatal)
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
            delegate = { _, _, _ -> error("native model path") },
        )

        val engine = factory.create(model, BundleFixture.classifier, BundleFixture.bytes())

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

    @Test
    fun `gated factory fails closed when native linkage is unavailable`() {
        val model = "abc".encodeToByteArray()
        val factory = AssetGatedCardRecognitionEngineFactory(
            verifier = RecognitionAssetVerifier(),
            delegate = { _, _, _ -> throw NoClassDefFoundError("private native detail") },
        )

        val engine = factory.create(model, BundleFixture.classifier, BundleFixture.bytes())

        assertEquals(
            RecognitionAvailability.Unavailable(
                com.maxmfonseca.pontinho.domain.recognition.RecognitionUnavailableReason.ENGINE_NOT_INSTALLED,
            ),
            engine.availability,
        )
    }

    @Test
    fun `gated factory does not swallow fatal VM errors`() {
        val fatal = OutOfMemoryError("native allocation")
        val factory = AssetGatedCardRecognitionEngineFactory(
            verifier = RecognitionAssetVerifier(),
            delegate = { _, _, _ -> throw fatal },
        )

        val thrown = assertThrows(OutOfMemoryError::class.java) {
            factory.create("abc".encodeToByteArray(), BundleFixture.classifier, BundleFixture.bytes())
        }

        assertTrue(thrown === fatal)
    }

    private fun engine(
        runner: CardDetectorRunner,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        nanoTime: () -> Long = { 0L },
    ) = TwoStageCardRecognitionEngine(
        detector = runner,
        classifier = object : CardClassifierRunner {
            override fun classify(bitmap: Bitmap) = RawCardClassification(FloatArray(52).apply { this[0] = 1f })
            override fun close() = Unit
        },
        metadata = metadata(),
        dispatcher = dispatcher,
        nanoTime = nanoTime,
    )

    private fun metadata() = (RecognitionAssetVerifier().verify(BundleFixture.detector, BundleFixture.classifier, BundleFixture.bytes())
        as RecognitionAvailability.Available).metadata as RecognitionModelBundleMetadata

    private fun bitmap() = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)



    private open class FakeRunner(
        private val detections: List<RawCardBox> = listOf(RawCardBox(.9f, .1f, .1f, .4f, .4f)),
        private val failure: Throwable? = null,
    ) : CardDetectorRunner {
        val detectCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val detectThreads = mutableListOf<Thread>()

        override fun detect(bitmap: Bitmap): List<RawCardBox> {
            detectCalls.incrementAndGet()
            detectThreads += Thread.currentThread()
            failure?.let { throw it }
            return detections
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private class BlockingRunner : CardDetectorRunner {
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val detectCalls = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val closeCalls = AtomicInteger()
        private val concurrent = AtomicInteger()

        override fun detect(bitmap: Bitmap): List<RawCardBox> {
            detectCalls.incrementAndGet()
            val active = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, active) }
            firstEntered.countDown()
            release.await(5, TimeUnit.SECONDS)
            concurrent.decrementAndGet()
            return listOf(RawCardBox(.9f, .1f, .1f, .4f, .4f))
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

}
