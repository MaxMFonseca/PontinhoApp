package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.YoloRecognitionMetadata
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
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SingleStageCardRecognitionLifecycleTest {
    @Test
    fun `runs inference off caller thread and serializes concurrent calls`() = runBlocking {
        val caller = Thread.currentThread()
        val runner = BlockingRunner()
        val engine = engine(runner, Dispatchers.IO)

        val first = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        if (!runner.entered.await(15, TimeUnit.SECONDS)) {
            runner.release.countDown()
            first.await()
            fail("inference did not enter runner")
        }
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { engine.recognize(bitmap()) }
        assertEquals(1, runner.runs.get())
        assertEquals(1, runner.maxConcurrent.get())
        runner.release.countDown()
        first.await()
        second.await()

        assertTrue(runner.threads.all { it !== caller })
        assertEquals(2, runner.runs.get())
        assertEquals(1, runner.maxConcurrent.get())
    }

    @Test
    fun `queued cancellation returns before active inference finishes`() = runBlocking {
        val runner = BlockingRunner()
        val engine = engine(runner, Dispatchers.Default)
        val first = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        if (!runner.entered.await(15, TimeUnit.SECONDS)) {
            runner.release.countDown()
            first.await()
            fail("inference did not enter runner")
        }
        val queued = async { engine.recognize(bitmap()) }

        withTimeout(1_000) { queued.cancelAndJoin() }
        assertEquals(1, runner.runs.get())
        assertFalse(runner.release.await(0, TimeUnit.MILLISECONDS))
        runner.release.countDown()
        first.await()
        Unit
    }

    @Test
    fun `close rejects queued inference and waits for admitted inference`() = runBlocking {
        val runner = BlockingRunner()
        val engine = engine(runner, Dispatchers.Default)
        val active = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        if (!runner.entered.await(15, TimeUnit.SECONDS)) {
            runner.release.countDown()
            active.await()
            fail("inference did not enter runner")
        }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { engine.recognize(bitmap()) } }
        val closed = CountDownLatch(1)
        val closer = Thread { engine.close(); closed.countDown() }.apply { start() }

        assertFalse(closed.await(250, TimeUnit.MILLISECONDS))
        runner.release.countDown()
        active.await()
        closer.join(5_000)

        assertTrue(closed.await(0, TimeUnit.MILLISECONDS))
        assertTrue(queued.await().exceptionOrNull() is CardRecognitionException.Closed)
        assertEquals(1, runner.runs.get())
        assertEquals(1, runner.closes.get())
    }

    @Test
    fun `concurrent and repeated close owns runner once`() {
        val runner = FakeRunner()
        val engine = engine(runner)
        val callers = 8
        val barrier = CyclicBarrier(callers)
        val pool = Executors.newFixedThreadPool(callers)
        val futures = List(callers) { pool.submit { barrier.await(5, TimeUnit.SECONDS); engine.close() } }

        futures.forEach { it.get(5, TimeUnit.SECONDS) }
        engine.close()
        pool.shutdownNow()

        assertEquals(1, runner.closes.get())
        assertThrows(CardRecognitionException.Closed::class.java) {
            runBlocking { engine.recognize(bitmap()) }
        }
    }

    @Test
    fun `cancellation before admission never invokes runner`() = runTest {
        val runner = FakeRunner()
        val engine = engine(runner, StandardTestDispatcher(testScheduler))

        val job = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        job.cancelAndJoin()

        assertEquals(0, runner.runs.get())
    }

    @Test
    fun `cancellation during synchronous inference is rethrown after native return`() = runBlocking {
        val runner = BlockingRunner()
        val engine = engine(runner, Dispatchers.IO)
        val job = async(Dispatchers.Default) { engine.recognize(bitmap()) }
        if (!runner.entered.await(15, TimeUnit.SECONDS)) {
            runner.release.countDown()
            job.await()
            fail("inference did not enter runner")
        }

        job.cancel()
        runner.release.countDown()

        assertThrows(CancellationException::class.java) { runBlocking { job.await() } }
        assertEquals(1, runner.runs.get())
    }

    @Test
    fun `ordinary and linkage failures are privacy-safe while fatal VM errors escape`() {
        listOf(IllegalStateException("/private/scan.jpg"), UnsatisfiedLinkError("/private/lib.so")).forEach { failure ->
            val error = assertThrows(CardRecognitionException.InferenceFailed::class.java) {
                runBlocking { engine(FakeRunner(failure)).recognize(bitmap()) }
            }
            assertEquals("Card recognition failed", error.message)
            assertNull(error.cause)
        }
        val fatal = OutOfMemoryError("native allocation")
        assertTrue(assertThrows(OutOfMemoryError::class.java) {
            runBlocking { engine(FakeRunner(fatal)).recognize(bitmap()) }
        } === fatal)
    }

    @Test
    fun `engine never recycles caller-owned bitmap`() = runBlocking {
        val bitmap = bitmap()
        val engine = engine(FakeRunner())

        engine.recognize(bitmap)
        engine.close()

        assertFalse(bitmap.isRecycled)
    }

    private fun engine(
        runner: YoloRunner,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ) = SingleStageCardRecognitionEngine(
        runner = runner,
        metadata = metadata(),
        dispatcher = dispatcher,
        prepare = { prepared() },
    )

    private fun prepared() = PreparedYoloImage(
        FloatArray(0),
        intArrayOf(1, 640, 640, 3),
        LetterboxGeometry.forSource(100, 100),
    )

    private fun metadata() = (RecognitionAssetVerifier(allowExperimental = true)
        .verify(YoloBundleFixture.model, YoloBundleFixture.bytes()) as RecognitionAvailability.Available)
        .metadata as YoloRecognitionMetadata

    private fun bitmap() = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

    private fun validOutput(): YoloTensorOutput {
        val values = FloatArray(56 * 8400)
        values[0] = .2f
        values[8400] = .3f
        values[2 * 8400] = .2f
        values[3 * 8400] = .2f
        values[4 * 8400] = .9f
        return YoloTensorOutput(values, intArrayOf(1, 56, 8400))
    }

    private open inner class FakeRunner(private val failure: Throwable? = null) : YoloRunner {
        val runs = AtomicInteger()
        val closes = AtomicInteger()
        override fun run(input: PreparedYoloImage): YoloTensorOutput {
            runs.incrementAndGet()
            failure?.let { throw it }
            return validOutput()
        }
        override fun close() { closes.incrementAndGet() }
    }

    private inner class BlockingRunner : FakeRunner() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val maxConcurrent = AtomicInteger()
        val threads = mutableListOf<Thread>()
        private val concurrent = AtomicInteger()

        override fun run(input: PreparedYoloImage): YoloTensorOutput {
            runs.incrementAndGet()
            val active = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, active) }
            threads += Thread.currentThread()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            concurrent.decrementAndGet()
            return validOutput()
        }
    }
}
