package com.maxmfonseca.pontinho.domain.statistics

import com.maxmfonseca.pontinho.domain.model.PlayerNameKey
import com.maxmfonseca.pontinho.domain.repository.MatchSnapshot

data class LifetimePlayerStatistics(
    val playerKey: PlayerNameKey,
    val displayName: String,
    val completedMatches: Int,
    val wins: Int,
    val points: Long,
) {
    val winRate: Double get() = wins.toDouble() / completedMatches
}

fun calculateLifetimeStatistics(matches: List<MatchSnapshot>): List<LifetimePlayerStatistics> {
    val statistics = mutableMapOf<PlayerNameKey, LifetimePlayerStatistics>()
    val completed = matches.filter { it.isFinished }.sortedWith(
        compareByDescending<MatchSnapshot> { it.createdAt }.thenByDescending { it.id.value },
    )
    completed.forEach { match ->
        val winnerKeys = match.participants.filter { it.id in match.outcome?.winners.orEmpty() }
            .map { PlayerNameKey.from(it.displayName) }.toSet()
        match.participants.groupBy { PlayerNameKey.from(it.displayName) }.forEach { (key, participants) ->
            val previous = statistics[key] ?: LifetimePlayerStatistics(key, participants.first().displayName, 0, 0, 0L)
            statistics[key] = previous.copy(
                completedMatches = previous.completedMatches + 1,
                wins = previous.wins + if (key in winnerKeys) 1 else 0,
                points = previous.points + participants.sumOf { match.totals.getValue(it.id).toLong() },
            )
        }
    }
    return statistics.values.sortedWith(
        compareBy<LifetimePlayerStatistics, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            .thenBy { it.displayName }.thenBy { it.playerKey.value },
    )
}
