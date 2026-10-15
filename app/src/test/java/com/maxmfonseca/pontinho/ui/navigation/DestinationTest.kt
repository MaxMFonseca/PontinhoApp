package com.maxmfonseca.pontinho.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.*
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.ui.scanner.ScanRequest
import com.maxmfonseca.pontinho.ui.test.matchSnapshot
import java.time.LocalDate

class DestinationTest {
    @Test fun staleChangedJokerInvalidatesScannerContext() {
        val match = matchSnapshot("match", LocalDate.of(2026, 10, 14), 0, listOf("a" to "Ana", "b" to "Bruno"))
        val player = match.participants.first().id
        val round = RoundSnapshot(RoundId("round"), 1, Indicator(Rank.KING, CardColor.BLACK), player,
            RoundDraft(match.participants.map { it.id }), java.time.Instant.EPOCH, null, RoundStatus.OPEN, false)
        val context = ScannerContext("match", "round", player.value, 0, "KING", "BLACK", "token", null)
        assertNotNull(context.resolve(match.copy(rounds = listOf(round))))
        for (indicator in listOf(null, Indicator(Rank.ACE, CardColor.BLACK), Indicator(Rank.KING, CardColor.RED))) {
            assertNull(context.resolve(match.copy(rounds = listOf(round.copy(indicator = indicator)))))
        }
    }

    @Test fun scannerEncodesOnlyScalarContextAndValidatesPreparedRound() {
        val match = matchSnapshot("m/a +%25", LocalDate.of(2026, 10, 14), 0, listOf("a" to "Ana", "b" to "Bruno"))
        val indicator = Indicator(Rank.KING, CardColor.BLACK)
        val request = ScanRequest(match.id, match.participants.first().id, RoundId("r/1"), indicator,
            PreparedRound(RoundId("r/1"), match.id, 1, indicator), submissionToken = "t/+%25")
        val context = ScannerContext.from(request)
        val route = Destination.Scanner.createRoute(request)
        assertTrue(route.contains("m%2Fa%20%2B%2525"))
        assertTrue(route.contains("r%2F1"))
        assertTrue(route.contains("t%2F%2B%2525"))
        assertEquals(request, context.resolve(match))
        assertNull(context.resolve(match.copy(id = MatchId("other"))))
        assertNull(context.copy(participant = "missing").resolve(match))
        assertNull(context.copy(ordinal = 2).resolve(match))
        assertNull(context.copy(correction = "a").resolve(match))
        assertNull(context.copy(token = " ").resolve(match))
    }

    @Test fun scannerRejectsMalformedAndMismatchedCorrectionWithoutDowngradingToSubmission() {
        val match = matchSnapshot("match", LocalDate.of(2026, 10, 14), 0, listOf("a" to "Ana", "b" to "Bruno"))
        val player = match.participants.first().id
        val indicator = Indicator(Rank.KING, CardColor.BLACK)
        val round = RoundSnapshot(RoundId("round"), 1, indicator, player,
            RoundDraft(match.participants.map { it.id }, mapOf(player to RoundEntry(5, RoundEntryOrigin.EXPLICIT))),
            java.time.Instant.EPOCH, null, RoundStatus.OPEN, false)
        val snapshot = match.copy(rounds = listOf(round))
        val context = ScannerContext("match", "round", player.value, 0, "KING", "BLACK", "token", player.value)
        assertEquals(player.value, context.resolve(snapshot)!!.correctionScoreId)
        assertNull(context.copy(correction = "another-score").resolve(snapshot))
        assertNull(context.copy(round = "missing").resolve(snapshot))
        assertNull(context.copy(rank = "ACE").resolve(snapshot))
        assertNull(context.copy(ordinal = -1).resolve(snapshot))
        assertNull(context.resolve(snapshot.copy(rounds = listOf(round.copy(draft = round.draft.copy(entries = emptyMap()))))))
        assertNull(context.resolve(snapshot.copy(rounds = listOf(round.copy(draft = round.draft.copy(
            entries = mapOf(player to RoundEntry(0, RoundEntryOrigin.AUTOMATIC_WINNER))))))))
        assertNull(ScannerContext.decode { null })
        assertNull(ScannerContext.decode { if (it == "ordinal") "NaN" else "value" })
    }

    @Test
    fun staticDestinationsExposeStableRoutes() {
        assertEquals("home", Destination.Home.route)
        assertEquals("setup", Destination.Setup.route)
        assertEquals("settings", Destination.Settings.route)
        assertEquals("appearance", Destination.Appearance.route)
        assertEquals("language", Destination.Language.route)
        assertEquals("learn", Destination.Learn.route)
        assertEquals("about", Destination.About.route)
        assertEquals("privacy-licenses", Destination.PrivacyLicenses.route)
    }

    @Test
    fun parameterizedDestinationsEncodeArguments() {
        assertEquals("match/{matchId}", Destination.Match.route)
        assertEquals("match/match%2Fone", Destination.Match.createRoute("match/one"))
        assertEquals("day/{localDate}", Destination.DayHistory.route)
        assertEquals("day/2026-10-13", Destination.DayHistory.createRoute("2026-10-13"))
        assertEquals("results/{matchId}", Destination.Results.route)
        assertEquals("results/match%20one", Destination.Results.createRoute("match one"))
    }
}
