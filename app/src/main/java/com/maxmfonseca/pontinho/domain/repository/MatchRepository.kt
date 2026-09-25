package com.maxmfonseca.pontinho.domain.repository

import com.maxmfonseca.pontinho.domain.model.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

data class CreateMatchPlayer(
    val draftId: String,
    val playerId: PlayerId? = null,
    val displayName: String,
)
data class CreateMatch(val creationToken: String, val players: List<CreateMatchPlayer>)
data class AddParticipant(val matchId: MatchId, val playerId: PlayerId, val rosterOrder: Int)
data class AddPlayerToMatch(val matchId: MatchId, val operationToken: String, val displayName: String)
data class ReorderParticipants(val matchId: MatchId, val participantIds: List<ParticipantId>)
data class ChooseIndicator(val matchId: MatchId, val indicator: Indicator)
/** Transient picker result. Discarding it changes no persistent match state. */
data class PreparedRound(val id: RoundId, val matchId: MatchId, val ordinal: Int, val indicator: Indicator)
data class DesignateWinner(val matchId: MatchId, val roundId: RoundId, val participantId: ParticipantId, val preparedRound: PreparedRound? = null)
data class SubmitScore(val matchId: MatchId, val roundId: RoundId, val participantId: ParticipantId, val points: Int, val submissionToken: String, val preparedRound: PreparedRound? = null)
data class RemoveRoundEntry(val matchId: MatchId, val roundId: RoundId, val participantId: ParticipantId)
data class UpdateExplicitScore(val matchId: MatchId, val roundId: RoundId, val participantId: ParticipantId, val points: Int)

enum class RoundStatus { OPEN, PAUSED, COMPLETE }

data class RoundSnapshot(
    val id: RoundId,
    val ordinal: Int,
    val indicator: Indicator,
    val dealerParticipantId: ParticipantId,
    val draft: RoundDraft,
    val createdAt: Instant,
    val completedAt: Instant?,
    val status: RoundStatus,
    val isCorrection: Boolean,
)

data class MatchSnapshot(
    val id: MatchId,
    val localDate: LocalDate,
    val timeZoneId: String,
    val createdAt: Instant,
    val lastPlayedAt: Instant,
    val participants: List<MatchParticipant>,
    val rounds: List<RoundSnapshot>,
    val totals: Map<ParticipantId, Int>,
    val dealerParticipantId: ParticipantId,
    val isFinished: Boolean,
    val finishedAt: Instant?,
    val outcome: MatchOutcome?,
    val resultRevision: Long,
    val acknowledgedResultRevision: Long,
) {
    val hasPendingResults: Boolean get() = isFinished && resultRevision > acknowledgedResultRevision
    val openRound: RoundSnapshot? get() = rounds.singleOrNull { it.status == RoundStatus.OPEN }
    val pausedRounds: List<RoundSnapshot> get() = rounds.filter { it.status == RoundStatus.PAUSED }
}

data class DaySummary(val localDate: LocalDate, val matches: List<MatchSnapshot>) {
    val matchCount: Int get() = matches.size
    val activeMatchCount: Int get() = matches.count { !it.isFinished }
}

data class HistoryPlayer(
    val playerId: PlayerId,
    val displayName: String,
    val total: Int,
    val isWinner: Boolean,
    val isHighestPenalty: Boolean,
)

data class HistoryMatch(
    val id: MatchId,
    val createdAtEpochMillis: Long,
    val isFinished: Boolean,
    val players: List<HistoryPlayer>,
    val timeZoneId: String = "UTC",
)

data class DailyStanding(val playerKey: PlayerNameKey, val displayName: String, val value: Int)

data class DayHistory(
    val localDate: LocalDate,
    val matches: List<HistoryMatch>,
    val wins: List<DailyStanding>,
    val penalties: List<DailyStanding>,
)

/** Writes commit before returning. [createMatch] atomically resolves or creates players,
 * snapshots the ordered roster, and creates the match. Its creation token is durably
 * idempotent for the same payload and is rejected for a different payload.
 * Invalid commands throw IllegalArgumentException.
 * Round-rule rejections preserve their typed RoundRuleException.failure.
 */
interface MatchRepository {
    fun observeHome(): Flow<List<DaySummary>>
    fun observeDay(localDate: LocalDate): Flow<DayHistory>
    fun observeMatch(matchId: MatchId): Flow<MatchSnapshot?>
    suspend fun createMatch(command: CreateMatch): MatchId
    suspend fun addParticipant(command: AddParticipant)
    /** Atomically resolves a named player and appends their participation between rounds.
     * The token is scoped to this match: replay with the same trimmed name returns the
     * original participant without writes, even if the roster or match has since changed.
     * A different name for a committed token is rejected. Failure/cancellation before
     * commit leaves neither player nor participant. Names resolve to a canonical player;
     * duplicate normalized names within a match are rejected.
     */
    suspend fun addPlayerToMatch(command: AddPlayerToMatch): ParticipantId
    suspend fun reorderParticipants(command: ReorderParticipants)
    /** Reads and prepares the next round; only its first score/designation persists it. */
    suspend fun chooseIndicator(command: ChooseIndicator): PreparedRound
    suspend fun designateWinner(command: DesignateWinner)
    suspend fun submitScore(command: SubmitScore)
    suspend fun removeRoundEntry(command: RemoveRoundEntry)
    suspend fun updateExplicitScore(command: UpdateExplicitScore)
    suspend fun acknowledgeResults(matchId: MatchId, revision: Long)
}
