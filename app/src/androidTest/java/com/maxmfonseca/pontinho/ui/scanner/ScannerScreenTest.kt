package com.maxmfonseca.pontinho.ui.scanner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.repository.RoomMatchRepository
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.recognition.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.content.res.Configuration
import java.util.Locale

class ScannerScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun card(id: String, low: Boolean = false) = ReviewCard(id,
        Card.Suited(Rank.TWO, Suit.HEARTS), .5f, null,
        if (low) setOf(RecognitionWarning.LOW_CONFIDENCE) else emptySet(), false)
    private fun review() = ScannerUiState(phase = ScannerPhase.Review,
        cards = listOf(card("first", true), card("second")), total = 20, modelVersion = "fake")

    @Test fun experimentalWarningRemainsVisibleFromCaptureThroughReview() {
        var state by mutableStateOf(ScannerUiState())
        var experimental by mutableStateOf(true)
        compose.setContent { ScannerScreen(state, recognitionExperimental = experimental) }
        compose.onNodeWithTag("scanner_experimental").assertExists()
        compose.runOnIdle { state = review() }
        compose.onNodeWithTag("scanner_experimental").assertExists()
        compose.runOnIdle { experimental = false }
        compose.onNodeWithTag("scanner_experimental").assertDoesNotExist()
    }

    @Test fun debugDiagnosticsExpandAndExposeCopyableJsonOnReviewAndError() {
        val diagnostics = RecognitionDiagnostics(
            sourceWidth = 1920, sourceHeight = 1080, letterboxScale = 1.0 / 3.0,
            padLeft = 0, padTop = 140, padRight = 0, padBottom = 140,
            rawCandidateCount = 0, maximumScore = .24f,
            confidenceThreshold = .25, iouThreshold = .45,
            rejectedBelowConfidence = 8400, rejectedByClassNms = 0,
            rejectedByPhysicalNms = 0, rejectedIncomplete = 0,
            finalBoxes = emptyList(), durationMillis = 25,
        )
        var state by mutableStateOf(review().copy(diagnostics = diagnostics))
        compose.setContent { ScannerScreen(state) }

        compose.onNodeWithTag("scanner_diagnostics_toggle").assertExists().performClick()
        compose.onNodeWithTag("scanner_diagnostics_json")
            .assertTextContains("\"maximumScore\":0.24", substring = true)
            .assertTextContains("\"belowConfidence\":8400", substring = true)
        compose.onNodeWithTag("scanner_diagnostics_copy").assertIsEnabled()

        compose.runOnIdle {
            state = ScannerUiState(
                phase = ScannerPhase.Error,
                recovery = ScannerRecovery.Capture,
                message = ScannerMessage.NoCards,
                diagnostics = diagnostics,
            )
        }
        compose.onNodeWithTag("scanner_diagnostics_toggle").assertExists()
    }

    @Test fun portugueseCaptureLimitsAndRecoveryAreLocalized() = localizedRecovery(
        "pt-BR", listOf("no máximo 10", "separadas", "inteiras", "reflexos", "duas fotos"),
        listOf("Nenhuma carta", "Mais de 10 cartas", "cortadas", "resolução menor"),
        "baixa confiança", "indisponível",
    )

    @Test fun englishCaptureLimitsAndRecoveryAreLocalized() = localizedRecovery(
        "en-US", listOf("at most 10", "separated", "fully inside", "glare", "two photos"),
        listOf("No cards", "More than 10 cards", "cut off", "lower resolution"),
        "low confidence", "unavailable",
    )

    private fun localizedRecovery(locale: String, guidance: List<String>, errors: List<String>, warning: String, unavailable: String) {
        var state by mutableStateOf(ScannerUiState())
        var available by mutableStateOf(true)
        var manual = 0
        var retries = 0
        compose.setContent {
            val base = LocalContext.current
            val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
            val localized = base.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                ScannerScreen(state, recognitionAvailable = available, actions = ScannerActions(
                    onRetry = { retries++; state = ScannerUiState() }, onManual = { manual++ }))
            }
        }
        guidance.forEach { compose.onNodeWithTag("scanner_capture_help").assertTextContains(it, substring = true) }
        listOf(ScannerMessage.NoCards, ScannerMessage.TooManyCards, ScannerMessage.IncompleteCards,
            ScannerMessage.ImageTooLarge).zip(errors).forEach { (message, text) ->
            compose.runOnIdle { state = ScannerUiState(phase = ScannerPhase.Error,
                recovery = ScannerRecovery.Capture, message = message) }
            compose.onNodeWithTag("scanner_error").assertTextContains(text, substring = true)
            compose.onNodeWithTag("scanner_confirm").assertDoesNotExist()
            compose.onNodeWithTag("scanner_retry").performScrollTo().performClick()
            compose.onNodeWithTag("scanner_capture").performScrollTo().assertIsEnabled()
        }
        compose.runOnIdle { assertEquals(4, retries); state = review() }
        compose.onNodeWithTag("scanner_warning_first").assertTextContains(warning, substring = true)
        compose.onNodeWithTag("scanner_edit_first").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_rank_ACE").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("scanner_editor_cancel").performScrollTo().performClick()
        compose.runOnIdle { state = ScannerUiState(); available = false }
        compose.onNodeWithTag("scanner_unavailable").assertTextContains(unavailable, substring = true)
        compose.onNodeWithTag("scanner_manual").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, manual) }
    }

    @Test fun disposingAndRecreatingDeviceScreenDuringDecodePreservesSourceUntilReview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PontinhoDatabase::class.java).build()
        val firstFiles = ScanCaptureFiles(context.cacheDir)
        val recreatedFiles = ScanCaptureFiles(context.cacheDir)
        val source = firstFiles.create().apply { writeText("camera bytes") }
        val nextOpen = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val engine = object : CardRecognitionEngine {
            override val availability = RecognitionAvailability.Available(RecognitionModelMetadata(
                "test", "0".repeat(64), (0..51).map { "card-$it" }, 640, 640, .25f, .45f))
            override suspend fun recognize(bitmap: Bitmap) = CardRecognitionResult(listOf(
                RecognizedCard(Card.Suited(Rank.TWO, Suit.HEARTS), NormalizedRect(.1f, .1f, .5f, .5f), .9f, emptySet())
            ), 1, "test")
            override fun close() = Unit
        }
        val vm = ScannerViewModel(ScanRequest(MatchId("match"), ParticipantId("participant"), RoundId("round"),
            Indicator(Rank.FIVE, CardColor.RED), submissionToken = "stable-token"), SavedStateHandle(), engine,
            RoomMatchRepository(db), loadImage = {
                assertEquals("camera bytes", source.readText())
                started.complete(Unit)
                nextOpen.await()
                assertEquals("camera bytes", source.readText())
                bitmap
            })
        val store = ViewModelStore().apply { put("scanner", vm) }
        var mounted by mutableStateOf(true)
        var recreated by mutableStateOf(false)
        try {
            // Begin before composing so this test never needs camera hardware or permission.
            compose.runOnIdle { vm.onCameraCaptured(Uri.fromFile(source), firstFiles.transfer(source)) }
            compose.setContent {
                if (mounted) ScannerScreen(vm, true, if (recreated) recreatedFiles else firstFiles)
            }
            compose.waitUntil { started.isCompleted }
            compose.onNodeWithTag("scanner_processing").assertExists()
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            assertTrue(source.exists())
            compose.runOnIdle { recreated = true; mounted = true }
            compose.onNodeWithTag("scanner_processing").assertExists()
            compose.runOnIdle { nextOpen.complete(Unit) }
            compose.waitUntil { vm.uiState.value.phase != ScannerPhase.Processing }
            compose.onNodeWithTag("scanner_total").assertExists()
            assertEquals(ScannerPhase.Review, vm.uiState.value.phase)
            assertEquals(1, vm.uiState.value.cards.size)
            assertEquals("stable-token", vm.request.submissionToken)
            assertFalse(source.exists())
            assertTrue(bitmap.isRecycled)
        } finally {
            compose.runOnIdle { store.clear(); nextOpen.complete(Unit) }
            firstFiles.close(); recreatedFiles.close(); db.close()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test fun captureAndGalleryDispatchSeparateActions() {
        var camera = 0; var gallery = 0
        compose.setContent { ScannerScreen(ScannerUiState(), actions = ScannerActions(
            onCapture = { camera++ }, onImport = { gallery++ })) }
        compose.onNodeWithTag("scanner_capture").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_import").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, camera); assertEquals(1, gallery) }
    }

    @Test fun deniedPermissionKeepsGalleryAndManualFallback() {
        var manual = false
        compose.setContent { ScannerScreen(ScannerUiState(), cameraPermissionDenied = true,
            actions = ScannerActions(onManual = { manual = true })) }
        compose.onNodeWithTag("scanner_permission_denied").assertExists()
        compose.onNodeWithTag("scanner_import").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("scanner_manual").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(manual) }
    }

    @Test fun awaitingImageDisablesRepeatedCaptureAndImport() {
        compose.setContent { ScannerScreen(ScannerUiState(awaitingInput = true)) }
        compose.onNodeWithTag("scanner_capture").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("scanner_import").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("scanner_cancel").performScrollTo().assertIsEnabled()
    }

    @Test fun cameraOutputFailureKeepsRecoveryAndRetakeIsExplicit() {
        var state by mutableStateOf(ScannerUiState())
        var retakes = 0
        compose.setContent { ScannerScreen(state, cameraError = true,
            actions = ScannerActions(onRetake = { retakes++ })) }
        compose.onNodeWithTag("scanner_camera_error").assertExists()
        compose.onNodeWithTag("scanner_import").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("scanner_manual").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state = review(); assertEquals(0, retakes) }
        compose.onNodeWithTag("scanner_retake").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retakes) }
    }

    @Test fun unavailableScannerOffersOnlyFallbackAndCancel() {
        compose.setContent { ScannerScreen(ScannerUiState(), recognitionAvailable = false) }
        compose.onNodeWithTag("scanner_unavailable").assertExists()
        compose.onNodeWithTag("scanner_capture").assertDoesNotExist()
        compose.onNodeWithTag("scanner_import").assertDoesNotExist()
        compose.onNodeWithTag("scanner_manual").performScrollTo().assertIsEnabled()
    }

    @Test fun progressAndSavingBlockDuplicateWork() {
        var state by mutableStateOf(ScannerUiState(phase = ScannerPhase.Processing))
        compose.setContent { ScannerScreen(state) }
        compose.onNodeWithTag("scanner_progress").assertExists()
        compose.onNodeWithTag("scanner_capture").assertDoesNotExist()
        compose.runOnIdle { state = review().copy(phase = ScannerPhase.Saving, submissionStarted = true) }
        compose.onNodeWithTag("scanner_confirm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("scanner_cancel").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("scanner_manual").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("scanner_add").assertDoesNotExist()
    }

    @Test fun removingAllReviewCardsAllowsAddingButCannotConfirm() {
        compose.setContent { ScannerScreen(review().copy(cards = emptyList(), total = 0)) }
        compose.onNodeWithTag("scanner_empty").assertExists()
        compose.onNodeWithTag("scanner_confirm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("scanner_add").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_rank_KING").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("scanner_suit_SPADES").performScrollTo().assertIsEnabled()
    }

    @Test fun duplicateCardsHaveIndependentAccessibleEditsAndExplicitConfirmation() {
        var confirmed = 0; var cancelled = 0; var removed: String? = null
        compose.setContent { ScannerScreen(review(), actions = ScannerActions(
            onConfirm = { confirmed++ }, onCancel = { cancelled++ }, onRemove = { removed = it })) }
        compose.onNodeWithTag("scanner_card_first").assertExists()
        compose.onNodeWithTag("scanner_card_second").assertExists()
        compose.onNodeWithTag("scanner_warning_first").assertExists()
        compose.onNodeWithTag("scanner_card_description_first").assert(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithTag("scanner_remove_second").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("second", removed) }
        compose.onNodeWithTag("scanner_confirm").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, confirmed); assertEquals(1, cancelled) }
    }

    @Test fun compactEditorAddsAndReplacesAndDisplaysUpdatedTotal() {
        var state by mutableStateOf(review())
        var changed: Pair<String, Card.Suited>? = null
        var added: Card.Suited? = null
        compose.setContent { ScannerScreen(state, actions = ScannerActions(
            onReplace = { id, card -> changed = id to card; state = state.copy(total = 30) },
            onAdd = { added = it; state = state.copy(total = 40) })) }
        compose.onNodeWithTag("scanner_edit_second").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_rank_KING").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_suit_SPADES").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_editor_confirm").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("second" to Card.Suited(Rank.KING, Suit.SPADES), changed) }
        compose.onNodeWithTag("scanner_total").assertTextContains("30", substring = true)
        compose.onNodeWithTag("scanner_add").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_rank_ACE").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_suit_CLUBS").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_editor_confirm").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Card.Suited(Rank.ACE, Suit.CLUBS), added) }
        compose.onNodeWithTag("scanner_total").assertTextContains("40", substring = true)
    }

    @Test fun errorsOfferRetryAndManualEntryWithoutLosingReview() {
        var retries = 0
        compose.setContent { ScannerScreen(review().copy(phase = ScannerPhase.Error,
            recovery = ScannerRecovery.Save, submissionStarted = true),
            cameraError = true, actions = ScannerActions(onRetry = { retries++ })) }
        compose.onNodeWithTag("scanner_error").assertExists()
        compose.onNodeWithTag("scanner_card_first").assertExists()
        compose.onNodeWithTag("scanner_retry").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_manual").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun narrowScreenAtDoubleFontKeepsEditorAndActionsReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(280.dp)) { ScannerScreen(review()) }
            }
        }
        compose.onNodeWithTag("scanner_confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("scanner_add").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_rank_KING").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("scanner_suit_HEARTS").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("scanner_editor_confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("scanner_editor_cancel").performScrollTo().performClick()
        compose.onNodeWithTag("scanner_cancel").performScrollTo().assertIsDisplayed()
    }
}
