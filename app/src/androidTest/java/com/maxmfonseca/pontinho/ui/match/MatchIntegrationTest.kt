package com.maxmfonseca.pontinho.ui.match

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import androidx.compose.runtime.SideEffect
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.repository.*
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.settings.*
import com.maxmfonseca.pontinho.ui.components.RoundStatusKind
import com.maxmfonseca.pontinho.ui.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class MatchIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: PontinhoDatabase
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PontinhoDatabase::class.java).build() }
    @After fun close() { stores.forEach { it.clear() }; db.close() }

    @Test fun lateJoinRecoversLostCommitAcknowledgementAndRefreshWithoutDuplicates() = runBlocking {
        for (failAfterCommit in listOf(true, false)) {
            val real = RoomMatchRepository(db)
            val id = real.createMatch(CreateMatch("retry-$failAfterCommit", listOf("Ana", "Bruno").mapIndexed { i, name -> CreateMatchPlayer("$i", displayName = name) }))
            var throwOnce = true
            var failRefresh = false
            val repository = object : MatchRepository by real {
                override suspend fun addPlayerToMatch(command: AddPlayerToMatch): ParticipantId {
                    val result = real.addPlayerToMatch(command)
                    if (throwOnce) {
                        throwOnce = false
                        if (failAfterCommit) throw java.io.IOException("Lost committed acknowledgement")
                        failRefresh = true
                    }
                    return result
                }
                override fun observeMatch(matchId: MatchId): Flow<MatchSnapshot?> {
                    if (failRefresh) {
                        failRefresh = false
                        return flow { throw java.io.IOException("First post-commit refresh failed") }
                    }
                    return real.observeMatch(matchId)
                }
            }
            // No PlayerRepository is injected: the ViewModel must use the atomic operation.
            val model = vm(id, repository)
            model.uiState.first { !it.isLoading }
            model.openAddPlayer(); model.updatePlayerName("Dora"); model.confirmPlayerName()
            withTimeout(5_000) { model.uiState.first { !it.isSaving && it.hasError } }
            val committed = real.observeMatch(id).first()!!
            assertEquals(3, committed.participants.size)
            val globalCount = db.playerDao().players().size
            model.confirmPlayerName()
            withTimeout(5_000) { model.uiState.first { !it.isSaving && !it.hasError && it.playerName == null } }
            assertEquals(committed, real.observeMatch(id).first())
            assertEquals(globalCount, db.playerDao().players().size)
            assertEquals(committed.participants.map { it.id }, model.uiState.value.players.map { it.id })
        }
    }

    @Test fun lateJoinFailureThenCancelAndReopenDoesNotLeaveAnOrphanIdentity() = runBlocking {
        val repository = RoomMatchRepository(db)
        val id = repository.createMatch(CreateMatch("cancel-reopen", listOf("Ana", "Bruno").mapIndexed { i, name -> CreateMatchPlayer("$i", displayName = name) }))
        val model = vm(id, repository)
        model.uiState.first { !it.isLoading }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_vm_join BEFORE INSERT ON participants BEGIN SELECT RAISE(ABORT, 'join failed'); END")
        model.openAddPlayer(); model.updatePlayerName("Dora"); model.confirmPlayerName()
        withTimeout(5_000) { model.uiState.first { !it.isSaving && it.hasError } }
        assertEquals(2, db.playerDao().players().size)
        assertEquals(2, repository.observeMatch(id).first()!!.participants.size)
        model.dismissDialog()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_vm_join")
        model.openAddPlayer(); model.updatePlayerName("Dora"); model.confirmPlayerName()
        withTimeout(5_000) { model.uiState.first { !it.isSaving && it.playerName == null } }
        assertEquals(3, db.playerDao().players().size)
        assertEquals(3, repository.observeMatch(id).first()!!.participants.size)
    }

    @Test fun lateJoinReorderAndHistoricalWinnerDeletionPauseAndResumeTheActualDraft() = runBlocking {
        val repo = RoomMatchRepository(db)
        val id = repo.createMatch(CreateMatch("late", listOf("Ana", "Bruno", "Carla").mapIndexed { i, name -> CreateMatchPlayer("$i", displayName = name) }))
        val model = vm(id, repo)
        val ids = model.uiState.first { !it.isLoading }.players.map { it.id }
        suspend fun settled() = withTimeout(5_000) { model.uiState.first { !it.isSaving && it.scoreDialog == null && it.playerName == null } }
        model.addScore(ids[0]); model.selectIndicator(Indicator(Rank.ACE, CardColor.BLACK)); model.confirmIndicator()
        model.uiState.first { it.scoreDialog != null && !it.isSaving }
        model.updateScore("10"); model.confirmScore(); settled()
        assertFalse(model.uiState.value.canManagePlayers)
        model.openAddPlayer(); model.movePlayer(ids[0], 1)
        assertNull(model.uiState.value.playerName)
        model.addScore(ids[1]); model.updateScore("20"); model.confirmScore(); settled()
        val original = repo.observeMatch(id).first()!!.rounds.single()
        model.openAddPlayer(); model.updatePlayerName(" Dora "); model.confirmPlayerName(); settled()
        val late = model.uiState.value.players.last()
        assertEquals("Dora", late.name)
        assertEquals(0, late.total)
        assertEquals(RoundStatusKind.NOT_PARTICIPATING, late.history[0].status)
        assertNull(late.history[0].score)
        assertTrue(late.canAddScore)
        model.movePlayer(late.id, -1); settled()
        assertEquals(listOf(ids[0], ids[1], late.id, ids[2]), model.uiState.value.players.map { it.id })
        assertEquals(original, repo.observeMatch(id).first()!!.rounds.single())
        model.addScore(late.id); model.selectIndicator(Indicator(Rank.KING, CardColor.RED)); model.confirmIndicator()
        model.uiState.first { it.scoreDialog != null && !it.isSaving }
        model.updateScore("7"); model.confirmScore(); settled()
        val later = repo.observeMatch(id).first()!!.openRound!!
        assertTrue(late.id in later.draft.eligibleParticipants)
        model.editScore(ids[2], 1)
        assertEquals("0", model.uiState.value.scoreDialog!!.value)
        assertTrue(model.uiState.value.scoreDialog!!.readOnly)
        model.deleteScore(); settled()
        var snapshot = repo.observeMatch(id).first()!!
        assertEquals(original.id, snapshot.openRound!!.id)
        assertNull(snapshot.openRound!!.draft.winner)
        assertEquals(later.draft, snapshot.pausedRounds.single().draft)
        assertEquals(1, model.uiState.value.roundNumber)
        assertEquals(listOf(2), model.uiState.value.pausedRoundNumbers)
        assertFalse(model.uiState.value.players.single { it.id == late.id }.canAddScore)
        assertFalse(model.uiState.value.canManagePlayers)
        model.editScore(ids[0], 1); model.deleteScore(); settled()
        model.addScore(ids[0]); model.wentOut(); settled()
        model.addScore(ids[2]); model.updateScore("3"); model.confirmScore(); settled()
        snapshot = repo.observeMatch(id).first()!!
        assertEquals(ids[0], snapshot.rounds[0].draft.designatedWinner)
        assertEquals(3, snapshot.rounds[0].draft.entries[ids[2]]!!.points)
        assertEquals(later, snapshot.openRound)
        assertTrue(model.uiState.value.pausedRoundNumbers.isEmpty())
        assertEquals(2, model.uiState.value.roundNumber)
        assertEquals(2, db.matchDao().corrections(id.value).size)
    }

    @Test fun finishedExplicitCorrectionReopensBelowThresholdAndWinnerTrashDoesNotInfer() = runBlocking {
        val repo = RoomMatchRepository(db)
        val id = repo.createMatch(CreateMatch("finished-edit", listOf("Ana", "Bruno").mapIndexed { i, name -> CreateMatchPlayer("$i", displayName = name) }))
        val model = vm(id, repo)
        val ids = model.uiState.first { !it.isLoading }.players.map { it.id }
        model.addScore(ids[0]); model.selectIndicator(Indicator(Rank.ACE, CardColor.BLACK)); model.confirmIndicator()
        model.uiState.first { it.scoreDialog != null && !it.isSaving }
        model.updateScore("101"); model.confirmScore()
        withTimeout(5_000) { model.uiState.first { it.isFinished && !it.isSaving } }
        val revision = repo.observeMatch(id).first()!!.resultRevision
        model.editScore(ids[0], 1); assertEquals("101", model.uiState.value.scoreDialog!!.value)
        model.updateScore("100"); model.confirmScore()
        withTimeout(5_000) { model.uiState.first { !it.isFinished && !it.isSaving } }
        var snapshot = repo.observeMatch(id).first()!!
        assertEquals(100, snapshot.totals[ids[0]])
        assertTrue(snapshot.resultRevision > revision)
        assertEquals("MANUAL", db.roundDao().scores(snapshot.rounds[0].id.value).single { it.participantId == ids[0].value }.origin)
        model.editScore(ids[1], 1); model.updateScore("9"); model.confirmScore()
        assertEquals("0", model.uiState.value.scoreDialog!!.value)
        model.deleteScore()
        withTimeout(5_000) { model.uiState.first { !it.isSaving && it.scoreDialog == null } }
        snapshot = repo.observeMatch(id).first()!!
        assertNull(snapshot.openRound!!.draft.winner)
        assertFalse(snapshot.openRound!!.draft.entries.containsKey(ids[1]))
        assertEquals(0, snapshot.totals[ids[0]])
        model.addScore(ids[1]); model.wentOut()
        withTimeout(5_000) { model.uiState.first { !it.isSaving && it.scoreDialog == null } }
        assertEquals(100, repo.observeMatch(id).first()!!.totals[ids[0]])
    }

    @Test fun actualRoomBoundaryRestoresZeroAndDesignationThenClosesAndNavigates() = runBlocking {
        val repository = RoomMatchRepository(db)
        val id = repository.createMatch(CreateMatch("task6", listOf("Ana", "Bruno", "Carla").mapIndexed { i, name -> CreateMatchPlayer("$i", displayName = name) }))
        val vm = vm(id, repository)
        val initial = vm.uiState.first { !it.isLoading }
        val ids = initial.players.map { it.id }
        vm.addScore(ids[0]); vm.selectIndicator(Indicator(Rank.KING, CardColor.RED)); vm.confirmIndicator()
        vm.uiState.first { it.scoreDialog != null && !it.isSaving }
        vm.dismissDialog()
        assertTrue(repository.observeMatch(id).first()!!.rounds.isEmpty())
        vm.addScore(ids[0]); vm.selectIndicator(Indicator(Rank.KING, CardColor.RED)); vm.confirmIndicator()
        vm.uiState.first { it.scoreDialog != null && !it.isSaving }
        vm.updateScore("0"); vm.confirmScore(); vm.confirmScore()
        vm.uiState.first { !it.isSaving && it.scoreDialog == null }
        assertEquals(1, repository.observeMatch(id).first()!!.rounds.size)
        stores.first().clear()
        val restored = vm(id, RoomMatchRepository(db))
        restored.uiState.first { !it.isLoading }
        assertEquals(RoundStatusKind.PENDING, restored.uiState.value.players[0].history[0].status)
        restored.addScore(ids[1])
        assertNull(restored.uiState.value.picker)
        assertEquals(Indicator(Rank.KING, CardColor.RED), restored.uiState.value.scoreDialog!!.indicator)
        restored.wentOut()
        restored.uiState.first { !it.isSaving && it.scoreDialog == null }
        assertFalse(repository.observeMatch(id).first()!!.isFinished)
        restored.addScore(ids[2]); assertFalse(restored.uiState.value.scoreDialog!!.wentOutEnabled)
        restored.updateScore("101"); restored.confirmScore()
        withTimeout(5_000) { restored.uiState.first { it.isFinished && !it.isSaving } }
        assertEquals(MatchEvent.OpenResults(id), withTimeout(5_000) { restored.events.first() })
        assertEquals(listOf(0, 0, 101), restored.uiState.value.players.map { it.total })
        assertEquals(listOf(false, true, false), restored.uiState.value.players.map { it.isDealer })
    }

    @Test fun encodedRoutePassesExactMatchIdToRepositoryAndKeepsResultsContract() = runBlocking {
        val real = RoomMatchRepository(db)
        val id = real.createMatch(CreateMatch("route", listOf(CreateMatchPlayer("a", displayName = "Ana"), CreateMatchPlayer("b", displayName = "Bruno"))))
        val special = MatchId("a/b + c?d#e%25 ç")
        val snapshot = real.observeMatch(id).first()!!.copy(id = special)
        val requested = mutableListOf<MatchId>()
        val repo = object : MatchRepository by real {
            override fun observeMatch(matchId: MatchId): Flow<MatchSnapshot?> {
                requested += matchId
                return MutableStateFlow(snapshot)
            }
        }
        lateinit var controller: NavHostController
        compose.setContent {
            val nav = rememberNavController()
            SideEffect { controller = nav }
            PontinhoNavHost(container(repo), navController = nav, startDestination = Destination.Match.createRoute(special.value))
        }
        compose.onNodeWithText("Ana").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(special), requested.distinct()) }
        compose.runOnIdle { controller.navigate(Destination.Results.createRoute(special.value)) }
        compose.onNodeWithTag("results_root").assertIsDisplayed()
        compose.runOnIdle { assertEquals(special.value, controller.currentBackStackEntry!!.arguments!!.getString(Destination.Results.MATCH_ID)) }
    }

    private fun vm(id: MatchId, repo: MatchRepository): MatchViewModel = MatchViewModel(id, repo, Dispatchers.Unconfined).also {
        val store = ViewModelStore(); store.put("match", it); stores += store
    }
    private fun container(repo: MatchRepository) = object : AppContainer {
        override val playerRepository = RoomPlayerRepository(db)
        override val matchRepository = repo
        override val preferencesRepository = object : PreferencesRepository {
            override val preferences = MutableStateFlow(AppPreferences())
            override suspend fun setTheme(theme: ThemePreference) = Unit
            override suspend fun setLanguage(language: LanguagePreference) = Unit
            override suspend fun setReducedMotion(reducedMotion: Boolean?) = Unit
        }
    }
}
