package com.maxmfonseca.pontinho.domain.rules

import com.maxmfonseca.pontinho.domain.model.MatchOutcome
import com.maxmfonseca.pontinho.domain.model.ParticipantId

sealed interface MatchTotalFailure {
    data class TotalOverflow(
        val participantId: ParticipantId,
        val currentTotal: Int,
        val roundPoints: Int,
    ) : MatchTotalFailure
}

sealed interface MatchTotalsTransition {
    val totals: Map<ParticipantId, Int>

    data class Applied(
        override val totals: Map<ParticipantId, Int>,
    ) : MatchTotalsTransition

    data class Rejected(
        override val totals: Map<ParticipantId, Int>,
        val failure: MatchTotalFailure,
    ) : MatchTotalsTransition
}

object MatchOutcomeCalculator {
    fun addRound(
        totals: Map<ParticipantId, Int>,
        roundScores: Map<ParticipantId, Int>,
    ): MatchTotalsTransition {
        require(totals.values.all { it >= 0 }) { "Existing totals must be nonnegative" }
        require(roundScores.values.all { it >= 0 }) { "Round scores must be nonnegative" }

        val updatedTotals = totals.toMutableMap()
        roundScores.forEach { (participantId, roundPoints) ->
            val currentTotal = totals[participantId] ?: 0
            val candidate = currentTotal.toLong() + roundPoints.toLong()
            if (candidate > Int.MAX_VALUE) {
                return MatchTotalsTransition.Rejected(
                    totals = totals,
                    failure = MatchTotalFailure.TotalOverflow(
                        participantId = participantId,
                        currentTotal = currentTotal,
                        roundPoints = roundPoints,
                    ),
                )
            }
            updatedTotals[participantId] = candidate.toInt()
        }
        return MatchTotalsTransition.Applied(updatedTotals.toMap())
    }

    fun evaluate(totals: Map<ParticipantId, Int>): MatchOutcome? {
        require(totals.values.all { it >= 0 }) { "Match totals must be nonnegative" }
        if (totals.isEmpty() || totals.values.none { it > 100 }) return null

        val minimum = totals.values.min()
        val maximum = totals.values.max()
        return MatchOutcome(
            winners = totals.filterValues { it == minimum }.keys,
            highestScorers = totals.filterValues { it == maximum }.keys,
            minimumTotal = minimum,
            maximumTotal = maximum,
        )
    }
}
