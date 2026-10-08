package com.maxmfonseca.pontinho.ui.match

import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.domain.rules.RoundEngine
import com.maxmfonseca.pontinho.domain.rules.RoundTransition
import com.maxmfonseca.pontinho.ui.components.RoundStatusKind
import com.maxmfonseca.pontinho.ui.test.matchSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import androidx.lifecycle.SavedStateHandle
import com.maxmfonseca.pontinho.ui.scanner.ScanRequest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MatchViewModelTest {
    @Test fun availabilityEmissionsBeforeRepositoryRestorePreserveSavedScannerContext() = runTest {
        val storage = ScoringRepository()
        val saved = SavedStateHandle()
        val vm = MatchViewModel(storage.snapshot.value!!.id, storage, StandardTestDispatcher(testScheduler), saved, { true })
        runCurrent(); prepare(vm, storage.ids[0]); advanceUntilIdle()
        vm.updateScore("17"); vm.openScanner(); advanceUntilIdle()
        val request = (vm.events.first() as MatchEvent.OpenScanner).request
        val restoredSaved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val repositoryReady = CompletableDeferred<Unit>()
        val repo = object : MatchRepository by storage {
            override fun observeMatch(matchId: MatchId) = flow {
                repositoryReady.await()
                emitAll(storage.observeMatch(matchId))
            }
        }
        val availability = MutableStateFlow<com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability>(
            com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability.Checking)
        val restored = MatchViewModel(storage.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler),
            restoredSaved,
            recognitionAvailable = { availability.value is com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability.Available },
            recognitionAvailabilityChanges = availability)
        runCurrent()
        assertEquals(saved.get<List<String>>("scoreDialog"), restoredSaved.get<List<String>>("scoreDialog"))
        assertEquals(request.submissionToken, restoredSaved.get<String>("activeScanToken"))
        repositoryReady.complete(Unit)
        runCurrent()
        assertEquals("17", restored.uiState.value.scoreDialog!!.value)
        assertTrue(restored.uiState.value.scoreDialog!!.canConfirm)
        assertFalse(restored.uiState.value.scoreDialog!!.canScan)
        restored.reconcileScannerNavigation(request.submissionToken)
        assertEquals(request, restored.validateScanner(com.maxmfonseca.pontinho.ui.navigation.ScannerContext.from(request)))
        availability.value = com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability.Available(
            com.maxmfonseca.pontinho.domain.recognition.RecognitionModelMetadata("fixture", "0".repeat(64),
                (0..51).map { "card-$it" }, 320, 320, .5f, .5f))
        runCurrent()
        assertTrue(restored.uiState.value.scoreDialog!!.canScan)
        restored.reconcileScannerNavigation(null)
        restored.openScanner(); advanceUntilIdle()
        assertEquals(MatchEvent.OpenScanner(request), restored.events.first())
        assertEquals("17", restored.uiState.value.scoreDialog!!.value)
    }

    @Test fun pendingAvailabilityAllowsManualEntryAndUpdatesCameraWithoutAnotherUserAction() = runTest {
        val repo = ScoringRepository()
        val availability = MutableStateFlow<com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability>(
            com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability.Checking)
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler),
            recognitionAvailable = { availability.value is com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability.Available },
            recognitionAvailabilityChanges = availability)
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("17")
        assertTrue(vm.uiState.value.scoreDialog!!.canConfirm)
        assertFalse(vm.uiState.value.scoreDialog!!.canScan)
        availability.value = com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability.Available(
            com.maxmfonseca.pontinho.domain.recognition.RecognitionModelMetadata("fixture", "0".repeat(64),
                (0..51).map { "card-$it" }, 320, 320, .5f, .5f))
        runCurrent()
        assertTrue(vm.uiState.value.scoreDialog!!.canScan)
        assertEquals("17", vm.uiState.value.scoreDialog!!.value)
        availability.value = com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability.Checking
        runCurrent(); vm.confirmScore(); advanceUntilIdle()
        assertNull(vm.uiState.value.scoreDialog)
        assertEquals(17, repo.snapshot.value!!.openRound!!.draft.entries[repo.ids[0]]!!.points)
    }

    @Test fun preparedScanIsOneShotAndCancellationPreservesManualContextAcrossRecreation() = runTest {
        val repo = ScoringRepository()
        val saved = SavedStateHandle()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler), saved, { true })
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("17")
        val events = mutableListOf<MatchEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.openScanner(); vm.openScanner(); advanceUntilIdle()
        val request = (events.single() as MatchEvent.OpenScanner).request
        assertNotNull(request.preparedRound)
        assertEquals(vm.uiState.value.scoreDialog!!.roundId, request.roundId)
        val restored = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler),
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), { true })
        runCurrent()
        restored.reconcileScannerNavigation(request.submissionToken)
        val restoredEvents = mutableListOf<MatchEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.events.collect { restoredEvents += it } }
        restored.openScanner(); advanceUntilIdle()
        assertTrue(restoredEvents.isEmpty())
        assertEquals("17", restored.uiState.value.scoreDialog!!.value)
        restored.scannerCancelled(request.submissionToken)
        restored.openScanner(); advanceUntilIdle()
        assertEquals(request, (restoredEvents.single() as MatchEvent.OpenScanner).request)
        restored.scannerCancelled(request.submissionToken)
        restored.confirmScore(); advanceUntilIdle()
        assertNull(restored.uiState.value.scoreDialog)
        assertEquals(17, repo.snapshot.value!!.openRound!!.draft.entries[request.participantId]!!.points)
    }

    @Test fun restoredPendingLaunchWithoutScannerDestinationCanRetrySameContextAndToken() = runTest {
        val repo = ScoringRepository()
        val saved = SavedStateHandle()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler), saved, { true })
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("17"); vm.openScanner(); advanceUntilIdle()
        // Event was emitted and consumed, but navigation has not completed before process loss.
        val request = (vm.events.first() as MatchEvent.OpenScanner).request
        val restored = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler),
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), { true })
        runCurrent()
        restored.reconcileScannerNavigation(null)
        val events = mutableListOf<MatchEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.events.collect { events += it } }
        restored.openScanner(); restored.openScanner(); advanceUntilIdle()
        assertEquals(listOf(MatchEvent.OpenScanner(request)), events)
        assertEquals("17", restored.uiState.value.scoreDialog!!.value)
        restored.reconcileScannerNavigation(request.submissionToken)
        restored.openScanner(); advanceUntilIdle()
        assertEquals(1, events.size)
    }

    @Test fun unavailableAndStaleScannerLaunchLeaveManualFlowUsable() = runTest {
        val repo = ScoringRepository()
        var available = false
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler),
            recognitionAvailable = { available })
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        val events = mutableListOf<MatchEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.openScanner(); advanceUntilIdle()
        assertTrue(events.isEmpty())
        vm.updateScore("9"); assertTrue(vm.uiState.value.scoreDialog!!.canConfirm)
        available = true
        // A committed competing round invalidates this transient round's identity.
        repo.snapshot.value = repo.snapshot.value!!.copy(rounds = listOf(repo.round(1, RoundDraft(repo.ids)).copy(id = RoundId("other"))))
        vm.openScanner(); advanceUntilIdle()
        assertTrue(events.isEmpty())
        vm.dismissDialog(); vm.addScore(repo.ids[0]); vm.updateScore("9"); vm.confirmScore(); advanceUntilIdle()
        assertEquals(9, repo.snapshot.value!!.openRound!!.draft.entries[repo.ids[0]]!!.points)
    }

    @Test fun queuedScannerEventCannotOpenAfterDialogDismissOrParticipantRemoval() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler), recognitionAvailable = { true })
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.openScanner(); advanceUntilIdle()
        val request = (vm.events.first() as MatchEvent.OpenScanner).request
        val context = com.maxmfonseca.pontinho.ui.navigation.ScannerContext.from(request)
        repo.snapshot.value = repo.snapshot.value!!.let { it.copy(participants = it.participants.drop(1)) }
        runCurrent()
        assertNull(vm.validateScanner(context))
        vm.scannerCancelled(request.submissionToken)
        assertNull(vm.uiState.value.scoreDialog)
        vm.dismissDialog()
        assertNull(vm.validateScanner(context))
    }

    @Test fun failedRepositoryRevalidationRejectsScannerWithoutBreakingManualDialog() = runTest {
        val storage = ScoringRepository()
        var fail = false
        val repo = object : MatchRepository by storage {
            override fun observeMatch(matchId: MatchId) = if (fail) kotlinx.coroutines.flow.flow<MatchSnapshot?> { error("offline db") }
                else storage.observeMatch(matchId)
        }
        val vm = MatchViewModel(storage.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler), recognitionAvailable = { true })
        runCurrent(); prepare(vm, storage.ids[0]); advanceUntilIdle()
        vm.openScanner(); advanceUntilIdle()
        val request = (vm.events.first() as MatchEvent.OpenScanner).request
        fail = true
        assertNull(vm.validateScanner(com.maxmfonseca.pontinho.ui.navigation.ScannerContext.from(request)))
        vm.scannerCancelled(request.submissionToken)
        vm.updateScore("9")
        assertTrue(vm.uiState.value.scoreDialog!!.canConfirm)
    }

    @Test fun automaticWinnerHasNoCameraActionAndDeleteRemainsAvailable() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler), recognitionAvailable = { true })
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("3"); vm.confirmScore(); advanceUntilIdle()
        vm.addScore(repo.ids[1]); vm.updateScore("4"); vm.confirmScore(); advanceUntilIdle()
        vm.editScore(repo.ids[2], 1)
        assertTrue(vm.uiState.value.scoreDialog!!.readOnly)
        assertFalse(vm.uiState.value.scoreDialog!!.canScan)
        vm.openScanner(); advanceUntilIdle()
        vm.deleteScore(); advanceUntilIdle()
        assertNull(vm.uiState.value.scoreDialog)
        assertNull(repo.snapshot.value!!.openRound!!.draft.entries[repo.ids[2]])
    }

    @Test fun persistedScanAndExplicitCorrectionKeepValidatedCompositeAndSaveClosesOnce() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler), recognitionAvailable = { true })
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("8"); vm.confirmScore(); advanceUntilIdle()
        val events = mutableListOf<MatchEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.addScore(repo.ids[1]); vm.openScanner(); advanceUntilIdle()
        val request = (events.single() as MatchEvent.OpenScanner).request
        assertNull(request.preparedRound); assertNull(request.correctionScoreId)
        repo.submitScore(SubmitScore(request.matchId, request.roundId, request.participantId, 6, request.submissionToken))
        vm.scannerSaved(request.submissionToken, 6); advanceUntilIdle()
        assertNull(vm.uiState.value.scoreDialog)
        vm.editScore(repo.ids[0], 1); vm.openScanner(); advanceUntilIdle()
        val correction = (events.last() as MatchEvent.OpenScanner).request
        assertEquals(repo.ids[0].value, correction.correctionScoreId)
        assertEquals(request.roundId, correction.roundId)
        vm.scannerSaved(request.submissionToken, 6); advanceUntilIdle()
        assertNotNull(vm.uiState.value.scoreDialog)
        vm.scannerCancelled(correction.submissionToken)
        vm.deleteScore(); advanceUntilIdle()
        assertNull(repo.snapshot.value!!.openRound!!.draft.entries[repo.ids[0]])
    }

    @Test fun savedSignalWaitsForRepositoryObservationBeforeClosingDialog() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler), recognitionAvailable = { true })
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.openScanner(); advanceUntilIdle()
        val request = (vm.events.first() as MatchEvent.OpenScanner).request
        val completion = launch { vm.scannerSaved(request.submissionToken, 7) }
        runCurrent()
        assertFalse(completion.isCompleted)
        assertNotNull(vm.uiState.value.scoreDialog)
        repo.submitScore(SubmitScore(request.matchId, request.roundId, request.participantId, 7, request.submissionToken, request.preparedRound))
        advanceUntilIdle()
        assertTrue(completion.isCompleted)
        assertNull(vm.uiState.value.scoreDialog)
    }

    @Test fun activeRoundAllowsAddButNotReorder() = runTest {
        val repo = ScoringRepository()
        val initial = repo.snapshot.value!!
        repo.snapshot.value = initial.copy(rounds = listOf(repo.round(1, RoundDraft(repo.ids))))
        val vm = MatchViewModel(initial.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(vm.uiState.value.canAddPlayer)
        assertFalse(vm.uiState.value.canReorderPlayers)
        vm.openAddPlayer()
        assertEquals("", vm.uiState.value.playerName)
        assertFalse(vm.uiState.value.canAddPlayer)
        vm.updatePlayerName(" Dora ")
        vm.openAddPlayer()
        assertEquals(" Dora ", vm.uiState.value.playerName)
        vm.addScore(repo.ids[0])
        assertNull(vm.uiState.value.scoreDialog)
        vm.dismissDialog()
        vm.reorderPlayers(repo.ids.reversed()); vm.movePlayer(repo.ids[0], 1)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasError)
        assertEquals(initial.participants.map { it.id }, vm.uiState.value.players.map { it.id })
    }

    @Test fun preparedWildcardSurvivesScoreDismissAndPlayerJoin() = runTest {
        val repo = ScoringRepository(allowJoin = true)
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        val preparedId = vm.uiState.value.scoreDialog!!.roundId
        val indicator = Indicator(Rank.KING, CardColor.BLACK)
        assertEquals(indicator, vm.uiState.value.indicator)
        assertEquals(1, vm.uiState.value.roundNumber)
        assertFalse(vm.uiState.value.canAddPlayer)
        vm.dismissDialog()
        assertNull(vm.uiState.value.scoreDialog)
        assertEquals(indicator, vm.uiState.value.indicator)
        assertEquals(1, vm.uiState.value.roundNumber)
        assertTrue(vm.uiState.value.canAddPlayer)
        assertTrue(repo.snapshot.value!!.rounds.isEmpty())
        vm.openAddPlayer(); vm.updatePlayerName(" Dora ")
        assertEquals(" Dora ", vm.uiState.value.playerName)
        vm.confirmPlayerName(); advanceUntilIdle()
        assertNull(vm.uiState.value.playerName)
        assertEquals("Dora", vm.uiState.value.players.last().name)
        assertTrue(vm.uiState.value.canAddPlayer)
        assertEquals(indicator, vm.uiState.value.indicator)
        assertEquals(1, vm.uiState.value.roundNumber)
        assertTrue(repo.snapshot.value!!.rounds.isEmpty())
        vm.addScore(repo.ids.last())
        assertNull(vm.uiState.value.picker)
        assertEquals(preparedId, vm.uiState.value.scoreDialog!!.roundId)
        assertEquals(indicator, vm.uiState.value.scoreDialog!!.indicator)
        assertEquals(1, vm.uiState.value.scoreDialog!!.roundNumber)
        vm.updateScore("7"); vm.confirmScore(); advanceUntilIdle()
        assertEquals(indicator, repo.snapshot.value!!.openRound!!.indicator)
        assertEquals(7, vm.uiState.value.players.last().history.single().score)
        for (id in repo.ids.take(2)) {
            vm.addScore(id); vm.updateScore("3"); vm.confirmScore(); advanceUntilIdle()
        }
        assertEquals(2, vm.uiState.value.roundNumber)
        assertNull(vm.uiState.value.indicator)
        vm.addScore(repo.ids[0])
        assertNotNull(vm.uiState.value.picker)
        assertNull(vm.uiState.value.scoreDialog)
    }

    @Test fun preparedWildcardSurvivesFailedAndSuccessfulReorder() = runTest {
        val storage = ScoringRepository()
        val initial = storage.snapshot.value!!
        val original = storage.ids
        val reordered = original.reversed()
        var fail = true
        val repo = object : MatchRepository by storage {
            override suspend fun reorderParticipants(command: ReorderParticipants) {
                if (fail) error("Unavailable")
                storage.snapshot.value = storage.snapshot.value!!.copy(participants = command.participantIds.mapIndexed { index, id ->
                    initial.participants.single { it.id == id }.copy(rosterOrder = index)
                })
            }
        }
        val vm = MatchViewModel(initial.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, original[0]); advanceUntilIdle()
        val preparedId = vm.uiState.value.scoreDialog!!.roundId
        val indicator = Indicator(Rank.KING, CardColor.BLACK)
        vm.dismissDialog()
        for (shouldFail in listOf(true, false)) {
            fail = shouldFail
            assertTrue(vm.uiState.value.canReorderPlayers)
            vm.reorderPlayers(reordered); advanceUntilIdle()
            assertEquals(shouldFail, vm.uiState.value.hasError)
            assertEquals(if (shouldFail) original else reordered, vm.uiState.value.players.map { it.id })
            assertEquals(indicator, vm.uiState.value.indicator)
            assertEquals(1, vm.uiState.value.roundNumber)
            assertTrue(storage.snapshot.value!!.rounds.isEmpty())
            vm.addScore(original[1])
            assertNull(vm.uiState.value.picker)
            assertEquals(preparedId, vm.uiState.value.scoreDialog!!.roundId)
            assertEquals(indicator, vm.uiState.value.scoreDialog!!.indicator)
            assertEquals(1, vm.uiState.value.scoreDialog!!.roundNumber)
            vm.dismissDialog()
        }
    }

    @Test fun joinAfterDesignatedWinnerRendersCurrentRoundEligibility() = runTest {
        val repo = ScoringRepository(allowJoin = true)
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.wentOut(); advanceUntilIdle()
        vm.openAddPlayer(); vm.updatePlayerName("Dora")
        assertEquals("Dora", vm.uiState.value.playerName)
        vm.confirmPlayerName(); advanceUntilIdle()
        assertNull(vm.uiState.value.playerName)
        assertEquals(4, vm.uiState.value.players.size)
        val joined = vm.uiState.value.players.last()
        assertEquals("Dora", joined.name)
        assertTrue(joined.canAddScore)
        assertEquals(RoundStatusKind.EMPTY, joined.history.single().status)
        assertEquals(1, vm.uiState.value.roundNumber)
        assertEquals(Indicator(Rank.KING, CardColor.BLACK), vm.uiState.value.indicator)
        assertEquals(RoundStatusKind.WINNER, vm.uiState.value.players.first().history.single().status)
        vm.addScore(joined.id)
        assertFalse(vm.uiState.value.scoreDialog!!.wentOutEnabled)
        assertEquals(1, vm.uiState.value.scoreDialog!!.roundNumber)
    }

    @Test fun failedActiveRoundJoinPreservesTypedName() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("8"); vm.confirmScore(); advanceUntilIdle()
        vm.openAddPlayer(); vm.updatePlayerName(" Dora "); vm.confirmPlayerName(); advanceUntilIdle()
        assertTrue(vm.uiState.value.hasError)
        assertEquals(" Dora ", vm.uiState.value.playerName)
        assertEquals(Indicator(Rank.KING, CardColor.BLACK), vm.uiState.value.indicator)
        assertEquals(1, vm.uiState.value.roundNumber)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test fun pendingJoinBlocksDuplicateSavesAndOtherModalActions() = runTest {
        val storage = ScoringRepository(allowJoin = true)
        val gate = CompletableDeferred<Unit>()
        val repo = object : MatchRepository by storage {
            override suspend fun addPlayerToMatch(command: AddPlayerToMatch): ParticipantId {
                gate.await()
                return storage.addPlayerToMatch(command)
            }
        }
        val vm = MatchViewModel(storage.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, storage.ids[0]); advanceUntilIdle()
        vm.dismissDialog(); vm.openAddPlayer(); vm.updatePlayerName(" Dora ")
        vm.confirmPlayerName(); vm.confirmPlayerName(); runCurrent()
        assertTrue(vm.uiState.value.isSaving)
        assertFalse(vm.uiState.value.canAddPlayer)
        assertFalse(vm.uiState.value.canReorderPlayers)
        vm.openAddPlayer(); vm.dismissDialog(); vm.addScore(storage.ids[1]); vm.updatePlayerName("Eva")
        assertEquals(" Dora ", vm.uiState.value.playerName)
        assertNull(vm.uiState.value.picker)
        assertNull(vm.uiState.value.scoreDialog)
        assertEquals(Indicator(Rank.KING, CardColor.BLACK), vm.uiState.value.indicator)
        assertEquals(1, vm.uiState.value.roundNumber)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(4, vm.uiState.value.players.size)
        assertEquals("Dora", vm.uiState.value.players.last().name)
        assertEquals(1, storage.lateJoinAttempts)
        assertFalse(vm.uiState.value.isSaving)
        assertTrue(vm.uiState.value.canAddPlayer)
    }

    @Test fun failedJoinPreservesPreparedWildcardAndTypedNameForRetry() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        val preparedId = vm.uiState.value.scoreDialog!!.roundId
        vm.dismissDialog()
        vm.openAddPlayer(); vm.updatePlayerName(" Dora "); vm.confirmPlayerName(); advanceUntilIdle()
        assertTrue(vm.uiState.value.hasError)
        assertEquals(" Dora ", vm.uiState.value.playerName)
        assertEquals(Indicator(Rank.KING, CardColor.BLACK), vm.uiState.value.indicator)
        assertEquals(1, vm.uiState.value.roundNumber)
        assertTrue(repo.snapshot.value!!.rounds.isEmpty())
        vm.dismissDialog(); vm.addScore(repo.ids[1])
        assertNull(vm.uiState.value.picker)
        assertEquals(preparedId, vm.uiState.value.scoreDialog!!.roundId)
    }

    @Test fun directlySelectedJokerIsConvertedToCompatibleIndicatorAndFailedConfirmKeepsSelectionForRetry() = runTest {
        val storage = ScoringRepository()
        var choices = 0
        var fail = true
        val repository = object : MatchRepository by storage {
            override suspend fun chooseIndicator(command: ChooseIndicator): PreparedRound {
                choices++
                if (fail) error("Unavailable")
                return storage.chooseIndicator(command)
            }
        }
        val vm = MatchViewModel(storage.snapshot.value!!.id, repository, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.addScore(storage.ids[0]); vm.selectIndicatorRank(Rank.ACE); vm.confirmIndicator(); advanceUntilIdle()
        assertNull(vm.uiState.value.picker!!.indicator)
        assertEquals(0, choices)
        vm.dismissDialog()
        assertTrue(storage.snapshot.value!!.rounds.isEmpty())
        vm.addScore(storage.ids[0]); vm.selectIndicatorColor(CardColor.BLACK); vm.confirmIndicator(); advanceUntilIdle()
        assertNull(vm.uiState.value.picker!!.indicator)
        assertEquals(0, choices)
        vm.selectIndicatorRank(Rank.ACE); vm.confirmIndicator(); advanceUntilIdle()
        assertEquals(Indicator(Rank.KING, CardColor.BLACK), vm.uiState.value.picker!!.indicator)
        assertTrue(vm.uiState.value.hasError)
        assertEquals(1, choices)
        assertTrue(storage.snapshot.value!!.rounds.isEmpty())
        fail = false
        vm.confirmIndicator(); advanceUntilIdle()
        assertEquals(Indicator(Rank.KING, CardColor.BLACK), vm.uiState.value.scoreDialog!!.indicator)
        assertTrue(storage.snapshot.value!!.rounds.isEmpty())
        vm.dismissDialog()
        assertTrue(storage.snapshot.value!!.rounds.isEmpty())
    }

    @Test fun finalPermutationIsValidatedCommittedOnceAndFailureKeepsCommittedOrder() = runTest {
        val storage = ScoringRepository()
        val initial = storage.snapshot.value!!
        val original = storage.ids
        val final = listOf(original[1], original[2], original[0])
        val gate = CompletableDeferred<Unit>()
        val commands = mutableListOf<ReorderParticipants>()
        var fail = false
        val repository = object : MatchRepository by storage {
            override suspend fun reorderParticipants(command: ReorderParticipants) {
                commands += command
                gate.await()
                if (fail) error("Unavailable")
                storage.snapshot.value = storage.snapshot.value!!.copy(participants = command.participantIds.mapIndexed { index, id ->
                    initial.participants.single { it.id == id }.copy(rosterOrder = index)
                })
            }
        }
        val vm = MatchViewModel(initial.id, repository, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.reorderPlayers(original); vm.reorderPlayers(original.take(2)); vm.reorderPlayers(listOf(original[0], original[0], original[2]))
        runCurrent(); assertTrue(commands.isEmpty())
        vm.reorderPlayers(final); vm.reorderPlayers(original.reversed()); runCurrent()
        assertEquals(listOf(ReorderParticipants(initial.id, final)), commands)
        assertEquals(original, vm.uiState.value.players.map { it.id })
        assertTrue(vm.uiState.value.isSaving)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(final, vm.uiState.value.players.map { it.id })
        fail = true
        vm.reorderPlayers(original); advanceUntilIdle()
        assertEquals(final, vm.uiState.value.players.map { it.id })
        assertTrue(vm.uiState.value.hasError)
        assertFalse(vm.uiState.value.isSaving)
        for (status in listOf(RoundStatus.OPEN, RoundStatus.PAUSED)) {
            storage.snapshot.value = initial.copy(rounds = listOf(storage.round(1, RoundDraft(original)).copy(status = status)))
            runCurrent(); vm.reorderPlayers(final); advanceUntilIdle()
        }
        storage.snapshot.value = initial.copy(isFinished = true)
        runCurrent(); vm.reorderPlayers(final); advanceUntilIdle()
        assertEquals(2, commands.size)
    }

    @Test fun repositoryDuplicateShowsInlineErrorAndStopsRetryingAfterRosterRefresh() = runTest {
        val storage = ScoringRepository()
        val initial = storage.snapshot.value!!
        var attempts = 0
        val repo = object : MatchRepository by storage {
            override suspend fun addPlayerToMatch(command: AddPlayerToMatch): ParticipantId {
                attempts++
                throw DuplicatePlayerNameException()
            }
        }
        val vm = MatchViewModel(initial.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.openAddPlayer(); vm.updatePlayerName(" dORA "); vm.confirmPlayerName()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.duplicateNameError)
        assertFalse(vm.uiState.value.hasError)
        assertEquals(" dORA ", vm.uiState.value.playerName)
        assertFalse(vm.uiState.value.isSaving)
        storage.snapshot.value = initial.copy(
            participants = initial.participants + initial.participants.last().copy(
                id = ParticipantId("joined"), playerId = PlayerId("dora"), displayName = "Dora", rosterOrder = 3),
            totals = initial.totals + (ParticipantId("joined") to 0),
        )
        runCurrent()
        vm.confirmPlayerName(); advanceUntilIdle()
        assertEquals(1, attempts)
        assertTrue(vm.uiState.value.duplicateNameError)
        assertEquals(" dORA ", vm.uiState.value.playerName)
        vm.updatePlayerName("Eva")
        assertFalse(vm.uiState.value.duplicateNameError)
    }

    @Test fun editingFailedLateJoinValidatesTheNewNameAgain() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.openAddPlayer(); vm.updatePlayerName(" Dora "); vm.confirmPlayerName()
        advanceUntilIdle()
        assertEquals(" Dora ", vm.uiState.value.playerName)
        assertTrue(vm.uiState.value.hasError)
        vm.updatePlayerName(" ANA "); vm.confirmPlayerName()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.duplicateNameError)
        assertFalse(vm.uiState.value.hasError)
        assertEquals(1, repo.lateJoinAttempts)
    }

    @Test fun lateJoinRejectsNormalizedDuplicateAndRetainsTypedText() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.openAddPlayer(); vm.updatePlayerName(" aNA "); vm.confirmPlayerName()
        advanceUntilIdle()
        assertEquals(" aNA ", vm.uiState.value.playerName)
        assertTrue(vm.uiState.value.duplicateNameError)
        assertFalse(vm.uiState.value.hasError)
        assertEquals(0, repo.lateJoinAttempts)
        vm.updatePlayerName("Dora")
        assertFalse(vm.uiState.value.duplicateNameError)
    }

    @Test fun rosterGuardsTrackCorrectionPausedAndFinishedState() = runTest {
        val repo = ScoringRepository()
        val initial = repo.snapshot.value!!
        val vm = MatchViewModel(initial.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(vm.uiState.value.canAddPlayer)
        assertTrue(vm.uiState.value.canReorderPlayers)
        vm.openAddPlayer()
        assertEquals("", vm.uiState.value.playerName)
        vm.dismissDialog()
        for (round in listOf(
            repo.round(1, RoundDraft(repo.ids)).copy(isCorrection = true),
            repo.round(1, RoundDraft(repo.ids)).copy(status = RoundStatus.PAUSED),
        )) {
            repo.snapshot.value = initial.copy(rounds = listOf(round))
            runCurrent()
            assertFalse(vm.uiState.value.canAddPlayer)
            assertFalse(vm.uiState.value.canReorderPlayers)
            vm.openAddPlayer(); vm.movePlayer(repo.ids[0], 1)
            assertNull(vm.uiState.value.playerName)
        }
        repo.snapshot.value = initial.copy(isFinished = true)
        runCurrent()
        assertFalse(vm.uiState.value.canAddPlayer)
        assertFalse(vm.uiState.value.canReorderPlayers)
    }

    @Test fun correctionsPreloadExplicitScoreAndProtectAutomaticZero() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("101"); vm.confirmScore(); advanceUntilIdle()
        vm.addScore(repo.ids[1]); vm.updateScore("2"); vm.confirmScore(); advanceUntilIdle()
        vm.editScore(repo.ids[0], 1)
        assertEquals("101", vm.uiState.value.scoreDialog!!.value)
        assertTrue(vm.uiState.value.scoreDialog!!.isCorrection)
        assertFalse(vm.uiState.value.scoreDialog!!.readOnly)
        vm.updateScore("5"); vm.confirmScore(); advanceUntilIdle()
        assertFalse(vm.uiState.value.isFinished)
        assertEquals(5, vm.uiState.value.players[0].total)
        assertEquals(RoundEntryOrigin.EXPLICIT, repo.snapshot.value!!.rounds[0].draft.entries[repo.ids[0]]!!.origin)
        vm.editScore(repo.ids[2], 1)
        assertTrue(vm.uiState.value.scoreDialog!!.readOnly)
        assertEquals("0", vm.uiState.value.scoreDialog!!.value)
        vm.updateScore("20"); vm.confirmScore(); vm.wentOut(); advanceUntilIdle()
        assertEquals("0", vm.uiState.value.scoreDialog!!.value)
        vm.deleteScore(); advanceUntilIdle()
        assertNull(repo.snapshot.value!!.openRound!!.draft.winner)
        assertNull(repo.snapshot.value!!.openRound!!.draft.entries[repo.ids[2]])
        assertFalse(vm.uiState.value.canReorderPlayers)
        assertEquals(listOf(0, 0, 0), vm.uiState.value.players.map { it.total })
        vm.addScore(repo.ids[2]); vm.wentOut(); advanceUntilIdle()
        assertEquals(RoundStatus.COMPLETE, repo.snapshot.value!!.rounds[0].status)
    }

    @Test fun pickerAndScoreCancellationPersistNoRound() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.addScore(repo.ids[0])
        assertEquals(repo.ids[0], vm.uiState.value.picker?.participantId)
        vm.dismissDialog()
        assertNull(vm.uiState.value.picker)
        assertTrue(repo.snapshot.value!!.rounds.isEmpty())
        vm.addScore(repo.ids[0])
        vm.selectIndicatorRank(Rank.KING); vm.selectIndicatorColor(CardColor.RED)
        vm.confirmIndicator()
        advanceUntilIdle()
        assertEquals(Indicator(Rank.QUEEN, CardColor.RED), vm.uiState.value.scoreDialog?.indicator)
        assertTrue(repo.snapshot.value!!.rounds.isEmpty())
        vm.dismissDialog()
        assertTrue(repo.snapshot.value!!.rounds.isEmpty())
    }

    @Test fun explicitZeroIsPendingUntilInferredWinnerClosesRoundAndAdvancesHand() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("0"); vm.confirmScore(); advanceUntilIdle()
        assertEquals(1, repo.snapshot.value!!.rounds.size)
        assertEquals(RoundStatusKind.PENDING, vm.uiState.value.players[0].history[0].status)
        assertEquals(0, vm.uiState.value.players[0].history[0].score)
        assertFalse(vm.uiState.value.players[0].canAddScore)
        vm.addScore(repo.ids[1])
        assertNull(vm.uiState.value.picker)
        assertNotNull(vm.uiState.value.scoreDialog)
        vm.updateScore("12"); vm.confirmScore(); advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(RoundStatusKind.LOSS, state.players[0].history[0].status)
        assertEquals(0, state.players[0].history[0].score)
        assertEquals(RoundStatusKind.WINNER, state.players[2].history[0].status)
        assertEquals(0, state.players[2].history[0].score)
        assertEquals(listOf(false, true, false), state.players.map { it.isDealer })
        assertEquals(2, state.roundNumber)
        assertEquals(listOf(0, 12, 0), state.players.map { it.total })
    }

    @Test fun bateuMarksOnlyOneWinnerWithoutClosingOrAdvancingUntilOtherScoresExist() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.wentOut(); advanceUntilIdle()
        assertFalse(repo.snapshot.value!!.rounds.single().draft.isComplete)
        assertEquals(RoundStatusKind.WINNER, vm.uiState.value.players[0].history[0].status)
        assertEquals(RoundStatusKind.EMPTY, vm.uiState.value.players[1].history[0].status)
        assertEquals(repo.ids[0], vm.uiState.value.players.single { it.isDealer }.id)
        vm.addScore(repo.ids[1])
        assertFalse(vm.uiState.value.scoreDialog!!.wentOutEnabled)
        vm.wentOut(); advanceUntilIdle()
        assertEquals(repo.ids[0], repo.snapshot.value!!.openRound!!.draft.designatedWinner)
        vm.updateScore("2"); vm.confirmScore(); advanceUntilIdle()
        vm.addScore(repo.ids[2]); vm.updateScore("3"); vm.confirmScore(); advanceUntilIdle()
        assertTrue(repo.snapshot.value!!.rounds.single().draft.isComplete)
        assertEquals(0, vm.uiState.value.players[0].history[0].score)
    }

    @Test fun invalidInputIsRetainedAndDuplicateTapCommitsOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = ScoringRepository(gate = gate)
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        for (invalid in listOf("", "-1", "2147483648", "12x")) {
            vm.updateScore(invalid); vm.confirmScore(); runCurrent()
            assertEquals(invalid, vm.uiState.value.scoreDialog!!.value)
            assertFalse(vm.uiState.value.scoreDialog!!.canConfirm)
            assertTrue(repo.snapshot.value!!.rounds.isEmpty())
        }
        vm.updateScore("2147483647")
        assertTrue(vm.uiState.value.scoreDialog!!.canConfirm)
        vm.updateScore("4"); vm.confirmScore(); vm.confirmScore(); runCurrent()
        assertTrue(vm.uiState.value.isSaving)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, repo.submissions)
        assertEquals(4, vm.uiState.value.players[0].history[0].score)
        assertNull(vm.uiState.value.scoreDialog)
    }

    @Test fun restoredOpenRoundSkipsPickerAndPausedRoundIsNotCurrent() = runTest {
        val repo = ScoringRepository()
        val initial = repo.snapshot.value!!
        val open = repo.round(1, RoundDraft(repo.ids, mapOf(repo.ids[0] to RoundEntry(0, RoundEntryOrigin.EXPLICIT))))
        val paused = repo.round(2, RoundDraft(repo.ids)).copy(status = RoundStatus.PAUSED)
        repo.snapshot.value = initial.copy(rounds = listOf(open.copy(isCorrection = true), paused))
        val vm = MatchViewModel(initial.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(1, vm.uiState.value.roundNumber)
        assertFalse(vm.uiState.value.players[0].canAddScore)
        vm.addScore(repo.ids[1])
        assertNull(vm.uiState.value.picker)
        assertEquals(open.id, vm.uiState.value.scoreDialog!!.roundId)
        assertEquals(open.indicator, vm.uiState.value.scoreDialog!!.indicator)
    }

    @Test fun finishedSnapshotHidesScoringAndEmitsResultsOnlyOncePerRevision() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("101"); vm.confirmScore(); advanceUntilIdle()
        assertFalse(vm.uiState.value.isFinished)
        vm.addScore(repo.ids[1]); vm.updateScore("1"); vm.confirmScore(); advanceUntilIdle()
        assertTrue(vm.uiState.value.isFinished)
        assertTrue(vm.uiState.value.players.none { it.canAddScore })
        assertEquals(MatchEvent.OpenResults(repo.snapshot.value!!.id), vm.events.first())
        repo.snapshot.value = repo.snapshot.value!!.copy(lastPlayedAt = Instant.ofEpochSecond(50))
        runCurrent()
        var additional = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { additional++ } }
        runCurrent()
        assertEquals(0, additional)
        vm.addScore(repo.ids[0]); assertNull(vm.uiState.value.picker)
        vm.viewResults(); runCurrent(); assertEquals(1, additional)
    }

    @Test fun lateParticipantHasNonparticipationAndPausedOnlySnapshotCannotStartRound() = runTest {
        val repo = ScoringRepository()
        val initial = repo.snapshot.value!!
        repo.snapshot.value = initial.copy(
            participants = initial.participants.mapIndexed { i, p -> if (i == 2) p.copy(eligibleFromRoundOrdinal = 2) else p },
            rounds = listOf(repo.round(1, RoundDraft(repo.ids.take(2))).copy(status = RoundStatus.PAUSED)),
        )
        val vm = MatchViewModel(initial.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(RoundStatusKind.NOT_PARTICIPATING, vm.uiState.value.players[2].history[0].status)
        assertTrue(vm.uiState.value.players.none { it.canAddScore })
        vm.addScore(repo.ids[0]); assertNull(vm.uiState.value.picker)
    }

    private fun prepare(vm: MatchViewModel, id: ParticipantId) {
        vm.addScore(id)
        vm.selectIndicatorRank(Rank.ACE); vm.selectIndicatorColor(CardColor.BLACK)
        vm.confirmIndicator()
    }
}

/** In-memory storage boundary; real RoundEngine handles all transition rules. */
private class ScoringRepository(
    private val gate: CompletableDeferred<Unit>? = null,
    private val allowJoin: Boolean = false,
) : MatchRepository {
    val snapshot = MutableStateFlow<MatchSnapshot?>(matchSnapshot("match", LocalDate.of(2026, 10, 14), 2,
        listOf("a" to "Ana", "b" to "Bruno", "c" to "Carla")))
    val ids get() = snapshot.value!!.participants.map { it.id }
    var submissions = 0
    var lateJoinAttempts = 0
    override fun observeMatch(matchId: MatchId) = snapshot
    override fun observeHome() = MutableStateFlow(emptyList<DaySummary>())
    override fun observeDay(localDate: LocalDate) = MutableStateFlow(DayHistory(localDate, emptyList(), emptyList(), emptyList()))
    override suspend fun chooseIndicator(command: ChooseIndicator): PreparedRound {
        check(snapshot.value!!.rounds.none { it.status != RoundStatus.COMPLETE })
        val ordinal = snapshot.value!!.rounds.size + 1
        return PreparedRound(RoundId("r$ordinal"), command.matchId, ordinal, command.indicator)
    }
    fun round(ordinal: Int, draft: RoundDraft) = RoundSnapshot(RoundId("r$ordinal"), ordinal,
        Indicator(Rank.ACE, CardColor.BLACK), ids[0], draft, Instant.EPOCH, null, RoundStatus.OPEN, false)
    private fun writable(id: RoundId, prepared: PreparedRound?): RoundSnapshot =
        snapshot.value!!.rounds.singleOrNull { it.id == id } ?: requireNotNull(prepared).let {
            round(it.ordinal, RoundDraft(ids)).copy(id = it.id, indicator = it.indicator,
                dealerParticipantId = snapshot.value!!.dealerParticipantId)
        }
    override suspend fun submitScore(command: SubmitScore) {
        gate?.await()
        val r = writable(command.roundId, command.preparedRound)
        val transition = RoundEngine.submitScore(r.draft, command.participantId, command.points)
        check(transition is RoundTransition.Applied)
        submissions++
        persist(r, transition.state)
    }
    override suspend fun designateWinner(command: DesignateWinner) {
        val r = writable(command.roundId, command.preparedRound)
        persist(r, RoundEngine.designateWinner(r.draft, command.participantId))
    }
    private fun persist(r: RoundSnapshot, draft: RoundDraft) {
        val before = snapshot.value!!
        val completed = draft.isComplete
        val next = r.copy(draft = draft, status = if (completed) RoundStatus.COMPLETE else RoundStatus.OPEN,
            completedAt = if (completed) Instant.EPOCH else null)
        val rounds = (before.rounds.filterNot { it.id == r.id } + next).sortedBy { it.ordinal }
        val totals = ids.associateWith { id -> rounds.filter { it.status == RoundStatus.COMPLETE }.sumOf { it.draft.entries[id]?.points ?: 0 } }
        val finished = totals.values.any { it > 100 }
        snapshot.value = before.copy(rounds = rounds, totals = totals,
            dealerParticipantId = if (completed) ids[(ids.indexOf(r.dealerParticipantId) + 1) % ids.size] else before.dealerParticipantId,
            isFinished = finished, finishedAt = if (finished) Instant.EPOCH else null,
            resultRevision = if (finished) 1 else 0)
    }
    override suspend fun createMatch(command: CreateMatch): MatchId = error("unused")
    override suspend fun addParticipant(command: AddParticipant) = error("unused")
    override suspend fun addPlayerToMatch(command: AddPlayerToMatch): ParticipantId {
        lateJoinAttempts++
        if (!allowJoin) error("Storage unavailable")
        val before = snapshot.value!!
        val ordinal = before.openRound?.ordinal ?: ((before.rounds.maxOfOrNull { it.ordinal } ?: 0) + 1)
        val id = ParticipantId("joined-${command.operationToken}")
        val participant = before.participants.last().copy(id = id, playerId = PlayerId("joined-player"),
            displayName = command.displayName, rosterOrder = before.participants.size, eligibleFromRoundOrdinal = ordinal)
        snapshot.value = before.copy(participants = before.participants + participant,
            totals = before.totals + (id to 0),
            rounds = before.rounds.map { round ->
                if (round.status == RoundStatus.OPEN) round.copy(draft = round.draft.copy(
                    eligibleParticipants = round.draft.eligibleParticipants + id)) else round
            })
        return id
    }
    override suspend fun reorderParticipants(command: ReorderParticipants) = error("unused")
    override suspend fun removeRoundEntry(command: RemoveRoundEntry) {
        val r = snapshot.value!!.rounds.single { it.id == command.roundId }
        persist(r, (RoundEngine.removeEntry(r.draft, command.participantId) as RoundTransition.Applied).state)
    }
    override suspend fun updateExplicitScore(command: UpdateExplicitScore) {
        val r = snapshot.value!!.rounds.single { it.id == command.roundId }
        val old = r.draft.entries.getValue(command.participantId)
        require(old.origin == RoundEntryOrigin.EXPLICIT)
        persist(r, r.draft.copy(entries = r.draft.entries + (command.participantId to old.copy(points = command.points))))
    }
    override suspend fun acknowledgeResults(matchId: MatchId, revision: Long) = error("unused")
    override suspend fun deleteMatch(matchId: MatchId) = error("unused")
}
