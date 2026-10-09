package com.maxmfonseca.pontinho.ui.scanner

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.recognition.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.domain.rules.CardValueCalculator
import com.maxmfonseca.pontinho.recognition.RecognitionImageException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import java.util.UUID

/**
 * UI actions run on the main thread. [loadImage] must decode off the main thread and transfer
 * ownership of its returned bitmap to this ViewModel. Neither URI nor bitmap enters saved state.
 * The engine is borrowed from its provider; only the provider closes it.
 */
class ScannerViewModel(
    initialRequest: ScanRequest,
    private val savedStateHandle: SavedStateHandle,
    private val engine: CardRecognitionEngine,
    private val repository: MatchRepository,
    private val loadImage: suspend (Uri) -> Bitmap,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val snapshot = savedStateHandle.get<Bundle>(SNAPSHOT)
    private val restoredRequest = snapshot?.let { runCatching { decodeRequest(requireNotNull(it.getBundle("request"))) } }
    val request: ScanRequest = restoredRequest?.getOrNull() ?: initialRequest
    private val mutableUiState = MutableStateFlow(restoreState())
    val uiState = mutableUiState.asStateFlow()
    private val eventChannel = Channel<ScannerEvent>(Channel.BUFFERED)
    private var savedAcknowledged = uiState.value.saved &&
        savedStateHandle.get<String>(COMPLETION_ACK) == request.submissionToken
    private val pendingSaved = MutableStateFlow<ScannerEvent.Saved?>(null)
    /** Saved is a durable pending completion, replayed to each collector until explicitly acknowledged. */
    val events = merge(eventChannel.receiveAsFlow(), pendingSaved.filterNotNull())
    private var processingJob: Job? = null
    private var generation = 0
    private var closed = false

    init {
        transition(mutableUiState.value)
    }

    fun beginCapture(source: ScanImageSource) {
        if (closed || uiState.value.phase != ScannerPhase.Capture || !checkAvailability()) return
        transition(ScannerUiState(source = source, awaitingInput = true))
    }

    /** Takes ownership even if input is rejected or processing is cancelled before its first dispatch. */
    fun onCameraCaptured(uri: Uri?, sourceOwner: AutoCloseable? = null) = process(uri, ScanImageSource.Camera, sourceOwner)
    fun onPhotoSelected(uri: Uri?) = process(uri, ScanImageSource.Photo)

    fun retake() {
        val state = uiState.value
        if (closed || state.submissionStarted || state.saved) return
        generation++
        processingJob?.cancel()
        transition(ScannerUiState())
    }

    private fun process(uri: Uri?, source: ScanImageSource, sourceOwner: AutoCloseable? = null) {
        if (closed || uiState.value.phase != ScannerPhase.Capture) {
            sourceOwner?.close()
            return
        }
        if (uri == null) {
            sourceOwner?.close()
            transition(ScannerUiState())
            return
        }
        if (!checkAvailability()) {
            sourceOwner?.close()
            return
        }
        val attempt = ++generation
        transition(ScannerUiState(phase = ScannerPhase.Processing, source = source))
        processingJob = scope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = loadImage(uri)
                ensureActive()
                val result = engine.recognize(bitmap)
                ensureActive()
                if (attempt != generation || closed) return@launch
                // Enforce capture limits at this boundary too, including alternate engines.
                if (result.candidates.isEmpty()) throw CardRecognitionFailure.NoCards()
                if (result.candidates.size > 10) throw CardRecognitionFailure.TooManyCards(result.candidates.size)
                val cards = result.candidates.map {
                    ReviewCard(UUID.randomUUID().toString(), it.card, it.confidence, it.bounds,
                        it.warnings.toSet(), correctedByUser = false)
                }
                transition(ScannerUiState(
                    phase = ScannerPhase.Review, source = source, cards = cards,
                    total = total(cards), modelVersion = result.modelVersion,
                ))
            } catch (cancelled: CancellationException) {
                if (attempt == generation && !closed) transition(ScannerUiState())
                throw cancelled
            } catch (failure: Exception) {
                if (attempt == generation && !closed) transition(ScannerUiState(
                    phase = ScannerPhase.Error, recovery = ScannerRecovery.Capture,
                    message = when (failure) {
                        is CardRecognitionFailure.NoCards -> ScannerMessage.NoCards
                        is CardRecognitionFailure.TooManyCards -> ScannerMessage.TooManyCards
                        is CardRecognitionFailure.IncompleteCards -> ScannerMessage.IncompleteCards
                        is RecognitionImageException.SizeLimitExceeded -> ScannerMessage.ImageTooLarge
                        else -> ScannerMessage.RecognitionFailed
                    },
                ))
            } finally {
                bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
        }.also { job ->
            // Completion waits for decoder/engine finally blocks, including non-cancellable work.
            // Unlike a coroutine-body finally, this also runs if cancellation precedes first dispatch.
            job.invokeOnCompletion { sourceOwner?.close() }
        }
    }

    private fun checkAvailability(): Boolean {
        if (engine.availability is RecognitionAvailability.Available) return true
        transition(ScannerUiState(
            phase = ScannerPhase.Error, recovery = ScannerRecovery.Capture,
            message = ScannerMessage.Unavailable,
        ))
        return false
    }

    fun addCard(card: Card.Suited) = edit { cards ->
        cards + ReviewCard(UUID.randomUUID().toString(), card, null, null, emptySet(), correctedByUser = true)
    }

    fun replaceRank(id: String, rank: Rank) = edit { cards ->
        cards.map { if (it.id == id) it.copy(card = it.card.copy(rank = rank), correctedByUser = true) else it }
    }

    fun replaceSuit(id: String, suit: Suit) = edit { cards ->
        cards.map { if (it.id == id) it.copy(card = it.card.copy(suit = suit), correctedByUser = true) else it }
    }

    fun removeCard(id: String) = edit { cards -> cards.filterNot { it.id == id } }

    private fun edit(change: (List<ReviewCard>) -> List<ReviewCard>) {
        val state = uiState.value
        if (closed || !state.canEdit) return
        val cards = change(state.cards)
        transition(state.copy(cards = cards, total = total(cards), message = null))
    }

    fun confirm() {
        val state = uiState.value
        if (closed || !state.canConfirm) return
        // Freeze the exact payload before dispatch. A commit may succeed even if its caller is interrupted.
        transition(state.copy(phase = ScannerPhase.Saving, submissionStarted = true, recovery = null, message = null))
        val cards = state.cards.map { ConfirmedCard(it.card, it.confidence, it.correctedByUser, it.bounds) }
        val version = requireNotNull(state.modelVersion)
        scope.launch {
            try {
                if (request.correctionScoreId != null) {
                    repository.updateScannedScore(UpdateScannedScore(
                        request.matchId, request.roundId, request.participantId, cards, version,
                    ))
                } else {
                    repository.submitScannedScore(SubmitScannedScore(
                        request.matchId, request.roundId, request.participantId, cards,
                        request.submissionToken, version, request.preparedRound,
                    ))
                }
                transition(uiState.value.copy(phase = ScannerPhase.Review, saved = true))
            } catch (cancelled: CancellationException) {
                // Keep the saved Saving snapshot on disposal, so recreation offers an exact retry.
                if (!closed) saveFailed()
                throw cancelled
            } catch (_: Exception) {
                if (!closed) saveFailed()
            }
        }
    }

    private fun saveFailed() = transition(uiState.value.copy(
        phase = ScannerPhase.Error, recovery = ScannerRecovery.Save,
        message = ScannerMessage.SaveFailed,
    ))

    fun retry() {
        if (closed || uiState.value.phase != ScannerPhase.Error) return
        when (uiState.value.recovery) {
            ScannerRecovery.Save -> confirm()
            ScannerRecovery.Capture -> transition(ScannerUiState())
            null -> Unit
        }
    }

    fun cancel() {
        if (closed || uiState.value.phase == ScannerPhase.Saving || uiState.value.saved) return
        closed = true
        generation++
        processingJob?.cancel()
        transition(ScannerUiState())
        eventChannel.trySend(ScannerEvent.Cancelled)
    }

    /** Called only after repository observation and successful destination handling. */
    fun acknowledgeSaved(event: ScannerEvent.Saved) {
        if (!uiState.value.saved || event != savedEvent()) return
        savedStateHandle[COMPLETION_ACK] = request.submissionToken
        savedAcknowledged = true
        pendingSaved.value = null
    }

    private fun savedEvent() = ScannerEvent.Saved(request.matchId, request.roundId, request.participantId)
    private fun total(cards: List<ReviewCard>) = cards.fold(0) { sum, card ->
        Math.addExact(sum, CardValueCalculator.value(card.card, request.indicator))
    }

    private fun transition(state: ScannerUiState) {
        savedStateHandle[SNAPSHOT] = encodeState(state)
        mutableUiState.value = state
        pendingSaved.value = if (state.saved && !savedAcknowledged) savedEvent() else null
    }

    private fun restoreState(): ScannerUiState {
        val saved = snapshot ?: return ScannerUiState()
        // Never pair old cards with a fallback navigation request or guess missing correction metadata.
        if (restoredRequest?.isFailure == true) return captureRecovery()
        return runCatching {
            val version = saved.getInt("version")
            require(version in 1..2)
            val phase = ScannerPhase.valueOf(saved.requiredString("phase"))
            val awaiting = saved.requiredBoolean("awaiting")
            if (phase == ScannerPhase.Processing || awaiting) return@runCatching captureRecovery()
            val cards = saved.cardBundles().map(::decodeCard)
            require(cards.map { it.id }.distinct().size == cards.size)
            val state = ScannerUiState(
                phase = phase,
                source = saved.getString("source")?.let(ScanImageSource::valueOf),
                cards = cards, total = total(cards), modelVersion = saved.getString("model"),
                message = if (version == 2) saved.getString("message")?.let(ScannerMessage::valueOf)
                    else when {
                        saved.getString("message") == null -> null
                        saved.getString("recovery") == ScannerRecovery.Save.name -> ScannerMessage.SaveFailed
                        phase == ScannerPhase.Error -> ScannerMessage.RecognitionFailed
                        phase == ScannerPhase.Capture -> ScannerMessage.ImageExpired
                        else -> null
                    },
                recovery = saved.getString("recovery")?.let(ScannerRecovery::valueOf),
                submissionStarted = saved.requiredBoolean("started"), saved = saved.requiredBoolean("saved"),
            )
            if (phase == ScannerPhase.Review || phase == ScannerPhase.Saving || state.recovery == ScannerRecovery.Save) {
                require(!state.modelVersion.isNullOrBlank())
            } else require(cards.isEmpty() && !state.submissionStarted && !state.saved)
            if (state.submissionStarted || state.saved) require(cards.isNotEmpty())
            if (phase == ScannerPhase.Saving || state.recovery == ScannerRecovery.Save || state.saved) {
                require(state.submissionStarted)
            }
            if (phase == ScannerPhase.Saving) state.copy(
                phase = ScannerPhase.Error, recovery = ScannerRecovery.Save,
                message = ScannerMessage.SaveInterrupted,
            ) else state
        }.getOrElse { captureRecovery() }
    }

    private fun captureRecovery() = ScannerUiState(
        message = ScannerMessage.ImageExpired,
    )

    private fun encodeState(state: ScannerUiState) = Bundle().apply {
        putInt("version", 2)
        putBundle("request", encodeRequest(request))
        putString("phase", state.phase.name)
        putString("source", state.source?.name)
        putString("model", state.modelVersion)
        putString("message", state.message?.name)
        putString("recovery", state.recovery?.name)
        putBoolean("awaiting", state.awaitingInput)
        putBoolean("started", state.submissionStarted)
        putBoolean("saved", state.saved)
        putParcelableArrayList("cards", ArrayList(state.cards.map(::encodeCard)))
    }

    override fun onCleared() {
        closed = true
        generation++
        scope.cancel()
        eventChannel.close()
    }

    private companion object {
        const val SNAPSHOT = "scanner_snapshot"
        const val COMPLETION_ACK = "scanner_completion_acknowledged_token"

        fun encodeRequest(request: ScanRequest) = Bundle().apply {
            putString("match", request.matchId.value)
            putString("participant", request.participantId.value)
            putString("round", request.roundId.value)
            putString("rank", request.indicator.rank.name)
            putString("color", request.indicator.color.name)
            putString("token", request.submissionToken)
            putString("correction", request.correctionScoreId)
            putInt("preparedOrdinal", request.preparedRound?.ordinal ?: 0)
        }

        fun decodeRequest(bundle: Bundle): ScanRequest {
            require(bundle.containsKey("correction") && bundle.containsKey("preparedOrdinal"))
            val match = MatchId(bundle.requiredString("match"))
            val round = RoundId(bundle.requiredString("round"))
            val indicator = Indicator(Rank.valueOf(bundle.requiredString("rank")), CardColor.valueOf(bundle.requiredString("color")))
            val ordinal = bundle.getInt("preparedOrdinal")
            require(ordinal >= 0)
            return ScanRequest(
                match, ParticipantId(bundle.requiredString("participant")), round, indicator,
                if (ordinal > 0) PreparedRound(round, match, ordinal, indicator) else null,
                bundle.getString("correction"), bundle.requiredString("token"),
            )
        }

        fun encodeCard(card: ReviewCard) = Bundle().apply {
            putString("id", card.id)
            putString("rank", card.card.rank.name)
            putString("suit", card.card.suit.name)
            putFloatArray("confidence", card.confidence?.let { floatArrayOf(it) } ?: floatArrayOf())
            putFloatArray("bounds", card.bounds?.let { floatArrayOf(it.left, it.top, it.right, it.bottom) } ?: floatArrayOf())
            putStringArrayList("warnings", ArrayList(card.warnings.map { it.name }))
            putBoolean("corrected", card.correctedByUser)
        }

        fun decodeCard(bundle: Bundle): ReviewCard {
            val confidence = requireNotNull(bundle.getFloatArray("confidence"))
            require(confidence.size <= 1)
            val bounds = requireNotNull(bundle.getFloatArray("bounds"))
            require(bounds.isEmpty() || bounds.size == 4)
            val confirmed = ConfirmedCard(
                Card.Suited(Rank.valueOf(bundle.requiredString("rank")), Suit.valueOf(bundle.requiredString("suit"))),
                confidence.singleOrNull(), bundle.requiredBoolean("corrected"),
                if (bounds.isEmpty()) null else NormalizedRect(bounds[0], bounds[1], bounds[2], bounds[3]),
            )
            return ReviewCard(bundle.requiredString("id"), confirmed.card, confirmed.recognitionConfidence,
                confirmed.sourceBounds, requireNotNull(bundle.getStringArrayList("warnings")).map(RecognitionWarning::valueOf).toSet(),
                confirmed.correctedByUser)
        }

        fun Bundle.requiredString(key: String) = requireNotNull(getString(key)).also { require(it.isNotBlank()) }
        fun Bundle.requiredBoolean(key: String): Boolean {
            require(containsKey(key))
            return getBoolean(key)
        }

        @Suppress("DEPRECATION") // Typed overload requires API 33; this application supports API 26.
        fun Bundle.cardBundles(): ArrayList<Bundle> = requireNotNull(getParcelableArrayList("cards"))
    }
}
