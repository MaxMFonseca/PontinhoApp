package com.maxmfonseca.pontinho.ui.scanner

import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.recognition.NormalizedRect
import com.maxmfonseca.pontinho.domain.recognition.RecognitionWarning
import com.maxmfonseca.pontinho.domain.repository.PreparedRound
import java.util.UUID

data class ScanRequest(
    val matchId: MatchId,
    val participantId: ParticipantId,
    val roundId: RoundId,
    val indicator: Indicator,
    val preparedRound: PreparedRound? = null,
    /** Selects correction; the repository addresses the score by match/round/participant. */
    val correctionScoreId: String? = null,
    val submissionToken: String = UUID.randomUUID().toString(),
) {
    init {
        require(submissionToken.isNotBlank())
        require(correctionScoreId == null || correctionScoreId.isNotBlank())
        require(preparedRound == null || (
            preparedRound.matchId == matchId && preparedRound.id == roundId &&
                preparedRound.indicator == indicator && preparedRound.ordinal > 0
            ))
        require(correctionScoreId == null || preparedRound == null)
    }
}

enum class ScannerPhase { Capture, Processing, Review, Saving, Error }
enum class ScanImageSource { Camera, Photo }
enum class ScannerRecovery { Capture, Save }
/** Saved as a stable code; translated only when rendered in the current locale. */
enum class ScannerMessage {
    CaptureGuidance, NoCards, TooManyCards, IncompleteCards, ImageTooLarge,
    Unavailable, RecognitionFailed, SaveFailed, SaveInterrupted, ImageExpired,
}

data class ReviewCard(
    /** Physical observation identity; duplicate rank/suit values remain separate cards. */
    val id: String,
    val card: Card.Suited,
    val confidence: Float?,
    val bounds: NormalizedRect?,
    val warnings: Set<RecognitionWarning>,
    val correctedByUser: Boolean,
)

data class ScannerUiState(
    val phase: ScannerPhase = ScannerPhase.Capture,
    val source: ScanImageSource? = null,
    val cards: List<ReviewCard> = emptyList(),
    val total: Int = 0,
    val modelVersion: String? = null,
    val message: ScannerMessage? = null,
    val recovery: ScannerRecovery? = null,
    val awaitingInput: Boolean = false,
    val submissionStarted: Boolean = false,
    val saved: Boolean = false,
) {
    val canEdit: Boolean get() = phase == ScannerPhase.Review && !submissionStarted && !saved
    val canConfirm: Boolean get() = !saved && cards.isNotEmpty() && !modelVersion.isNullOrBlank() &&
        (phase == ScannerPhase.Review || phase == ScannerPhase.Error && recovery == ScannerRecovery.Save)
    val hasLowConfidence: Boolean get() = cards.any { RecognitionWarning.LOW_CONFIDENCE in it.warnings }
}

sealed interface ScannerEvent {
    data class Saved(val matchId: MatchId, val roundId: RoundId, val participantId: ParticipantId) : ScannerEvent
    data object Cancelled : ScannerEvent
}
