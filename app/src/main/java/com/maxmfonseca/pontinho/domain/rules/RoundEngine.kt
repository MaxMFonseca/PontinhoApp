package com.maxmfonseca.pontinho.domain.rules

import com.maxmfonseca.pontinho.domain.model.ParticipantId
import com.maxmfonseca.pontinho.domain.model.RoundDraft
import com.maxmfonseca.pontinho.domain.model.RoundEntry
import com.maxmfonseca.pontinho.domain.model.RoundEntryOrigin
import com.maxmfonseca.pontinho.domain.model.WinnerDesignation
import com.maxmfonseca.pontinho.domain.model.WinnerDesignationOrigin

sealed interface RoundEvent {
    data class ScoreSubmitted(val participantId: ParticipantId, val points: Int) : RoundEvent
    data class WinnerDesignated(
        val participantId: ParticipantId,
        val origin: WinnerDesignationOrigin,
    ) : RoundEvent

    data class RoundCompleted(val winnerId: ParticipantId) : RoundEvent
    data class EntryRemoved(val participantId: ParticipantId) : RoundEvent
    data class WinnerDesignationRemoved(val participantId: ParticipantId) : RoundEvent
    data object RoundReopened : RoundEvent
}

sealed interface RoundFailure {
    data class ParticipantNotEligible(val participantId: ParticipantId) : RoundFailure
    data class NegativePoints(val points: Int) : RoundFailure
    data class DuplicateExplicitSubmission(val participantId: ParticipantId) : RoundFailure
    data class DesignatedWinnerCannotSubmit(val participantId: ParticipantId) : RoundFailure
    data class EntryNotFound(val participantId: ParticipantId) : RoundFailure
    data object RoundAlreadyComplete : RoundFailure
    data object WinnerAlreadyDesignated : RoundFailure
}

class RoundRuleException(val failure: RoundFailure) : IllegalArgumentException(failure.toString())

sealed interface RoundTransition {
    val state: RoundDraft

    data class Applied(
        override val state: RoundDraft,
        val events: List<RoundEvent>,
    ) : RoundTransition

    data class Rejected(
        override val state: RoundDraft,
        val failure: RoundFailure,
    ) : RoundTransition
}

object RoundEngine {
    fun designateWinner(state: RoundDraft, participantId: ParticipantId): RoundDraft {
        designationFailure(state, participantId)?.let { throw RoundRuleException(it) }

        val designated = state.copy(
            winner = WinnerDesignation(participantId, WinnerDesignationOrigin.MANUAL),
        )
        return if (allOtherEligiblePlayersSubmitted(designated, participantId)) {
            complete(designated, participantId)
        } else {
            designated
        }
    }

    fun submitScore(
        state: RoundDraft,
        participantId: ParticipantId,
        points: Int,
    ): RoundTransition {
        scoreFailure(state, participantId, points)?.let {
            return RoundTransition.Rejected(state, it)
        }

        var nextState = state.copy(
            entries = state.entries + (
                participantId to RoundEntry(points, RoundEntryOrigin.EXPLICIT)
                ),
        )
        val events = mutableListOf<RoundEvent>(RoundEvent.ScoreSubmitted(participantId, points))

        val existingWinner = nextState.winner
        if (existingWinner != null) {
            if (allOtherEligiblePlayersSubmitted(nextState, existingWinner.participantId)) {
                nextState = complete(nextState, existingWinner.participantId)
                events += RoundEvent.RoundCompleted(existingWinner.participantId)
            }
        } else {
            val explicitParticipants = nextState.explicitParticipantIds()
            if (explicitParticipants.size == nextState.eligibleParticipants.size - 1) {
                val inferredWinner = nextState.eligibleParticipants.single { it !in explicitParticipants }
                nextState = complete(
                    nextState.copy(
                        winner = WinnerDesignation(
                            inferredWinner,
                            WinnerDesignationOrigin.INFERRED,
                        ),
                    ),
                    inferredWinner,
                )
                events += RoundEvent.WinnerDesignated(
                    inferredWinner,
                    WinnerDesignationOrigin.INFERRED,
                )
                events += RoundEvent.RoundCompleted(inferredWinner)
            }
        }

        return RoundTransition.Applied(nextState, events.toList())
    }

    fun removeEntry(state: RoundDraft, participantId: ParticipantId): RoundTransition {
        val entry = state.entries[participantId]
        if (entry == null) {
            return if (!state.isComplete && state.designatedWinner == participantId) {
                RoundTransition.Applied(
                    state = state.copy(winner = null),
                    events = listOf(RoundEvent.WinnerDesignationRemoved(participantId)),
                )
            } else {
                RoundTransition.Rejected(state, RoundFailure.EntryNotFound(participantId))
            }
        }

        if (!state.isComplete) {
            return RoundTransition.Applied(
                state = state.copy(entries = state.entries - participantId),
                events = listOf(RoundEvent.EntryRemoved(participantId)),
            )
        }

        val winnerId = state.entries.entries
            .single { it.value.origin == RoundEntryOrigin.AUTOMATIC_WINNER }
            .key
        val retainedWinner = state.winner?.takeIf {
            participantId != winnerId && it.origin == WinnerDesignationOrigin.MANUAL
        }
        val reopenedEntries = state.entries - participantId - winnerId

        return RoundTransition.Applied(
            state = state.copy(
                entries = reopenedEntries,
                winner = retainedWinner,
                isComplete = false,
            ),
            events = listOf(
                RoundEvent.EntryRemoved(participantId),
                RoundEvent.RoundReopened,
            ),
        )
    }

    private fun designationFailure(
        state: RoundDraft,
        participantId: ParticipantId,
    ): RoundFailure? = when {
        state.isComplete -> RoundFailure.RoundAlreadyComplete
        participantId !in state.eligibleParticipants -> RoundFailure.ParticipantNotEligible(participantId)
        state.winner != null -> RoundFailure.WinnerAlreadyDesignated
        state.entries[participantId]?.origin == RoundEntryOrigin.EXPLICIT ->
            RoundFailure.DuplicateExplicitSubmission(participantId)
        else -> null
    }

    private fun scoreFailure(
        state: RoundDraft,
        participantId: ParticipantId,
        points: Int,
    ): RoundFailure? = when {
        state.entries[participantId]?.origin == RoundEntryOrigin.EXPLICIT ->
            RoundFailure.DuplicateExplicitSubmission(participantId)
        state.isComplete -> RoundFailure.RoundAlreadyComplete
        participantId !in state.eligibleParticipants -> RoundFailure.ParticipantNotEligible(participantId)
        points < 0 -> RoundFailure.NegativePoints(points)
        state.designatedWinner == participantId ->
            RoundFailure.DesignatedWinnerCannotSubmit(participantId)
        else -> null
    }

    private fun RoundDraft.explicitParticipantIds(): Set<ParticipantId> = entries
        .filterValues { it.origin == RoundEntryOrigin.EXPLICIT }
        .keys

    private fun allOtherEligiblePlayersSubmitted(
        state: RoundDraft,
        winnerId: ParticipantId,
    ): Boolean {
        val explicitParticipants = state.explicitParticipantIds()
        return state.eligibleParticipants
            .filterNot { it == winnerId }
            .all { it in explicitParticipants }
    }

    private fun complete(state: RoundDraft, winnerId: ParticipantId): RoundDraft = state.copy(
        entries = state.entries + (
            winnerId to RoundEntry(points = 0, origin = RoundEntryOrigin.AUTOMATIC_WINNER)
            ),
        isComplete = true,
    )
}
