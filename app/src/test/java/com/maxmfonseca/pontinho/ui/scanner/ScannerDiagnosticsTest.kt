package com.maxmfonseca.pontinho.ui.scanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.maxmfonseca.pontinho.BuildConfig
import com.maxmfonseca.pontinho.di.readSingleStageRecognitionAssets
import com.maxmfonseca.pontinho.di.singleStageRecognitionEngineFactory
import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.CardColor
import com.maxmfonseca.pontinho.domain.model.Indicator
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.ParticipantId
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.RoundId
import com.maxmfonseca.pontinho.domain.model.Suit
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngine
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionFailure
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionResult
import com.maxmfonseca.pontinho.domain.recognition.NormalizedRect
import com.maxmfonseca.pontinho.domain.recognition.RecognizedCard
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.domain.recognition.RecognitionDiagnosticBox
import com.maxmfonseca.pontinho.domain.recognition.RecognitionDiagnostics
import com.maxmfonseca.pontinho.domain.recognition.RecognitionModelMetadata
import com.maxmfonseca.pontinho.domain.recognition.RecognitionUnavailableReason
import com.maxmfonseca.pontinho.recognition.CardRecognitionException
import com.maxmfonseca.pontinho.recognition.SingleStageCardRecognitionEngineFactory
import com.maxmfonseca.pontinho.ui.test.FakeMatchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerDiagnosticsTest {
    @Test
    fun `container reads only detector and release metadata and defaults to single stage`() {
        val reads = mutableListOf<String>()

        val assets = readSingleStageRecognitionAssets { name ->
            reads += name
            name.encodeToByteArray()
        }

        assertEquals(listOf("card_detector.tflite", "card_recognition.release.json"), reads)
        assertEquals("card_detector.tflite", assets.first?.decodeToString())
        assertEquals("card_recognition.release.json", assets.second?.decodeToString())
        assertTrue(singleStageRecognitionEngineFactory() is SingleStageCardRecognitionEngineFactory)
    }

    @Test
    fun `diagnostics JSON contains numeric inference data and no image or user identifiers`() {
        val json = diagnostics().toJson()
        val parsed = JSONObject(json)

        assertEquals(1920, parsed.getInt("sourceWidth"))
        assertEquals(0.83, parsed.getDouble("maximumScore"), 0.0)
        assertEquals(4, parsed.getJSONObject("rejected").getInt("belowConfidence"))
        assertEquals(1, parsed.getJSONArray("finalBoxes").length())
        assertNumericLeaves(parsed)
        listOf("uri", "filename", "filepath", "exif", "pixel", "content://", "private-image", "modelVersion")
            .forEach { forbidden -> assertFalse(json.lowercase().contains(forbidden.lowercase())) }
    }

    @Test
    fun `debug state receives diagnostics and every new capture clears the previous record`() = runTest {
        val engine = FakeEngine(result = result(diagnostics()))
        val viewModel = viewModel(engine, diagnosticsEnabled = true)

        viewModel.onPhotoSelected(imageUri)
        advanceUntilIdle()
        assertSame(engine.result.diagnostics, viewModel.uiState.value.diagnostics)

        viewModel.retake()
        assertNull(viewModel.uiState.value.diagnostics)
        viewModel.beginCapture(ScanImageSource.Camera)
        assertNull(viewModel.uiState.value.diagnostics)
    }

    @Test
    fun `release policy discards diagnostics even from an injected engine`() = runTest {
        val viewModel = viewModel(FakeEngine(result = result(diagnostics())), diagnosticsEnabled = false)

        viewModel.onPhotoSelected(imageUri)
        advanceUntilIdle()

        assertEquals(ScannerPhase.Review, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.diagnostics)
    }

    @Test
    fun `variant default exposes diagnostics only in debug builds`() = runTest {
        val engine = FakeEngine(result = result(diagnostics()))
        val viewModel = ScannerViewModel(
            initialRequest = request,
            savedStateHandle = SavedStateHandle(),
            engine = engine,
            repository = FakeMatchRepository(),
            loadImage = { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.onPhotoSelected(imageUri)
        advanceUntilIdle()

        assertEquals(BuildConfig.DEBUG, viewModel.uiState.value.diagnostics != null)
    }

    @Test
    fun `typed capture failures retain diagnostics and map to distinct recovery messages`() = runTest {
        val cases = listOf(
            CardRecognitionFailure.NoCards(diagnostics()) to ScannerMessage.NoCards,
            CardRecognitionFailure.IncompleteCards(diagnostics()) to ScannerMessage.IncompleteCards,
            CardRecognitionFailure.TooManyCards(11, diagnostics()) to ScannerMessage.TooManyCards,
        )

        cases.forEach { (failure, expectedMessage) ->
            val viewModel = viewModel(FakeEngine(failure = failure), diagnosticsEnabled = true)
            viewModel.onPhotoSelected(imageUri)
            advanceUntilIdle()

            assertEquals(expectedMessage, viewModel.uiState.value.message)
            assertEquals(ScannerRecovery.Capture, viewModel.uiState.value.recovery)
            assertSame(failure.diagnostics, viewModel.uiState.value.diagnostics)
            assertTrue(viewModel.uiState.value.cards.isEmpty())
            assertFalse(viewModel.uiState.value.canConfirm)
        }
    }

    @Test
    fun `view model boundary limits retain diagnostics from alternate engines`() = runTest {
        val record = diagnostics()
        val cases = listOf(
            result(record).copy(candidates = emptyList()) to ScannerMessage.NoCards,
            result(record).copy(candidates = List(11) { result().candidates.single() }) to ScannerMessage.TooManyCards,
        )

        cases.forEach { (engineResult, expectedMessage) ->
            val viewModel = viewModel(FakeEngine(result = engineResult), diagnosticsEnabled = true)
            viewModel.onPhotoSelected(imageUri)
            advanceUntilIdle()

            assertEquals(expectedMessage, viewModel.uiState.value.message)
            assertSame(record, viewModel.uiState.value.diagnostics)
            assertTrue(viewModel.uiState.value.cards.isEmpty())
        }
    }

    @Test
    fun `runtime failure and model unavailability remain distinct`() = runTest {
        val runtime = viewModel(
            FakeEngine(failure = CardRecognitionException.InferenceFailed(diagnostics())),
            diagnosticsEnabled = true,
        )
        runtime.onPhotoSelected(imageUri)
        advanceUntilIdle()
        assertEquals(ScannerMessage.RecognitionFailed, runtime.uiState.value.message)
        assertEquals(diagnostics(), runtime.uiState.value.diagnostics)

        val unavailableEngine = FakeEngine(failure = CardRecognitionException.InferenceFailed()).apply {
            unavailableOnRecognize = true
        }
        val unavailable = viewModel(unavailableEngine, diagnosticsEnabled = true)
        unavailable.onPhotoSelected(imageUri)
        advanceUntilIdle()
        assertEquals(ScannerMessage.Unavailable, unavailable.uiState.value.message)
        assertNull(unavailable.uiState.value.diagnostics)
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        engine: FakeEngine,
        diagnosticsEnabled: Boolean,
    ) = ScannerViewModel(
        initialRequest = request,
        savedStateHandle = SavedStateHandle(),
        engine = engine,
        repository = FakeMatchRepository(),
        loadImage = { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) },
        dispatcher = StandardTestDispatcher(testScheduler),
        diagnosticsEnabled = diagnosticsEnabled,
    )

    private fun assertNumericLeaves(value: Any) {
        when (value) {
            is JSONObject -> value.keys().forEach { assertNumericLeaves(value.get(it)) }
            is JSONArray -> for (index in 0 until value.length()) assertNumericLeaves(value.get(index))
            else -> assertTrue("Expected numeric JSON leaf but was $value", value is Number)
        }
    }

    private class FakeEngine(
        val result: CardRecognitionResult = result(),
        private val failure: Exception? = null,
    ) : CardRecognitionEngine {
        var unavailableOnRecognize = false
        private var currentAvailability: RecognitionAvailability = available
        override val availability: RecognitionAvailability get() = currentAvailability

        override suspend fun recognize(bitmap: Bitmap): CardRecognitionResult {
            if (unavailableOnRecognize) {
                currentAvailability = RecognitionAvailability.Unavailable(RecognitionUnavailableReason.INVALID_ASSETS)
            }
            failure?.let { throw it }
            return result
        }

        override fun close() = Unit
    }

    private companion object {
        val request = ScanRequest(
            MatchId("match"),
            ParticipantId("participant"),
            RoundId("round"),
            Indicator(Rank.FIVE, CardColor.RED),
            submissionToken = "stable-token",
        )
        val imageUri: Uri = Uri.parse("content://photos/private-image")
        val available = RecognitionAvailability.Available(
            RecognitionModelMetadata(
                "test-model",
                "0".repeat(64),
                (0..51).map { "card-$it" },
                640,
                640,
                .25f,
                .45f,
            ),
        )

        fun diagnostics() = RecognitionDiagnostics(
            sourceWidth = 1920,
            sourceHeight = 1080,
            letterboxScale = 1.0 / 3.0,
            padLeft = 0,
            padTop = 140,
            padRight = 0,
            padBottom = 140,
            rawCandidateCount = 3,
            maximumScore = .83f,
            confidenceThreshold = .25,
            iouThreshold = .45,
            rejectedBelowConfidence = 4,
            rejectedByClassNms = 1,
            rejectedByPhysicalNms = 0,
            rejectedByIndexPairing = 0,
            rejectedIncomplete = 1,
            finalBoxes = listOf(RecognitionDiagnosticBox(7, .83f, .1f, .2f, .3f, .4f)),
            durationMillis = 37,
        )

        fun result(diagnostics: RecognitionDiagnostics? = null) = CardRecognitionResult(
            candidates = listOf(
                RecognizedCard(
                    Card.Suited(Rank.SIX, Suit.HEARTS),
                    NormalizedRect(.1f, .2f, .3f, .4f),
                    .83f,
                    emptySet(),
                ),
            ),
            durationMillis = 37,
            modelVersion = "test-model",
            diagnostics = diagnostics,
        )
    }
}
