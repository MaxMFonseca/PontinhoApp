package com.maxmfonseca.pontinho.ui.scanner

import android.graphics.Bitmap
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.recognition.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.recognition.RecognitionImageException
import com.maxmfonseca.pontinho.ui.test.FakeMatchRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModelTest {
    @Test fun consumedSavedRemainsPendingForRetainedCollectorUntilAcknowledged() = runTest {
        val saved = SavedStateHandle()
        val repo = FakeMatchRepository()
        val vm = vm(saved, repo = repo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.confirm(); advanceUntilIdle()
        val first = mutableListOf<ScannerEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { first += it } }
        runCurrent()
        assertEquals(listOf(ScannerEvent.Saved(request.matchId, request.roundId, request.participantId)), first)
        collector.cancel(); runCurrent()
        val second = mutableListOf<ScannerEvent>()
        val replacement = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { second += it } }
        runCurrent()
        assertEquals(first, second)
        vm.confirm(); vm.cancel(); advanceUntilIdle()
        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.canConfirm)
        assertEquals(1, repo.submittedScans.size)
        vm.acknowledgeSaved(first.single() as ScannerEvent.Saved)
        replacement.cancel(); runCurrent()
        val restored = vm(roundTrip(saved), repo = repo)
        val afterAck = mutableListOf<ScannerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.events.collect { afterAck += it } }
        runCurrent()
        assertTrue(afterAck.isEmpty())
        assertTrue(restored.uiState.value.saved)
        assertFalse(restored.uiState.value.canConfirm)
    }

    private val request = ScanRequest(
        MatchId("match"), ParticipantId("participant"), RoundId("round"),
        Indicator(Rank.FIVE, CardColor.RED), submissionToken = "stable-token",
    )
    private val uri = Uri.parse("content://photos/private-image")

    @Test fun disposingAndRecreatingCaptureOwnerDuringDecodePreservesLaterSourceOpens() = runTest {
        val cache = ApplicationProvider.getApplicationContext<Context>().cacheDir
        val screenFiles = ScanCaptureFiles(cache)
        val source = screenFiles.create().apply { writeText("camera bytes") }
        val nextOpen = CompletableDeferred<Unit>()
        val bitmap = bitmap()
        val vm = ScannerViewModel(request, SavedStateHandle(), FakeEngine(), FakeMatchRepository(), {
            assertEquals("camera bytes", source.readText())
            nextOpen.await()
            assertEquals("camera bytes", source.readText())
            bitmap
        }, StandardTestDispatcher(testScheduler))
        vm.onCameraCaptured(Uri.fromFile(source), screenFiles.transfer(source))
        runCurrent()
        assertEquals(ScannerPhase.Processing, vm.uiState.value.phase)
        // Mirrors disposal and recreation of the screen's file owner while the retained VM runs.
        screenFiles.close()
        val recreatedFiles = ScanCaptureFiles(cache)
        recreatedFiles.create()
        recreatedFiles.close()
        nextOpen.complete(Unit)
        advanceUntilIdle()
        assertEquals(ScannerPhase.Review, vm.uiState.value.phase)
        assertEquals(1, vm.uiState.value.cards.size)
        assertEquals("stable-token", vm.request.submissionToken)
        assertFalse(source.exists())
        assertTrue(bitmap.isRecycled)
    }

    @Test fun recognitionCancellationReturnsToCaptureAndReleasesTransferredCameraFile() = runTest {
        val files = ScanCaptureFiles(ApplicationProvider.getApplicationContext<Context>().cacheDir)
        val source = files.create()
        val vm = ScannerViewModel(request, SavedStateHandle(), FakeEngine(block = { throw CancellationException() }),
            FakeMatchRepository(), { bitmap() }, StandardTestDispatcher(testScheduler))
        vm.onCameraCaptured(Uri.fromFile(source), files.transfer(source))
        advanceUntilIdle()
        assertEquals(ScannerPhase.Capture, vm.uiState.value.phase)
        assertFalse(source.exists())
        files.close()
    }

    @Test fun cancelledBeforeProcessingStartsAndRejectedInputReleaseSourceOwnership() = runTest {
        var releases = 0
        val vm = vm()
        vm.onCameraCaptured(uri, AutoCloseable { releases++ })
        vm.cancel()
        advanceUntilIdle()
        assertEquals(1, releases)
        vm.onCameraCaptured(uri, AutoCloseable { releases++ })
        assertEquals(2, releases)
    }

    @Test fun retakeKeepsSourceUntilUncooperativeDecodeFinishesThenReleasesIt() = runTest {
        val files = ScanCaptureFiles(ApplicationProvider.getApplicationContext<Context>().cacheDir)
        val source = files.create().apply { writeText("camera bytes") }
        val nextOpen = CompletableDeferred<Unit>()
        val bitmap = bitmap()
        val vm = ScannerViewModel(request, SavedStateHandle(), FakeEngine(), FakeMatchRepository(), {
            withContext(NonCancellable) { nextOpen.await() }
            assertEquals("camera bytes", source.readText())
            bitmap
        }, StandardTestDispatcher(testScheduler))
        vm.onCameraCaptured(Uri.fromFile(source), files.transfer(source))
        runCurrent()
        vm.retake()
        files.clear()
        assertTrue(source.exists())
        nextOpen.complete(Unit)
        advanceUntilIdle()
        assertEquals(ScannerPhase.Capture, vm.uiState.value.phase)
        assertFalse(source.exists())
        assertTrue(bitmap.isRecycled)
        files.close()
    }

    @Test fun retakeDiscardsReviewButKeepsRequestAndCannotInterruptSaving() = runTest {
        val vm = vm()
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.retake()
        assertEquals(ScannerUiState(), vm.uiState.value)
        assertEquals("stable-token", vm.request.submissionToken)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.confirm()
        vm.retake()
        assertEquals(ScannerPhase.Saving, vm.uiState.value.phase)
        advanceUntilIdle()
        vm.retake()
        assertTrue(vm.uiState.value.saved)
    }

    @Test fun cameraInputProcessesThenWaitsForExplicitConfirmation() = runTest {
        val repo = FakeMatchRepository()
        val engine = FakeEngine()
        val bitmap = bitmap()
        var loadedUri: Uri? = null
        val vm = ScannerViewModel(request, SavedStateHandle(), engine, repo,
            { loadedUri = it; bitmap }, StandardTestDispatcher(testScheduler))
        assertEquals(ScannerPhase.Capture, vm.uiState.value.phase)
        vm.onCameraCaptured(uri)
        assertEquals(ScannerPhase.Processing, vm.uiState.value.phase)
        assertEquals(ScanImageSource.Camera, vm.uiState.value.source)
        advanceUntilIdle()
        assertEquals(uri, loadedUri)
        assertEquals(1, engine.calls)
        assertTrue(bitmap.isRecycled)
        assertEquals(ScannerPhase.Review, vm.uiState.value.phase)
        assertEquals(20, vm.uiState.value.total)
        assertTrue(vm.uiState.value.canConfirm)
        assertTrue(repo.submittedScans.isEmpty())
    }

    @Test fun photoInputUsesTheSameReviewPipeline() = runTest {
        val vm = vm()
        vm.onPhotoSelected(uri)
        assertEquals(ScanImageSource.Photo, vm.uiState.value.source)
        advanceUntilIdle()
        assertEquals(ScannerPhase.Review, vm.uiState.value.phase)
        assertEquals(Card.Suited(Rank.SIX, Suit.HEARTS), vm.uiState.value.cards.single().card)
    }

    @Test fun emptyDetectionRequiresANewCaptureAndCannotBecomeAManualScan() = runTest {
        val repo = FakeMatchRepository()
        val vm = vm(engine = FakeEngine(result = result(emptyList())), repo = repo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        assertEquals(ScannerPhase.Error, vm.uiState.value.phase)
        assertEquals(ScannerRecovery.Capture, vm.uiState.value.recovery)
        assertFalse(vm.uiState.value.canConfirm)
        assertNotNull(vm.uiState.value.message)
        assertEquals(ScannerMessage.NoCards, vm.uiState.value.message)
        vm.confirm(); advanceUntilIdle()
        assertTrue(repo.submittedScans.isEmpty())
        vm.addCard(Card.Suited(Rank.ACE, Suit.CLUBS))
        assertTrue(vm.uiState.value.cards.isEmpty())
        assertFalse(vm.uiState.value.canConfirm)
        vm.retry()
        assertEquals(ScannerPhase.Capture, vm.uiState.value.phase)
    }

    @Test fun elevenReturnedCardsRequireRecaptureWithoutSavingPartialHand() = runTest {
        val repo = FakeMatchRepository()
        val vm = vm(engine = FakeEngine(result = result(List(11) { candidate() })), repo = repo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        assertEquals(ScannerPhase.Error, vm.uiState.value.phase)
        assertEquals(ScannerRecovery.Capture, vm.uiState.value.recovery)
        assertTrue(vm.uiState.value.cards.isEmpty())
        assertFalse(vm.uiState.value.canConfirm)
        vm.confirm(); advanceUntilIdle()
        assertTrue(repo.submittedScans.isEmpty())
    }

    @Test fun everySupportedCardCountEntersEditableReviewWithoutSaving() = runTest {
        for (count in 1..10) {
            val repo = FakeMatchRepository()
            val vm = vm(engine = FakeEngine(result = result(List(count) { candidate() })), repo = repo)
            vm.onPhotoSelected(uri); advanceUntilIdle()
            assertEquals(ScannerPhase.Review, vm.uiState.value.phase)
            assertEquals(count, vm.uiState.value.cards.size)
            assertTrue(vm.uiState.value.canEdit)
            assertTrue(vm.uiState.value.canConfirm)
            assertTrue(repo.submittedScans.isEmpty())
        }
    }

    @Test fun inferenceFailureCanReturnToCaptureAndRetry() = runTest {
        val engine = FakeEngine(block = { throw IllegalStateException("private details") })
        val vm = vm(engine = engine)
        vm.onCameraCaptured(uri); advanceUntilIdle()
        assertEquals(ScannerPhase.Error, vm.uiState.value.phase)
        assertEquals(ScannerRecovery.Capture, vm.uiState.value.recovery)
        assertEquals(ScannerMessage.RecognitionFailed, vm.uiState.value.message)
        vm.retry()
        assertEquals(ScannerPhase.Capture, vm.uiState.value.phase)
        engine.block = null
        vm.onPhotoSelected(uri); advanceUntilIdle()
        assertEquals(ScannerPhase.Review, vm.uiState.value.phase)
    }

    @Test fun typedFailuresSurviveRecreationAndOfferCaptureRetryWithoutCardsOrSave() = runTest {
        val failures = listOf(
            CardRecognitionFailure.NoCards() to ScannerMessage.NoCards,
            CardRecognitionFailure.TooManyCards(11) to ScannerMessage.TooManyCards,
            CardRecognitionFailure.IncompleteCards() to ScannerMessage.IncompleteCards,
        )
        for ((failure, message) in failures) {
            val repo = FakeMatchRepository()
            val saved = SavedStateHandle()
            val bitmap = bitmap()
            val vm = ScannerViewModel(request, saved, FakeEngine(block = { throw failure }), repo,
                { bitmap }, StandardTestDispatcher(testScheduler))
            vm.onPhotoSelected(uri); advanceUntilIdle()
            assertEquals(message, vm.uiState.value.message)
            assertTrue(bitmap.isRecycled)
            val restored = vm(roundTrip(saved), repo = repo)
            assertEquals(message, restored.uiState.value.message)
            assertEquals(ScannerRecovery.Capture, restored.uiState.value.recovery)
            assertTrue(restored.uiState.value.cards.isEmpty())
            assertFalse(restored.uiState.value.canConfirm)
            restored.confirm(); advanceUntilIdle()
            assertTrue(repo.submittedScans.isEmpty())
            assertSafeSavedValue(saved.savedStateProvider().saveState())
            restored.retry()
            assertEquals(ScannerPhase.Capture, restored.uiState.value.phase)
        }
    }

    @Test fun oversizedImageGetsSpecificRecoveryWithoutRunningInference() = runTest {
        val engine = FakeEngine()
        val vm = ScannerViewModel(request, SavedStateHandle(), engine, FakeMatchRepository(),
            { throw RecognitionImageException.SizeLimitExceeded() }, StandardTestDispatcher(testScheduler))
        vm.onPhotoSelected(uri); advanceUntilIdle()
        assertEquals(ScannerMessage.ImageTooLarge, vm.uiState.value.message)
        assertEquals(ScannerRecovery.Capture, vm.uiState.value.recovery)
        assertEquals(0, engine.calls)
        assertFalse(vm.uiState.value.canConfirm)
    }

    @Test fun imageDecodeFailureNeverRunsInference() = runTest {
        val engine = FakeEngine()
        val vm = ScannerViewModel(request, SavedStateHandle(), engine, FakeMatchRepository(),
            { throw SecurityException("access denied") }, StandardTestDispatcher(testScheduler))
        vm.onPhotoSelected(uri); advanceUntilIdle()
        assertEquals(0, engine.calls)
        assertEquals(ScannerPhase.Error, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.canConfirm)
    }

    @Test fun unavailableEngineCannotRecognizeOrCreateManualScan() = runTest {
        val engine = FakeEngine(available = false)
        val repo = FakeMatchRepository()
        val vm = vm(engine = engine, repo = repo)
        vm.onPhotoSelected(uri)
        vm.addCard(Card.Suited(Rank.ACE, Suit.CLUBS))
        vm.confirm(); advanceUntilIdle()
        assertEquals(0, engine.calls)
        assertTrue(repo.submittedScans.isEmpty())
        assertEquals(ScannerPhase.Error, vm.uiState.value.phase)
        assertEquals(ScannerMessage.Unavailable, vm.uiState.value.message)
        assertFalse(vm.uiState.value.canConfirm)
    }

    @Test fun cancellationIgnoresLateInferenceAndDoesNotSave() = runTest {
        val gate = CompletableDeferred<Unit>()
        val bitmap = bitmap()
        val repo = FakeMatchRepository()
        val engine = FakeEngine(block = { withContext(NonCancellable) { gate.await() }; result() })
        val vm = ScannerViewModel(request, SavedStateHandle(), engine, repo, { bitmap },
            StandardTestDispatcher(testScheduler))
        vm.onPhotoSelected(uri); runCurrent()
        vm.cancel()
        assertEquals(ScannerEvent.Cancelled, vm.events.first())
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(ScannerPhase.Capture, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.cards.isEmpty())
        assertTrue(bitmap.isRecycled)
        assertTrue(repo.submittedScans.isEmpty())
    }

    @Test fun inferenceCancellationReturnsToCaptureWithoutAnError() = runTest {
        val vm = vm(engine = FakeEngine(block = { throw CancellationException() }))
        vm.onPhotoSelected(uri); advanceUntilIdle()
        assertEquals(ScannerPhase.Capture, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.canConfirm)
    }

    @Test fun cancellingReviewDiscardsCardsAndPreventsLaterConfirmation() = runTest {
        val repo = FakeMatchRepository()
        val vm = vm(repo = repo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.cancel()
        vm.confirm(); advanceUntilIdle()
        assertEquals(ScannerEvent.Cancelled, vm.events.first())
        assertTrue(vm.uiState.value.cards.isEmpty())
        assertTrue(repo.submittedScans.isEmpty())
    }

    @Test fun editingPreservesPhysicalDuplicatesAndRecomputesTotal() = runTest {
        val vm = vm(engine = FakeEngine(result = result(listOf(candidate(), candidate()))))
        vm.onPhotoSelected(uri); advanceUntilIdle()
        val originals = vm.uiState.value.cards
        assertEquals(2, originals.map { it.id }.distinct().size)
        assertEquals(40, vm.uiState.value.total)
        vm.replaceRank(originals[0].id, Rank.ACE)
        assertEquals(35, vm.uiState.value.total)
        vm.replaceSuit(originals[1].id, Suit.SPADES)
        assertEquals(21, vm.uiState.value.total)
        assertTrue(vm.uiState.value.cards.all { it.correctedByUser })
        assertEquals(originals[0].confidence, vm.uiState.value.cards[0].confidence)
        assertEquals(originals[0].bounds, vm.uiState.value.cards[0].bounds)
        vm.addCard(Card.Suited(Rank.KING, Suit.CLUBS))
        assertEquals(31, vm.uiState.value.total)
        assertTrue(vm.uiState.value.cards.last().correctedByUser)
        assertNull(vm.uiState.value.cards.last().confidence)
        vm.removeCard(originals[0].id)
        assertEquals(16, vm.uiState.value.total)
        vm.uiState.value.cards.toList().forEach { vm.removeCard(it.id) }
        assertEquals(0, vm.uiState.value.total)
        assertFalse(vm.uiState.value.canConfirm)
    }

    @Test fun warningsSurviveReviewEditsAndRecreation() = runTest {
        val saved = SavedStateHandle()
        val warnings = setOf(RecognitionWarning.LOW_CONFIDENCE, RecognitionWarning.NEAR_IMAGE_EDGE)
        val vm = vm(saved, FakeEngine(result = result(listOf(candidate().copy(confidence = .42f, warnings = warnings)))))
        vm.onPhotoSelected(uri); advanceUntilIdle()
        assertTrue(vm.uiState.value.hasLowConfidence)
        vm.replaceRank(vm.uiState.value.cards.single().id, Rank.QUEEN)
        val restored = vm(roundTrip(saved))
        assertTrue(restored.uiState.value.hasLowConfidence)
        assertEquals(warnings, restored.uiState.value.cards.single().warnings)
        assertTrue(restored.uiState.value.cards.single().correctedByUser)
    }

    @Test fun doubleConfirmationSavesOnceAndEmitsSuccessOnlyAfterCommit() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeMatchRepository(submitScanBlock = { gate.await() })
        val vm = vm(repo = repo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.confirm(); vm.confirm(); runCurrent()
        val success = async { vm.events.first() }
        runCurrent()
        assertFalse(success.isCompleted)
        assertEquals(ScannerPhase.Saving, vm.uiState.value.phase)
        assertEquals(1, repo.submittedScans.size)
        assertFalse(vm.uiState.value.canConfirm)
        vm.removeCard(vm.uiState.value.cards.single().id)
        vm.cancel()
        assertEquals(1, vm.uiState.value.cards.size)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(ScannerEvent.Saved(request.matchId, request.roundId, request.participantId), success.await())
        vm.confirm(); advanceUntilIdle()
        assertEquals(1, repo.submittedScans.size)
        assertTrue(vm.uiState.value.saved)
    }

    @Test fun failedSaveRetriesTheSameFrozenPayloadAndTokenAfterRecreation() = runTest {
        var fails = true
        val repo = FakeMatchRepository(submitScanBlock = { if (fails) throw java.io.IOException() })
        val saved = SavedStateHandle()
        val prepared = PreparedRound(request.roundId, request.matchId, 2, request.indicator)
        val vm = vm(saved, repo = repo, scanRequest = request.copy(preparedRound = prepared))
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.confirm(); advanceUntilIdle()
        assertEquals(ScannerPhase.Error, vm.uiState.value.phase)
        assertEquals(ScannerRecovery.Save, vm.uiState.value.recovery)
        vm.addCard(Card.Suited(Rank.ACE, Suit.CLUBS))
        val restored = vm(roundTrip(saved), repo = repo, scanRequest = request.copy(submissionToken = "new-token"))
        assertEquals("stable-token", restored.request.submissionToken)
        fails = false
        restored.retry(); advanceUntilIdle()
        assertEquals(2, repo.submittedScans.size)
        assertEquals(repo.submittedScans[0], repo.submittedScans[1])
        assertEquals(prepared, repo.submittedScans.last().preparedRound)
        assertEquals("test-model", repo.submittedScans.last().modelVersion)
        assertEquals(1, repo.submittedScans.last().cards.size)
        assertTrue(restored.uiState.value.saved)
    }

    @Test fun legacySaveFailureRestoresTypedMessageAndFrozenPayload() = runTest {
        val saved = SavedStateHandle()
        val repo = FakeMatchRepository(submitScanBlock = { throw java.io.IOException() })
        val original = vm(saved, repo = repo)
        original.onPhotoSelected(uri); advanceUntilIdle()
        original.confirm(); advanceUntilIdle()
        saved.get<Bundle>("scanner_snapshot")!!.apply {
            putInt("version", 1)
            putString("message", "Não foi possível confirmar a gravação. Tente salvar novamente.")
        }
        val retryRepo = FakeMatchRepository()
        val restored = vm(roundTrip(saved), repo = retryRepo)
        assertEquals(ScannerMessage.SaveFailed, restored.uiState.value.message)
        assertEquals(original.uiState.value.cards, restored.uiState.value.cards)
        assertTrue(retryRepo.submittedScans.isEmpty())
        restored.retry(); advanceUntilIdle()
        assertEquals(repo.submittedScans.single(), retryRepo.submittedScans.single())
    }

    @Test fun correctionUsesAtomicUpdateAndRetainsCorrectionId() = runTest {
        val saved = SavedStateHandle()
        val repo = FakeMatchRepository()
        val vm = vm(saved, repo = repo, scanRequest = request.copy(correctionScoreId = "score-id"))
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.replaceRank(vm.uiState.value.cards.single().id, Rank.ACE)
        val restored = vm(roundTrip(saved), repo = repo)
        assertEquals("score-id", restored.request.correctionScoreId)
        restored.confirm(); advanceUntilIdle()
        assertTrue(repo.submittedScans.isEmpty())
        val command = repo.updatedScans.single()
        assertEquals(request.matchId, command.matchId)
        assertEquals(request.roundId, command.roundId)
        assertEquals(request.participantId, command.participantId)
        assertEquals("test-model", command.modelVersion)
        assertEquals(Card.Suited(Rank.ACE, Suit.HEARTS), command.cards.single().card)
        assertTrue(command.cards.single().correctedByUser)
        assertEquals(.91f, command.cards.single().recognitionConfidence)
        assertEquals(NormalizedRect(.1f, .2f, .3f, .4f), command.cards.single().sourceBounds)
    }

    @Test fun failedCorrectionCanRetryButNeverFallsThroughToNewScore() = runTest {
        var fails = true
        val repo = FakeMatchRepository(updateScanBlock = { if (fails) throw IllegalArgumentException("stale score") })
        val saved = SavedStateHandle()
        val vm = vm(saved, repo = repo, scanRequest = request.copy(correctionScoreId = "score-id"))
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.confirm(); advanceUntilIdle()
        assertEquals(ScannerRecovery.Save, vm.uiState.value.recovery)
        val restored = vm(roundTrip(saved), repo = repo)
        fails = false
        restored.retry(); advanceUntilIdle()
        assertEquals(2, repo.updatedScans.size)
        assertEquals(repo.updatedScans[0], repo.updatedScans[1])
        assertTrue(repo.submittedScans.isEmpty())
        assertTrue(restored.uiState.value.saved)
    }

    @Test fun incompleteRestoredRequestCannotTurnCorrectionIntoNewSubmission() = runTest {
        val saved = SavedStateHandle()
        val repo = FakeMatchRepository()
        val vm = vm(saved, repo = repo, scanRequest = request.copy(correctionScoreId = "score-id"))
        vm.onPhotoSelected(uri); advanceUntilIdle()
        saved.get<Bundle>("scanner_snapshot")!!.getBundle("request")!!.remove("correction")
        val restored = vm(roundTrip(saved), repo = repo)
        restored.confirm(); advanceUntilIdle()
        assertTrue(repo.submittedScans.isEmpty())
        assertTrue(repo.updatedScans.isEmpty())
        assertEquals(ScannerPhase.Capture, restored.uiState.value.phase)
        assertEquals(ScannerMessage.ImageExpired, restored.uiState.value.message)
    }

    @Test fun missingRestoredRequestDoesNotSubmitCardsUsingAnotherParticipant() = runTest {
        val saved = SavedStateHandle()
        val repo = FakeMatchRepository()
        val vm = vm(saved, repo = repo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        saved.get<Bundle>("scanner_snapshot")!!.remove("request")
        val restored = vm(roundTrip(saved), repo = repo, scanRequest = request.copy(participantId = ParticipantId("another")))
        restored.confirm(); advanceUntilIdle()
        assertTrue(repo.submittedScans.isEmpty())
        assertTrue(restored.uiState.value.cards.isEmpty())
        assertEquals(ScannerPhase.Capture, restored.uiState.value.phase)
    }

    @Test fun invalidCandidateDiscardsEntireReviewInsteadOfSubmittingPartialCards() = runTest {
        val saved = SavedStateHandle()
        val repo = FakeMatchRepository()
        val vm = vm(saved, FakeEngine(result = result(listOf(candidate(), candidate()))), repo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        @Suppress("DEPRECATION")
        val cards = saved.get<Bundle>("scanner_snapshot")!!.getParcelableArrayList<Bundle>("cards")!!
        cards[1].putString("rank", "invalid-rank")
        val restored = vm(roundTrip(saved), repo = repo)
        restored.confirm(); advanceUntilIdle()
        assertEquals(ScannerPhase.Capture, restored.uiState.value.phase)
        assertTrue(restored.uiState.value.cards.isEmpty())
        assertTrue(repo.submittedScans.isEmpty())
    }

    @Test fun cancelledSavePreservesReviewForExactRetry() = runTest {
        var cancels = true
        val repo = FakeMatchRepository(submitScanBlock = { if (cancels) throw CancellationException() })
        val vm = vm(repo = repo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.confirm(); advanceUntilIdle()
        assertEquals(ScannerRecovery.Save, vm.uiState.value.recovery)
        cancels = false
        vm.retry(); advanceUntilIdle()
        assertEquals(repo.submittedScans.first(), repo.submittedScans.last())
        assertTrue(vm.uiState.value.saved)
    }

    @Test fun reviewSurvivesParcelableRoundTripWithoutSavingImagesOrUris() = runTest {
        val saved = SavedStateHandle()
        val repo = FakeMatchRepository()
        val vm = vm(saved, repo = repo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.addCard(Card.Suited(Rank.ACE, Suit.CLUBS))
        val restored = vm(roundTrip(saved), repo = repo)
        assertEquals(vm.uiState.value.cards, restored.uiState.value.cards)
        assertEquals(35, restored.uiState.value.total)
        assertEquals(ScannerPhase.Review, restored.uiState.value.phase)
        assertTrue(repo.submittedScans.isEmpty())
        assertSafeSavedValue(saved.savedStateProvider().saveState())
    }

    @Test fun processLossWhileProcessingReturnsToCaptureWithRecoveryMessage() = runTest {
        val saved = SavedStateHandle()
        val gate = CompletableDeferred<CardRecognitionResult>()
        val vm = vm(saved, FakeEngine(block = { gate.await() }))
        vm.onCameraCaptured(uri); runCurrent()
        assertSafeSavedValue(saved.savedStateProvider().saveState())
        val restored = vm(roundTrip(saved))
        assertEquals(ScannerPhase.Capture, restored.uiState.value.phase)
        assertEquals(ScannerMessage.ImageExpired, restored.uiState.value.message)
        assertTrue(restored.uiState.value.cards.isEmpty())
        assertFalse(restored.uiState.value.canConfirm)
        vm.cancel(); advanceUntilIdle()
    }

    @Test fun processLossDuringCaptureReturnsToCaptureWithRecoveryMessage() = runTest {
        val saved = SavedStateHandle()
        val vm = vm(saved)
        vm.beginCapture(ScanImageSource.Camera)
        val restored = vm(roundTrip(saved))
        assertEquals(ScannerPhase.Capture, restored.uiState.value.phase)
        assertEquals(ScannerMessage.ImageExpired, restored.uiState.value.message)
        assertFalse(restored.uiState.value.awaitingInput)
    }

    @Test fun interruptedSaveRequiresExplicitRetryAndPreservesPayload() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = SavedStateHandle()
        val originalRepo = FakeMatchRepository(submitScanBlock = { gate.await() })
        val vm = vm(saved, repo = originalRepo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.confirm(); runCurrent()
        val retryRepo = FakeMatchRepository()
        val restored = vm(roundTrip(saved), repo = retryRepo)
        assertEquals(ScannerRecovery.Save, restored.uiState.value.recovery)
        assertEquals(ScannerMessage.SaveInterrupted, restored.uiState.value.message)
        assertTrue(retryRepo.submittedScans.isEmpty())
        restored.retry(); advanceUntilIdle()
        assertEquals(originalRepo.submittedScans.single(), retryRepo.submittedScans.single())
        gate.complete(Unit); advanceUntilIdle()
    }

    @Test fun savedSuccessSurvivesRecreationWithoutSubmittingAgain() = runTest {
        val saved = SavedStateHandle()
        val repo = FakeMatchRepository()
        val vm = vm(saved, repo = repo)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        vm.confirm(); advanceUntilIdle()
        val restored = vm(roundTrip(saved), repo = repo)
        restored.confirm(); advanceUntilIdle()
        assertEquals(1, repo.submittedScans.size)
        assertEquals(ScannerEvent.Saved(request.matchId, request.roundId, request.participantId), restored.events.first())
    }

    @Test fun cancelledPickerDoesNotDiscardReviewOrLaunchInference() = runTest {
        val engine = FakeEngine()
        val vm = vm(engine = engine)
        vm.beginCapture(ScanImageSource.Photo)
        vm.onPhotoSelected(null)
        assertEquals(ScannerPhase.Capture, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.awaitingInput)
        assertEquals(0, engine.calls)
        vm.onPhotoSelected(uri); advanceUntilIdle()
        val review = vm.uiState.value
        vm.onCameraCaptured(uri); vm.onPhotoSelected(null)
        advanceUntilIdle()
        assertEquals(review, vm.uiState.value)
        assertEquals(1, engine.calls)
    }

    @Test fun clearingViewModelCancelsProcessingAndReleasesItsBitmap() = runTest {
        val gate = CompletableDeferred<CardRecognitionResult>()
        val bitmap = bitmap()
        val engine = FakeEngine(block = { gate.await() })
        val vm = ScannerViewModel(request, SavedStateHandle(), engine, FakeMatchRepository(), { bitmap },
            StandardTestDispatcher(testScheduler))
        val store = ViewModelStore().apply { put("scanner", vm) }
        vm.onPhotoSelected(uri); runCurrent()
        store.clear(); advanceUntilIdle()
        assertTrue(bitmap.isRecycled)
        // Engine lifetime belongs to the provider, which may serve another screen.
        assertFalse(engine.closed)
    }

    private fun TestScope.vm(
        saved: SavedStateHandle = SavedStateHandle(),
        engine: FakeEngine = FakeEngine(),
        repo: FakeMatchRepository = FakeMatchRepository(),
        scanRequest: ScanRequest = request,
    ) = ScannerViewModel(scanRequest, saved, engine, repo, { bitmap() }, StandardTestDispatcher(testScheduler))

    private fun roundTrip(handle: SavedStateHandle): SavedStateHandle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(handle.savedStateProvider().saveState())
            val bytes = parcel.marshall()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(javaClass.classLoader), null)
        } finally { parcel.recycle() }
    }

    @Suppress("DEPRECATION") // Recursively inspect all values regardless of their saved-state type.
    private fun assertSafeSavedValue(value: Any?) {
        assertFalse(value is Bitmap || value is Uri || value is ByteArray)
        assertFalse(value is String && value.contains("private-image"))
        when (value) {
            is Bundle -> value.keySet().forEach { assertSafeSavedValue(value.get(it)) }
            is Iterable<*> -> value.forEach(::assertSafeSavedValue)
            is Array<*> -> value.forEach(::assertSafeSavedValue)
        }
    }

    private class FakeEngine(
        private val result: CardRecognitionResult = result(),
        var block: (suspend () -> CardRecognitionResult)? = null,
        available: Boolean = true,
    ) : CardRecognitionEngine {
        var calls = 0
        var closed = false
        override val availability = if (available) RecognitionAvailability.Available(
            RecognitionModelMetadata("test-model", "0".repeat(64), (0..51).map { "card-$it" }, 640, 640, .25f, .45f),
        ) else RecognitionAvailability.Unavailable(RecognitionUnavailableReason.MISSING_ASSETS)
        override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult {
            calls++
            return block?.invoke() ?: result
        }
        override fun close() { closed = true }
    }

    companion object {
        private fun bitmap() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        private fun candidate() = RecognizedCard(Card.Suited(Rank.SIX, Suit.HEARTS),
            NormalizedRect(.1f, .2f, .3f, .4f), .91f, emptySet())
        private fun result(cards: List<RecognizedCard> = listOf(candidate())) =
            CardRecognitionResult(cards, 15, "test-model")
    }
}
