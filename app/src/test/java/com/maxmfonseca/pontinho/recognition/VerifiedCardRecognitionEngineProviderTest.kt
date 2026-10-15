package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import com.maxmfonseca.pontinho.domain.recognition.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VerifiedCardRecognitionEngineProviderTest {
    private fun assets(): Pair<ByteArray?, ByteArray?> = YoloBundleFixture.model.copyOf() to YoloBundleFixture.bytes()
    private fun productionAssets(): Pair<ByteArray?, ByteArray?> =
        YoloBundleFixture.model.copyOf() to YoloBundleFixture.productionBytes()

    @Test fun `asset reader contract is the exact nullable model and release pair`() = runTest {
        val reader: SingleStageRecognitionAssetReader = { null to null }
        val provider = VerifiedCardRecognitionEngineProvider(
            readAssets = reader,
            factory = null,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        advanceUntilIdle()

        assertEquals(
            RecognitionAvailability.Unavailable(RecognitionUnavailableReason.MISSING_ASSETS),
            provider.availability.value,
        )
        provider.close()
    }

    @Test fun `production dispatcher verifies assets and constructs one detector off main`() = runBlocking {
        val main = android.os.Looper.getMainLooper().thread
        val provider = VerifiedCardRecognitionEngineProvider(
            readAssets = { assertNotEquals(main, Thread.currentThread()); productionAssets() },
            factory = { model, metadata ->
                assertArrayEquals(YoloBundleFixture.model, model); assertNotEquals(main, Thread.currentThread())
                Engine(metadata).also { it.inference.complete(Unit) }
            },
        )
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try { assertEquals(YoloBundleFixture.modelHash, withTimeout(5_000) { provider.get().recognize(bitmap) }.modelVersion) }
        finally { provider.close(); bitmap.recycle() }
    }

    @Test fun `availability verification is eager but detector construction remains lazy`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var constructions = 0
        val provider = VerifiedCardRecognitionEngineProvider(
            readAssets = { gate.await(); assets() },
            factory = { model, metadata -> assertArrayEquals(YoloBundleFixture.model, model); constructions++; Engine(metadata) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val borrowed = provider.get()
        assertEquals(RecognitionAvailability.Checking, borrowed.availability)
        runCurrent(); assertEquals(0, constructions)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(borrowed.availability is RecognitionAvailability.Available)
        assertEquals(0, constructions); assertSame(borrowed, provider.get())
        provider.close()
    }

    @Test fun `concurrent recognition creates one detector and cancelled waiter does not own it`() = runTest {
        var constructions = 0
        lateinit var native: Engine
        val provider = VerifiedCardRecognitionEngineProvider({ assets() }, { _, metadata ->
            constructions++; Engine(metadata).also { native = it }
        }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val cancelled = launch { provider.get().recognize(bitmap) }
        val next = async { provider.get().recognize(bitmap) }
        runCurrent(); cancelled.cancel(); native.inference.complete(Unit); advanceUntilIdle()
        assertEquals(YoloBundleFixture.modelHash, next.await().modelVersion)
        assertEquals(1, constructions); assertEquals(0, native.closes)
        provider.close(); provider.close(); assertEquals(1, native.closes)
        bitmap.recycle()
    }

    @Test fun `closing during detector creation disposes it and never admits inference`() = runTest {
        lateinit var provider: VerifiedCardRecognitionEngineProvider
        lateinit var native: Engine
        provider = VerifiedCardRecognitionEngineProvider({ assets() }, { _, metadata ->
            native = Engine(metadata)
            provider.close()
            native
        }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val call = async { runCatching { provider.get().recognize(bitmap) } }
        advanceUntilIdle()
        assertTrue(call.await().isFailure)
        assertEquals(1, native.closes)
        assertThrows(IllegalStateException::class.java) { provider.get() }
        bitmap.recycle()
    }

    @Test fun `missing malformed and unapproved typed pairs fail closed without construction`() = runTest {
        val cases: List<Pair<ByteArray?, ByteArray?>> = listOf(
            Pair(null, null), Pair(YoloBundleFixture.model, null), Pair(null, YoloBundleFixture.bytes()),
            Pair(YoloBundleFixture.model, "bad".toByteArray()),
            Pair(YoloBundleFixture.model, YoloBundleFixture.json().put("productionApproved", true).toString().encodeToByteArray()),
        )
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        cases.forEach { source ->
            var constructions = 0
            val provider = VerifiedCardRecognitionEngineProvider({ source }, { _, metadata -> constructions++; Engine(metadata) },
                StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            assertTrue(provider.availability.value is RecognitionAvailability.Unavailable)
            val attempt = async { runCatching { provider.get().recognize(bitmap) } }
            advanceUntilIdle(); assertTrue(attempt.await().isFailure); assertEquals(0, constructions)
            provider.close()
        }
        bitmap.recycle()
    }

    @Test fun `provider owns copied model and metadata snapshots until lazy construction`() = runTest {
        val sourceModel = YoloBundleFixture.model.copyOf()
        val sourceJson = YoloBundleFixture.bytes()
        val provider = VerifiedCardRecognitionEngineProvider({ sourceModel to sourceJson }, { model, metadata ->
            assertArrayEquals(YoloBundleFixture.model, model); Engine(metadata).also { it.inference.complete(Unit) }
        }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle(); sourceModel.fill(0); sourceJson.fill(0)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val result = async { provider.get().recognize(bitmap) }
        advanceUntilIdle(); assertEquals(YoloBundleFixture.modelHash, result.await().modelVersion)
        provider.close(); bitmap.recycle()
    }

    @Test fun `closing while awaiting assets cancels verification without constructing engines`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val provider = VerifiedCardRecognitionEngineProvider({
            try { gate.await(); assets() } finally { cancelled = true }
        }, { _, _ -> error("Must not construct") }, StandardTestDispatcher(testScheduler))
        runCurrent(); provider.close(); advanceUntilIdle()
        assertTrue(cancelled); assertThrows(IllegalStateException::class.java) { provider.get() }
    }

    @Test fun `verified assets without an installed factory stay unavailable`() = runTest {
        val provider = VerifiedCardRecognitionEngineProvider({ assets() }, null, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED), provider.availability.value)
        provider.close()
    }

    @Test fun `factory failures fail closed and expose no engine`() = runTest {
        val provider = VerifiedCardRecognitionEngineProvider(
            readAssets = { assets() },
            factory = { _, _ -> error("private factory detail") },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val attempt = async { runCatching { provider.get().recognize(bitmap) } }
        advanceUntilIdle()

        assertTrue(attempt.await().exceptionOrNull() is CardRecognitionException.InferenceFailed)
        assertEquals(
            RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED),
            provider.availability.value,
        )
        provider.close()
        bitmap.recycle()
    }

    private class Engine(metadata: YoloRecognitionMetadata) : CardRecognitionEngine {
        override val availability = RecognitionAvailability.Available(metadata)
        val inference = CompletableDeferred<Unit>()
        var closes = 0
        override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult {
            inference.await(); return CardRecognitionResult(emptyList(), 0, availability.metadata.modelVersion)
        }
        override fun close() { closes++ }
    }
}
