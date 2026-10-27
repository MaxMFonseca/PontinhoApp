package com.maxmfonseca.pontinho.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcel
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maxmfonseca.pontinho.PontinhoApplication
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.repository.RoomMatchRepository
import com.maxmfonseca.pontinho.data.repository.RoomPlayerRepository
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.recognition.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.recognition.SingleStageCardRecognitionEngineFactory
import com.maxmfonseca.pontinho.recognition.VerifiedCardRecognitionEngineProvider
import com.maxmfonseca.pontinho.settings.*
import com.maxmfonseca.pontinho.ui.match.MatchViewModel
import com.maxmfonseca.pontinho.ui.navigation.Destination
import com.maxmfonseca.pontinho.ui.navigation.PontinhoNavHost
import com.maxmfonseca.pontinho.ui.scanner.ScannerViewModel
import com.maxmfonseca.pontinho.ui.scanner.ScanRequest
import com.maxmfonseca.pontinho.ui.scanner.ScannerScreen
import com.maxmfonseca.pontinho.ui.scanner.ScannerPhase
import com.maxmfonseca.pontinho.ui.scanner.ScannerMessage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Release wiring uses the real asset gate; recognition is substituted only in androidTest. */
class CardScannerJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<PontinhoApplication>()
    private lateinit var database: PontinhoDatabase
    private lateinit var repository: RoomMatchRepository
    private lateinit var nav: NavHostController
    private lateinit var match: MatchViewModel

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, PontinhoDatabase::class.java).build()
        repository = RoomMatchRepository(database)
    }

    @After fun close() { database.close() }

    @Test fun cameraSelectsJokerOnDemandAndCompletedRoundEditsPreserveSavedScoreAndTotal() = runBlocking<Unit> {
        val id = createMatch()
        showMatch(id, DebugRecognitionEngine())
        val participants = repository.observeMatch(id).first()!!.participants.map { it.id }
        compose.onNode(hasTestTag("player_action") and hasAnyAncestor(hasTestTag("match_player_${participants[0].value}")))
            .performScrollTo().performClick()
        awaitTag("score_input")
        compose.onNodeWithTag("joker_dialog").assertDoesNotExist()
        compose.runOnIdle { match = ViewModelProvider(nav.currentBackStackEntry!!)[MatchViewModel::class.java] }
        assertNull(match.uiState.value.scoreDialog!!.indicator)
        compose.onNodeWithTag("score_camera").performScrollTo().performClick()
        awaitTag("joker_dialog")
        compose.onNodeWithTag("scanner_root").assertDoesNotExist()
        selectJoker(Rank.ACE, CardColor.BLACK)
        awaitTag("scanner_root")
        val image = File.createTempFile("optional-joker-", ".png", context.cacheDir)
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            lateinit var scanner: ScannerViewModel
            compose.runOnIdle {
                scanner = ViewModelProvider(nav.currentBackStackEntry!!)[ScannerViewModel::class.java]
                scanner.onPhotoSelected(Uri.fromFile(image))
            }
            compose.waitUntil { scanner.uiState.value.canConfirm }
            assertEquals(4, scanner.uiState.value.total)
            compose.onNodeWithTag("scanner_confirm").performScrollTo().performClick()
            compose.waitUntil { match.uiState.value.scoreDialog == null && !match.uiState.value.isSaving }
            awaitTag("match_root")
            compose.runOnIdle { assertEquals(Destination.Match.route, nav.currentDestination!!.route) }
            compose.onNode(hasTestTag("player_action") and hasAnyAncestor(hasTestTag("match_player_${participants[1].value}")))
                .performScrollTo().performClick()
            awaitTag("score_input")
            compose.onNodeWithTag("score_input").performTextInput("9")
            compose.onNodeWithText(context.getString(R.string.confirm)).performScrollTo().performClick()
            compose.waitUntil { match.uiState.value.roundNumber == 2 && !match.uiState.value.isSaving }
            val before = repository.observeMatch(id).first()!!
            val completed = before.rounds.single()
            val scores = database.roundDao().scores(completed.id.value)
            val observations = database.roundDao().allCardObservations()
            assertNull(before.openRound)
            assertEquals(Indicator(Rank.KING, CardColor.BLACK), completed.indicator)
            assertEquals(listOf(4, 9, 0), participants.map { before.totals.getValue(it) })
            assertEquals("SCAN", scores.single { it.participantId == participants[0].value }.origin)
            assertEquals(2, observations.size)

            // The saved 2H + 2H would score differently if the edited joker recalculated it.
            compose.onNodeWithTag("joker_round_1").performClick()
            awaitTag("joker_dialog")
            selectJoker(Rank.TWO, CardColor.RED)
            compose.waitUntil { match.uiState.value.picker == null && !match.uiState.value.isSaving }
            val edited = repository.observeMatch(id).first()!!
            assertEquals(Indicator(Rank.ACE, CardColor.RED), edited.rounds.single().indicator)
            assertEquals(before.copy(rounds = listOf(completed.copy(indicator = Indicator(Rank.ACE, CardColor.RED)))), edited)
            assertEquals(scores, database.roundDao().scores(completed.id.value))
            assertEquals(observations, database.roundDao().allCardObservations())

            compose.onNodeWithTag("joker_round_1").performClick()
            awaitTag("joker_remove")
            compose.onNodeWithTag("joker_remove").performClick()
            compose.waitUntil { match.uiState.value.picker == null && !match.uiState.value.isSaving }
            val removed = repository.observeMatch(id).first()!!
            assertEquals(before.copy(rounds = listOf(completed.copy(indicator = null))), removed)
            assertEquals(4, removed.rounds.single().draft.entries.getValue(participants[0]).points)
            assertEquals(listOf(4, 9, 0), participants.map { removed.totals.getValue(it) })
            assertEquals(scores, database.roundDao().scores(completed.id.value))
            assertEquals(observations, database.roundDao().allCardObservations())
            compose.onNodeWithTag("joker_round_2").assertIsDisplayed()
        } finally { assertTrue(image.delete() || !image.exists()) }
    }

    private fun selectJoker(rank: Rank, color: CardColor) {
        compose.onNodeWithTag("indicator_rank_${rank.name}").performClick()
        compose.onNodeWithTag("indicator_color_${color.name}").performClick()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
    }

    @Test fun missingReleaseAssetsFailClosedAndManualScoreStillCommits() = runBlocking<Unit> {
        val provider = VerifiedCardRecognitionEngineProvider(
            readAssets = { null to null },
            factory = SingleStageCardRecognitionEngineFactory(),
        )
        try {
            val engine = provider.get()
            provider.availability.first { it !is RecognitionAvailability.Checking }
            assertEquals(RecognitionAvailability.Unavailable(RecognitionUnavailableReason.MISSING_ASSETS), engine.availability)
            unavailableJourney(engine)
        } finally {
            provider.close()
        }
    }

    @Test fun malformedReleasePairFailsClosedAndManualScoreStillCommits() = runBlocking<Unit> {
        val provider = VerifiedCardRecognitionEngineProvider(
            readAssets = { byteArrayOf(1, 2, 3) to "{malformed".toByteArray() },
            factory = SingleStageCardRecognitionEngineFactory(),
        )
        try {
            val engine = provider.get()
            provider.availability.first { it !is RecognitionAvailability.Checking }
            assertEquals(RecognitionAvailability.Unavailable(RecognitionUnavailableReason.INVALID_ASSETS), engine.availability)
            unavailableJourney(engine)
        } finally {
            provider.close()
        }
    }

    @Test fun noCardsRetriesCaptureAndManualScoreStillCommits() = runBlocking<Unit> {
        failedCaptureJourney(CardRecognitionFailure.NoCards(), ScannerMessage.NoCards)
    }

    @Test fun tooManyCardsNeverSavesPartialHandAndManualScoreStillCommits() = runBlocking<Unit> {
        failedCaptureJourney(CardRecognitionFailure.TooManyCards(11), ScannerMessage.TooManyCards)
    }

    @Test fun incompleteCardsNeverSavesPartialHandAndManualScoreStillCommits() = runBlocking<Unit> {
        failedCaptureJourney(CardRecognitionFailure.IncompleteCards(), ScannerMessage.IncompleteCards)
    }

    private suspend fun failedCaptureJourney(failure: CardRecognitionFailure, expected: ScannerMessage) {
        val id = createMatch()
        showMatch(id, DebugRecognitionEngine(failure))
        openScore()
        compose.onNodeWithTag("score_camera").performScrollTo().performClick()
        awaitTag("joker_dialog")
        selectJoker(Rank.ACE, CardColor.BLACK)
        awaitTag("scanner_root")
        val image = File.createTempFile("failed-capture-", ".png", context.cacheDir)
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            lateinit var scanner: ScannerViewModel
            compose.runOnIdle {
                scanner = ViewModelProvider(nav.currentBackStackEntry!!)[ScannerViewModel::class.java]
                scanner.onPhotoSelected(Uri.fromFile(image))
            }
            compose.waitUntil(5_000) { scanner.uiState.value.phase == ScannerPhase.Error }
            assertEquals(expected, scanner.uiState.value.message)
            compose.onNodeWithTag("scanner_confirm").assertDoesNotExist()
            assertTrue(repository.observeMatch(id).first()!!.rounds.isEmpty())
            assertTrue(database.roundDao().allCardObservations().isEmpty())
            compose.onNodeWithTag("scanner_retry").performScrollTo().performClick()
            compose.onNodeWithTag("scanner_capture").assertIsEnabled()
            compose.onNodeWithTag("scanner_camera_cancel").performClick()
            awaitTag("score_input")
            compose.onNodeWithTag("score_input").performTextInput("17")
            compose.runOnIdle { match.confirmScore() }
            compose.waitUntil(5_000) { match.uiState.value.scoreDialog == null }
            val round = repository.observeMatch(id).first()!!.rounds.single()
            assertEquals(17, round.draft.entries.values.single().points)
            assertEquals("MANUAL", database.roundDao().scores(round.id.value).single().origin)
            assertTrue(database.roundDao().allCardObservations().isEmpty())
        } finally { assertTrue(image.delete() || !image.exists()) }
    }

    private suspend fun unavailableJourney(engine: CardRecognitionEngine) {
        val id = createMatch()
        showMatch(id, engine)
        openScore()
        compose.onNodeWithTag("score_camera").assertDoesNotExist()
        compose.onNodeWithTag("score_input").performTextInput("17")
        compose.runOnIdle { match.confirmScore() }
        compose.waitUntil(5_000) { match.uiState.value.scoreDialog == null }
        val round = repository.observeMatch(id).first()!!.rounds.single()
        assertEquals(17, round.draft.entries.values.single().points)
        assertEquals("MANUAL", database.roundDao().scores(round.id.value).single().origin)
        assertTrue(database.roundDao().allCardObservations().isEmpty())
        compose.onNodeWithTag("match_root").assertExists()
    }

    @Test fun debugReviewEditsDuplicatesSurvivesCompositionRestoreThenCommitsOnceAndManualCorrectionClearsCards() = runBlocking<Unit> {
        val id = createMatch()
        val restoration = StateRestorationTester(compose)
        showMatch(id, DebugRecognitionEngine(), restoration)
        openScore()
        val participant = match.uiState.value.scoreDialog!!.participantId
        compose.onNodeWithTag("score_camera").performScrollTo().performClick()
        awaitTag("joker_dialog")
        selectJoker(Rank.ACE, CardColor.BLACK)
        awaitTag("scanner_root")
        lateinit var scanner: ScannerViewModel
        // A generated cache image exercises the real decoder. This does not exercise the picker/camera.
        val image = File.createTempFile("journey-", ".png", context.cacheDir)
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            compose.runOnIdle {
                scanner = ViewModelProvider(nav.currentBackStackEntry!!)[ScannerViewModel::class.java]
                scanner.onPhotoSelected(Uri.fromFile(image))
            }
            compose.waitUntil(5_000) { scanner.uiState.value.canConfirm }
            assertEquals(2, scanner.uiState.value.cards.size)
            assertEquals(4, scanner.uiState.value.total)
            assertTrue(repository.observeMatch(id).first()!!.rounds.isEmpty())
            val firstCard = scanner.uiState.value.cards.first().id
            compose.onNodeWithTag("scanner_edit_$firstCard").performScrollTo().performClick()
            compose.onNodeWithTag("scanner_rank_THREE").performScrollTo().performClick()
            compose.onNodeWithTag("scanner_editor_confirm").performScrollTo().performClick()
            assertEquals(Rank.THREE, scanner.uiState.value.cards.first().card.rank)
            assertEquals(5, scanner.uiState.value.total)
            compose.onNodeWithTag("scanner_add").performScrollTo().performClick()
            compose.onNodeWithTag("scanner_rank_TWO").performScrollTo().performClick()
            compose.onNodeWithTag("scanner_suit_HEARTS").performScrollTo().performClick()
            compose.onNodeWithTag("scanner_editor_confirm").performScrollTo().performClick()
            assertEquals(3, scanner.uiState.value.cards.size)
            assertEquals(7, scanner.uiState.value.total)
            // [3H, 2H, 2H] -> [2H, 2H], preserving two physical observations.
            compose.onNodeWithTag("scanner_remove_$firstCard").performScrollTo().performClick()
            assertEquals(4, scanner.uiState.value.total)
            val request = scanner.request
            restoration.emulateSavedInstanceStateRestore()
            awaitTag("scanner_root")
            compose.runOnIdle { scanner = ViewModelProvider(nav.currentBackStackEntry!!)[ScannerViewModel::class.java] }
            assertEquals(request, scanner.request)
            assertEquals(2, scanner.uiState.value.cards.size)
            assertTrue(repository.observeMatch(id).first()!!.rounds.isEmpty())
            compose.onNodeWithTag("scanner_confirm").performScrollTo().performClick()
            compose.waitUntil(5_000) { scanner.uiState.value.saved }
            compose.runOnIdle { scanner.confirm() } // A repeated confirmation must not create another score.
            compose.waitUntil(5_000) { match.uiState.value.scoreDialog == null }
            awaitTag("match_root")
            val round = repository.observeMatch(id).first()!!.rounds.single()
            val score = database.roundDao().scores(round.id.value).single()
            assertEquals(4, round.draft.entries.getValue(participant).points)
            assertEquals("SCAN", score.origin)
            val cards = database.roundDao().cardObservations(score.id)
            assertEquals(listOf(0, 1), cards.map { it.ordinal })
            assertEquals(listOf("TWO", "TWO"), cards.map { it.rank })
            assertEquals(listOf("HEARTS", "HEARTS"), cards.map { it.suit })
            assertEquals(listOf(false, true), cards.map { it.correctedByUser })
            assertNotNull(database.roundDao().submission(request.submissionToken))
            compose.runOnIdle { match.editScore(participant, 1) }
            compose.onNodeWithTag("score_input").performTextReplacement("9")
            compose.runOnIdle { match.confirmScore() }
            compose.waitUntil(5_000) { match.uiState.value.scoreDialog == null }
            val corrected = database.roundDao().scores(round.id.value).single()
            assertEquals(score.id, corrected.id)
            assertEquals("MANUAL", corrected.origin)
            assertEquals(9, repository.observeMatch(id).first()!!.rounds.single().draft.entries.getValue(participant).points)
            assertTrue(database.roundDao().allCardObservations().isEmpty())
        } finally { assertTrue(image.delete() || !image.exists()) }
    }

    /** A distinct instance from parcelled saved state; this does not simulate an OS process kill. */
    @Test fun parcelledReviewReconstructsDistinctViewModelAndRequiresExplicitConfirmation() = runBlocking<Unit> {
        val id = createMatch()
        val participant = repository.observeMatch(id).first()!!.participants.first().id
        val prepared = repository.prepareRound(PrepareRound(id, Indicator(Rank.ACE, CardColor.BLACK)))
        val request = ScanRequest(id, participant, prepared.id, requireNotNull(prepared.indicator), prepared, submissionToken = "restored-review")
        val handle = SavedStateHandle()
        val store = ViewModelStore()
        lateinit var original: ScannerViewModel
        lateinit var restored: ScannerViewModel
        compose.runOnIdle {
            original = ScannerViewModel(request, handle, DebugRecognitionEngine(), repository,
                { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) })
            store.put("scanner", original)
            original.onPhotoSelected(Uri.parse("content://test/generated"))
        }
        compose.waitUntil(5_000) { original.uiState.value.canConfirm }
        compose.runOnIdle {
            original.replaceRank(original.uiState.value.cards.first().id, Rank.THREE)
            val parcel = Parcel.obtain()
            val reconstructedHandle = try {
                parcel.writeBundle(handle.savedStateProvider().saveState())
                val bytes = parcel.marshall()
                parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0)
                SavedStateHandle.createHandle(parcel.readBundle(javaClass.classLoader), null)
            } finally { parcel.recycle() }
            store.clear()
            restored = ScannerViewModel(request.copy(submissionToken = "must-not-replace-restored-token"),
                reconstructedHandle, DebugRecognitionEngine(), repository, { error("Must not reload image") })
            store.put("restored", restored)
        }
        try {
            assertNotSame(original, restored)
            assertEquals(request, restored.request)
            assertEquals(original.uiState.value.cards, restored.uiState.value.cards)
            assertEquals(5, restored.uiState.value.total)
            assertTrue(repository.observeMatch(id).first()!!.rounds.isEmpty())
            compose.setContent { ScannerScreen(restored, true) }
            compose.onNodeWithTag("scanner_confirm").performScrollTo().performClick()
            compose.waitUntil(5_000) { restored.uiState.value.saved }
            assertEquals(5, repository.observeMatch(id).first()!!.rounds.single().draft.entries.getValue(participant).points)
            assertEquals(2, database.roundDao().allCardObservations().size)
            assertNotNull(database.roundDao().submission("restored-review"))
        } finally { compose.runOnIdle { store.clear() } }
    }

    private suspend fun createMatch() = repository.createMatch(CreateMatch("journey", listOf("Ana", "Bruno", "Carla")
        .map { CreateMatchPlayer(it, displayName = it) }))

    private fun showMatch(id: MatchId, engine: CardRecognitionEngine, restoration: StateRestorationTester? = null) {
        val app = object : AppContainer {
            override val playerRepository = RoomPlayerRepository(database)
            override val matchRepository = repository
            override val preferencesRepository = object : PreferencesRepository {
                override val preferences = MutableStateFlow(AppPreferences(experimentalCardScanningEnabled = true))
                override suspend fun setTheme(theme: ThemePreference) = Unit
                override suspend fun setLanguage(language: LanguagePreference) = Unit
                override suspend fun setReducedMotion(reducedMotion: Boolean?) = Unit
                override suspend fun setExperimentalCardScanningEnabled(enabled: Boolean) {
                    preferences.value = preferences.value.copy(experimentalCardScanningEnabled = enabled)
                }
            }
            override val cardRecognitionEngineProvider = object : CardRecognitionEngineProvider {
                override fun get() = engine
                override fun close() = Unit
            }
        }
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            val controller = rememberNavController()
            SideEffect { nav = controller }
            PontinhoNavHost(app, navController = controller, startDestination = Destination.Match.createRoute(id.value))
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Ana").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openScore() {
        compose.runOnIdle {
            match = ViewModelProvider(nav.currentBackStackEntry!!)[MatchViewModel::class.java]
            match.addScore(match.uiState.value.players.first().id)
        }
        compose.waitUntil(5_000) { match.uiState.value.scoreDialog != null && !match.uiState.value.isSaving }
        awaitTag("score_input")
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private class DebugRecognitionEngine(private val failure: CardRecognitionFailure? = null) : CardRecognitionEngine {
        override val availability = RecognitionAvailability.Available(
            RecognitionModelMetadata("journey-debug-only", "0".repeat(64), (0..51).map { "card-$it" }, 320, 320, .25f, .45f))
        override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult {
            failure?.let { throw it }
            return CardRecognitionResult(listOf(
            RecognizedCard(Card.Suited(Rank.TWO, Suit.HEARTS), NormalizedRect(.1f, .1f, .3f, .5f), .9f, emptySet()),
            RecognizedCard(Card.Suited(Rank.TWO, Suit.HEARTS), NormalizedRect(.6f, .1f, .8f, .5f), .9f, emptySet()),
            ), 1, "journey-debug-only")
        }
        override fun close() = Unit
    }
}
