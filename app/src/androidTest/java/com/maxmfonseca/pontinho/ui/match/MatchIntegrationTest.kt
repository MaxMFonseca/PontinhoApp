package com.maxmfonseca.pontinho.ui.match

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import androidx.compose.runtime.SideEffect
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.repository.*
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.settings.*
import com.maxmfonseca.pontinho.ui.components.RoundStatusKind
import com.maxmfonseca.pontinho.ui.navigation.*
import com.maxmfonseca.pontinho.ui.setup.MatchSetupViewModel
import com.maxmfonseca.pontinho.ui.setup.MatchSetupEvent
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

    @Test fun duplicateDialogsShowLocalizedInlineErrorKeepInputAndAllowCorrection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = RoomMatchRepository(db)
        compose.setContent { PontinhoNavHost(container(repo), startDestination = Destination.Setup.route) }
        fun addSetupName(name: String) {
            compose.onNodeWithText(context.getString(R.string.setup_add_player)).performScrollTo().performClick()
            compose.onNodeWithTag("player_name_input").performTextInput(name)
            compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        }
        addSetupName("Max")
        addSetupName(" max ")
        compose.onNodeWithTag("player_name_input").assertIsFocused().assertTextContains(" max ")
        compose.onNodeWithText(context.getString(R.string.player_name_duplicate_error)).assertIsDisplayed()
        compose.onNodeWithTag("player_name_input").performTextReplacement("Julia")
        compose.onNodeWithText(context.getString(R.string.player_name_duplicate_error)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.onNodeWithText(context.getString(R.string.setup_create_match)).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("match_add_player").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("match_add_player").performClick()
        compose.onNodeWithTag("player_name_input").performTextInput(" JULIA ")
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.onNodeWithTag("player_name_input").assertIsFocused().assertTextContains(" JULIA ")
        compose.onNodeWithText(context.getString(R.string.player_name_duplicate_error)).assertIsDisplayed()
        compose.onNodeWithTag("player_name_input").performTextReplacement("Dora")
        compose.onNodeWithText(context.getString(R.string.player_name_duplicate_error)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player_name_input").fetchSemanticsNodes().isEmpty() }
        runBlocking {
            val match = repo.observeHome().first().single().matches.single()
            assertEquals(listOf("Max", "Julia", "Dora"), match.participants.map { it.displayName })
            assertEquals(3, db.playerDao().players().size)
        }
    }

    @Test fun setupRenameCreatesNewIdentityAndCapitalizationReusesWithoutChangingHistory() = runBlocking {
        val repo = RoomMatchRepository(db)
        val originalId = repo.createMatch(CreateMatch("original-names", listOf(
            CreateMatchPlayer("j", displayName = "Julia"), CreateMatchPlayer("m", displayName = "max"))))
        val original = repo.observeMatch(originalId).first()!!
        val julia = db.playerDao().get(original.participants[0].playerId.value)!!
        val model = MatchSetupViewModel(SavedStateHandle(), repo, Dispatchers.Unconfined)
        val store = ViewModelStore(); store.put("setup", model); stores += store
        model.uiState.first { it.canUseLastMatch }
        model.requestUseLastMatch()
        val draft = model.uiState.value.players
        model.openEditPlayer(draft[0].draftId); model.updateDialogName("Julia2"); model.confirmPlayerName()
        model.openEditPlayer(draft[1].draftId); model.updateDialogName("MAX"); model.confirmPlayerName()
        model.createMatch()
        val newId = (withTimeout(5_000) { model.events.first() } as MatchSetupEvent.OpenMatch).matchId
        val created = repo.observeMatch(newId).first()!!
        assertEquals(listOf("Julia2", "MAX"), created.participants.map { it.displayName })
        assertNotEquals(original.participants[0].playerId, created.participants[0].playerId)
        assertEquals(original.participants[1].playerId, created.participants[1].playerId)
        assertEquals(julia, db.playerDao().get(julia.id))
        assertEquals(original, repo.observeMatch(originalId).first())
        assertEquals(3, db.playerDao().players().size)
    }

    @Test fun duplicateCreateRollsBackAndConcurrentLateNamesReuseCanonicalExactlyOnce() = runBlocking {
        val repo = RoomMatchRepository(db)
        val rejected = runCatching { repo.createMatch(CreateMatch("duplicate-roster", listOf(
            CreateMatchPlayer("one", displayName = "Max"), CreateMatchPlayer("two", displayName = " max ")))) }
        assertTrue(rejected.exceptionOrNull() is DuplicatePlayerNameException)
        assertTrue(db.playerDao().players().isEmpty())
        assertTrue(db.matchDao().matches().isEmpty())
        val canonicalMatch = repo.createMatch(CreateMatch("canonical", listOf(
            CreateMatchPlayer("d", displayName = "Dora"), CreateMatchPlayer("m", displayName = "Max"))))
        val canonical = repo.observeMatch(canonicalMatch).first()!!.participants.first().playerId
        val target = repo.createMatch(CreateMatch("target", listOf(
            CreateMatchPlayer("a", displayName = "Ana"), CreateMatchPlayer("b", displayName = "Bruno"))))
        val commands = listOf("Dora", " dORA ", "DORA", "dora").mapIndexed { index, name ->
            AddPlayerToMatch(target, "concurrent-$index", name)
        }
        val results = commands.map { command -> async(Dispatchers.IO) {
            runCatching { RoomMatchRepository(db).addPlayerToMatch(command) }
        } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is DuplicatePlayerNameException })
        val committed = repo.observeMatch(target).first()!!
        assertEquals(3, committed.participants.size)
        assertEquals(canonical, committed.participants.last().playerId)
        assertEquals(4, db.playerDao().players().size)
        val winner = results.indexOfFirst { it.isSuccess }
        assertEquals(results[winner].getOrThrow(), repo.addPlayerToMatch(commands[winner]))
        assertEquals(committed, repo.observeMatch(target).first())
    }

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
            withTimeout(5_000) { model.uiState.first { it.players.size == 3 } }
            assertEquals("Dora", model.uiState.value.playerName)
            model.confirmPlayerName()
            withTimeout(5_000) { model.uiState.first { !it.isSaving && !it.hasError && it.playerName == null } }
            assertEquals(committed, real.observeMatch(id).first())
            assertEquals(globalCount, db.playerDao().players().size)
            assertEquals(committed.participants.map { it.id }, model.uiState.value.players.map { it.id })
        }
    }

    @Test fun concurrentLateJoinWithStaleRostersShowsInlineDuplicateAndPreservesTypedName() = runBlocking {
        val real = RoomMatchRepository(db)
        val id = real.createMatch(CreateMatch("stale-concurrent", listOf(
            CreateMatchPlayer("a", displayName = "Ana"), CreateMatchPlayer("b", displayName = "Bruno"))))
        val visible = MutableStateFlow(real.observeMatch(id).first())
        val bothSubmitted = CompletableDeferred<Unit>()
        var attempts = 0
        val repo = object : MatchRepository by real {
            override fun observeMatch(matchId: MatchId): Flow<MatchSnapshot?> = visible
            override suspend fun addPlayerToMatch(command: AddPlayerToMatch): ParticipantId {
                attempts++
                if (attempts == 2) bothSubmitted.complete(Unit)
                bothSubmitted.await()
                return real.addPlayerToMatch(command)
            }
        }
        val models = listOf(vm(id, repo), vm(id, repo))
        val typedNames = listOf(" Dora ", " dORA ")
        models.forEachIndexed { index, model ->
            model.uiState.first { !it.isLoading }
            model.openAddPlayer(); model.updatePlayerName(typedNames[index]); model.confirmPlayerName()
        }
        models.forEach { model -> withTimeout(5_000) { model.uiState.first { !it.isSaving } } }
        val loserIndex = models.indexOfFirst { it.uiState.value.playerName != null }
        assertTrue(loserIndex >= 0)
        val loser = models[loserIndex]
        assertTrue(loser.uiState.value.duplicateNameError)
        assertFalse(loser.uiState.value.hasError)
        assertEquals(typedNames[loserIndex], loser.uiState.value.playerName)
        val committed = real.observeMatch(id).first()!!
        assertEquals(3, committed.participants.size)
        assertEquals(3, db.playerDao().players().size)
        visible.value = committed
        withTimeout(5_000) { loser.uiState.first { it.players.size == 3 } }
        loser.confirmPlayerName()
        assertEquals(2, attempts)
        assertTrue(loser.uiState.value.duplicateNameError)
        assertEquals(typedNames[loserIndex], loser.uiState.value.playerName)
        assertEquals(committed, real.observeMatch(id).first())
    }

    @Test fun editingNameAfterLostAcknowledgementStartsAnotherDurableCommand() = runBlocking {
        val real = RoomMatchRepository(db)
        val id = real.createMatch(CreateMatch("edited-retry", listOf(
            CreateMatchPlayer("a", displayName = "Ana"), CreateMatchPlayer("b", displayName = "Bruno"))))
        var throwOnce = true
        val repo = object : MatchRepository by real {
            override suspend fun addPlayerToMatch(command: AddPlayerToMatch): ParticipantId {
                val result = real.addPlayerToMatch(command)
                if (throwOnce) {
                    throwOnce = false
                    throw java.io.IOException("Lost committed acknowledgement")
                }
                return result
            }
        }
        val model = vm(id, repo)
        model.uiState.first { !it.isLoading }
        model.openAddPlayer(); model.updatePlayerName(" Dora "); model.confirmPlayerName()
        withTimeout(5_000) { model.uiState.first { !it.isSaving && it.hasError } }
        assertEquals(" Dora ", model.uiState.value.playerName)
        model.updatePlayerName("Eva"); model.confirmPlayerName()
        val settled = withTimeout(5_000) { model.uiState.first { !it.isSaving } }
        assertFalse(settled.hasError)
        assertNull(settled.playerName)
        val committed = real.observeMatch(id).first()!!
        assertEquals(listOf("Ana", "Bruno", "Dora", "Eva"), committed.participants.map { it.displayName })
        assertEquals(4, db.playerDao().players().size)
    }

    @Test fun lateJoinFailureThenCancelAndReopenDoesNotLeaveAnOrphanIdentity() = runBlocking {
        val repository = RoomMatchRepository(db)
        val id = repository.createMatch(CreateMatch("cancel-reopen", listOf("Ana", "Bruno").mapIndexed { i, name -> CreateMatchPlayer("$i", displayName = name) }))
        val model = vm(id, repository)
        model.uiState.first { !it.isLoading }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_vm_join BEFORE INSERT ON participants BEGIN SELECT RAISE(ABORT, 'join failed'); END")
        model.openAddPlayer(); model.updatePlayerName("Dora"); model.confirmPlayerName()
        withTimeout(5_000) { model.uiState.first { !it.isSaving && it.hasError } }
        assertEquals("Dora", model.uiState.value.playerName)
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
