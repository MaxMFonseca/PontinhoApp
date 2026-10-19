package com.maxmfonseca.pontinho.domain.statistics

import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.MatchOutcome
import com.maxmfonseca.pontinho.domain.model.MatchParticipant
import com.maxmfonseca.pontinho.domain.model.ParticipantId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.domain.model.PlayerNameKey
import com.maxmfonseca.pontinho.domain.repository.MatchSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class LifetimeStatisticsCalculatorTest {
    @Test fun aggregatesCompletedMatchesAcrossDaysAndExcludesActiveMatches() {
        val older = match("older", "2026-10-13T12:00:00Z", listOf("Ana" to 10, "Bruno" to 110), setOf(0))
        val newer = match("newer", "2026-10-14T12:00:00Z", listOf("Ana" to 20, "Bruno" to 101), setOf(0))
        val active = match("active", "2026-10-15T12:00:00Z", listOf("ANA" to 99, "Carla" to 80), null)

        val result = calculateLifetimeStatistics(listOf(active, older, newer))

        assertEquals(listOf(
            LifetimePlayerStatistics(PlayerNameKey("ana"), "Ana", 2, 2, 30L),
            LifetimePlayerStatistics(PlayerNameKey("bruno"), "Bruno", 2, 0, 211L),
        ), result)
        assertEquals(1.0, result[0].winRate, 0.0)
        assertEquals(0.0, result[1].winRate, 0.0)
    }

    @Test fun countsEveryTiedWinner() {
        val tied = match("tied", "2026-10-14T12:00:00Z", listOf("Bruno" to 15, "Ana" to 15, "Carla" to 101), setOf(0, 1))
        val later = match("later", "2026-10-15T12:00:00Z", listOf("Ana" to 110, "Carla" to 20), setOf(1))

        val result = calculateLifetimeStatistics(listOf(later, tied))

        assertEquals(listOf(
            LifetimePlayerStatistics(PlayerNameKey("ana"), "Ana", 2, 1, 125L),
            LifetimePlayerStatistics(PlayerNameKey("bruno"), "Bruno", 1, 1, 15L),
            LifetimePlayerStatistics(PlayerNameKey("carla"), "Carla", 2, 1, 121L),
        ), result)
        assertEquals(0.5, result[0].winRate, 0.0)
    }

    @Test fun groupsNormalizedNamesUsingNewestCompletedSpelling() {
        val older = match("older", "2026-10-13T12:00:00Z", listOf(" ana " to 110, "BRUNO" to 5), setOf(1))
        val newer = match("newer", "2026-10-14T12:00:00Z", listOf("Ana" to 10, "Bruno" to 101), setOf(0))
        val active = match("active", "2026-10-15T12:00:00Z", listOf("aNA" to 90, "bruno" to 80), null)

        assertEquals(listOf(
            LifetimePlayerStatistics(PlayerNameKey("ana"), "Ana", 2, 1, 120L),
            LifetimePlayerStatistics(PlayerNameKey("bruno"), "Bruno", 2, 1, 106L),
        ), calculateLifetimeStatistics(listOf(older, active, newer)))
    }

    @Test fun accumulatedPointsUseLongArithmetic() {
        val first = match("first", "2026-10-13T12:00:00Z", listOf("Ana" to Int.MAX_VALUE, "Bruno" to 0), setOf(1))
        val second = match("second", "2026-10-14T12:00:00Z", listOf("Ana" to Int.MAX_VALUE, "Bruno" to 0), setOf(1))

        assertEquals(listOf(
            LifetimePlayerStatistics(PlayerNameKey("ana"), "Ana", 2, 0, 4_294_967_294L),
            LifetimePlayerStatistics(PlayerNameKey("bruno"), "Bruno", 2, 2, 0L),
        ), calculateLifetimeStatistics(listOf(first, second)))
    }

    private fun match(
        id: String,
        createdAt: String,
        players: List<Pair<String, Int>>,
        winnerIndices: Set<Int>?,
    ): MatchSnapshot {
        val instant = Instant.parse(createdAt)
        val participants = players.mapIndexed { index, (name, _) ->
            MatchParticipant(ParticipantId("$id-$index"), PlayerId("$id-player-$index"), name, index, 1)
        }
        val totals = participants.mapIndexed { index, participant -> participant.id to players[index].second }.toMap()
        return MatchSnapshot(
            id = MatchId(id),
            localDate = instant.atZone(ZoneOffset.UTC).toLocalDate(),
            timeZoneId = "UTC",
            createdAt = instant,
            lastPlayedAt = instant,
            participants = participants,
            rounds = emptyList(),
            totals = totals,
            dealerParticipantId = participants.first().id,
            isFinished = winnerIndices != null,
            finishedAt = instant.takeIf { winnerIndices != null },
            outcome = winnerIndices?.let { indices ->
                MatchOutcome(indices.map { participants[it].id }.toSet(),
                    totals.filterValues { it == totals.values.max() }.keys,
                    totals.values.min(), totals.values.max())
            },
            resultRevision = if (winnerIndices != null) 1L else 0L,
            acknowledgedResultRevision = 0L,
        )
    }
}
