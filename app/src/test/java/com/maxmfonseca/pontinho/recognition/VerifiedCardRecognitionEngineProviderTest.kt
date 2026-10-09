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
    private fun assets() = Triple(BundleFixture.detector.copyOf(), BundleFixture.classifier.copyOf(), BundleFixture.bytes())

    @Test fun `production dispatcher verifies assets and constructs native detector off main`() = runBlocking {
        val main = android.os.Looper.getMainLooper().thread
        val provider = VerifiedCardRecognitionEngineProvider({
            assertNotEquals(main, Thread.currentThread()); assets()
        }, { detector, classifier, metadata ->
            assertArrayEquals(BundleFixture.detector, detector); assertArrayEquals(BundleFixture.classifier, classifier)
            assertNotEquals(main, Thread.currentThread())
            Engine(metadata).also { it.inference.complete(Unit) }
        })
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val result = withTimeout(5_000) { provider.get().recognize(bitmap) }
            assertEquals("a".repeat(64), result.modelVersion)
        } finally { provider.close(); bitmap.recycle() }
    }

    @Test fun `opening and editing manual dialog during verification never creates detector`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var constructions = 0
        val provider = VerifiedCardRecognitionEngineProvider({ gate.await(); assets() }, { detector, classifier, metadata ->
            assertArrayEquals(BundleFixture.detector, detector); assertArrayEquals(BundleFixture.classifier, classifier)
            constructions++; Engine(metadata)
        }, StandardTestDispatcher(testScheduler))
        val snapshot = com.maxmfonseca.pontinho.ui.test.matchSnapshot("manual", java.time.LocalDate.of(2026, 10, 14),
            2, listOf("a" to "Ana", "b" to "Bruno"))
        val repo = object : com.maxmfonseca.pontinho.domain.repository.MatchRepository by
            com.maxmfonseca.pontinho.ui.test.FakeMatchRepository() {
            override fun observeMatch(matchId: com.maxmfonseca.pontinho.domain.model.MatchId) =
                kotlinx.coroutines.flow.MutableStateFlow(snapshot)
            override suspend fun chooseIndicator(command: com.maxmfonseca.pontinho.domain.repository.ChooseIndicator) =
                com.maxmfonseca.pontinho.domain.repository.PreparedRound(
                    com.maxmfonseca.pontinho.domain.model.RoundId("r"), command.matchId, 1, command.indicator)
        }
        val vm = com.maxmfonseca.pontinho.ui.match.MatchViewModel(snapshot.id, repo,
            StandardTestDispatcher(testScheduler), recognitionAvailable = {
                provider.availability.value is RecognitionAvailability.Available
            }, recognitionAvailabilityChanges = provider.availability)
        runCurrent()
        vm.addScore(snapshot.participants.first().id)
        vm.selectIndicatorRank(com.maxmfonseca.pontinho.domain.model.Rank.ACE)
        vm.selectIndicatorColor(com.maxmfonseca.pontinho.domain.model.CardColor.BLACK)
        vm.confirmIndicator(); runCurrent(); vm.updateScore("17")
        assertTrue(vm.uiState.value.scoreDialog!!.canConfirm)
        assertFalse(vm.uiState.value.scoreDialog!!.canScan)
        assertEquals(0, constructions)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(vm.uiState.value.scoreDialog!!.canScan)
        assertEquals("17", vm.uiState.value.scoreDialog!!.value)
        assertEquals(0, constructions)
        provider.close()
    }

    @Test fun `checking and reading verified availability never constructs a detector`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var constructions = 0
        val provider = VerifiedCardRecognitionEngineProvider(
            readAssets = { gate.await(); assets() },
            factory = { detector, classifier, metadata ->
            assertArrayEquals(BundleFixture.detector, detector); assertArrayEquals(BundleFixture.classifier, classifier)
            constructions++; Engine(metadata) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val borrowed = provider.get()
        assertEquals(RecognitionAvailability.Checking, borrowed.availability)
        runCurrent()
        assertEquals(0, constructions)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(borrowed.availability is RecognitionAvailability.Available)
        assertEquals(0, constructions)
        assertSame(borrowed, provider.get())
        provider.close()
    }

    @Test fun `concurrent recognition creates one detector and cancelled waiter does not own it`() = runTest {
        var constructions = 0
        lateinit var native: Engine
        val provider = VerifiedCardRecognitionEngineProvider({ assets() }, { detector, classifier, metadata ->
            assertArrayEquals(BundleFixture.detector, detector); assertArrayEquals(BundleFixture.classifier, classifier)
            constructions++; Engine(metadata).also { native = it }
        }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val cancelled = launch { provider.get().recognize(bitmap) }
        val next = async { provider.get().recognize(bitmap) }
        runCurrent()
        cancelled.cancel(); native.inference.complete(Unit); advanceUntilIdle()
        assertEquals("a".repeat(64), next.await().modelVersion)
        assertEquals(1, constructions)
        assertEquals(0, native.closes)
        provider.close(); provider.close()
        assertEquals(1, native.closes)
        bitmap.recycle()
    }

    @Test fun `closing during native creation disposes result and never admits inference`() = runTest {
        lateinit var provider: VerifiedCardRecognitionEngineProvider
        lateinit var native: Engine
        provider = VerifiedCardRecognitionEngineProvider({ assets() }, { detector, classifier, metadata ->
            assertArrayEquals(BundleFixture.detector, detector); assertArrayEquals(BundleFixture.classifier, classifier)
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
        assertEquals(0, native.calls)
        assertThrows(IllegalStateException::class.java) { provider.get() }
        bitmap.recycle()
    }

    @Test fun `absent malformed and unapproved assets fail closed without native construction`() = runTest {
        val valid = assets()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        for (pair in listOf(Triple(null, null, null), Triple(valid.first, null, valid.third), Triple(null, valid.second, valid.third),
            Triple(valid.first, valid.first, valid.third),
            Triple(valid.first, valid.second, "bad".toByteArray()), Triple(valid.first, valid.second, BundleFixture.json().put("releaseApproved", false).toString().encodeToByteArray()))) {
            var constructions = 0
            val provider = VerifiedCardRecognitionEngineProvider({ pair }, { _, _, metadata -> constructions++; Engine(metadata) },
                StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            assertTrue(provider.availability.value is RecognitionAvailability.Unavailable)
            val attempt = async { runCatching { provider.get().recognize(bitmap) } }
            advanceUntilIdle()
            assertTrue(attempt.await().isFailure)
            assertEquals(0, constructions)
            provider.close()
        }
        bitmap.recycle()
    }

    @Test fun `provider owns verified snapshots until lazy native creation`() = runTest {
        val source = assets()
        val provider = VerifiedCardRecognitionEngineProvider({ source }, { detector, classifier, metadata ->
            assertArrayEquals(BundleFixture.detector, detector)
            assertArrayEquals(BundleFixture.classifier, classifier)
            Engine(metadata).also { it.inference.complete(Unit) }
        }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        source.first.fill(0); source.second.fill(0); source.third.fill(0)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val result = async { provider.get().recognize(bitmap) }
        advanceUntilIdle()
        assertEquals("a".repeat(64), result.await().modelVersion)
        provider.close(); bitmap.recycle()
    }

    @Test fun `closing while awaiting assets cancels verification without constructing engines`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val provider = VerifiedCardRecognitionEngineProvider({
            try { gate.await(); assets() } finally { cancelled = true }
        }, { _, _, _ -> error("Must not construct") }, StandardTestDispatcher(testScheduler))
        runCurrent()
        provider.close(); advanceUntilIdle()
        assertTrue(cancelled)
        assertThrows(IllegalStateException::class.java) { provider.get() }
    }

    @Test fun `verified assets without an installed factory stay unavailable`() = runTest {
        val provider = VerifiedCardRecognitionEngineProvider({ assets() }, null, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(RecognitionAvailability.Unavailable(RecognitionUnavailableReason.ENGINE_NOT_INSTALLED), provider.availability.value)
        provider.close()
    }

    private class Engine(metadata: RecognitionModelBundleMetadata) : CardRecognitionEngine {
        override val availability = RecognitionAvailability.Available(metadata)
        val inference = CompletableDeferred<Unit>()
        var calls = 0
        var closes = 0
        override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult {
            calls++; inference.await()
            return CardRecognitionResult(emptyList(), 0, availability.metadata.modelVersion)
        }
        override fun close() { closes++ }
    }
}
