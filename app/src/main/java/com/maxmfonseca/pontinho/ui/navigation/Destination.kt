package com.maxmfonseca.pontinho.ui.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.ui.scanner.ScanRequest

sealed interface Destination {
    val route: String

    data object Home : Destination { override val route = "home" }
    data object Scanner : Destination {
        override val route = "scanner/{match}/{round}/{participant}/{ordinal}/{rank}/{color}/{token}?correction={correction}"
        val argumentNames = listOf("match", "round", "participant", "ordinal", "rank", "color", "token", "correction")
        fun createRoute(request: ScanRequest): String = ScannerContext.from(request).let { context ->
            "scanner/" + listOf(context.match, context.round, context.participant, context.ordinal.toString(),
                context.rank, context.color, context.token).joinToString("/") { it.routeEncoded() } +
                (context.correction?.let { "?correction=${it.routeEncoded()}" } ?: "")
        }
    }
    data object Setup : Destination { override val route = "setup" }
    data object Match : Destination {
        const val MATCH_ID = "matchId"
        override val route = "match/{$MATCH_ID}"
        fun createRoute(matchId: String) = "match/${matchId.routeEncoded()}"
    }
    data object DayHistory : Destination {
        const val LOCAL_DATE = "localDate"
        override val route = "day/{$LOCAL_DATE}"
        fun createRoute(localDate: String) = "day/${localDate.routeEncoded()}"
    }
    data object Results : Destination {
        const val MATCH_ID = "matchId"
        override val route = "results/{$MATCH_ID}"
        fun createRoute(matchId: String) = "results/${matchId.routeEncoded()}"
    }
    data object Settings : Destination { override val route = "settings" }
    data object Appearance : Destination { override val route = "appearance" }
    data object Language : Destination { override val route = "language" }
    data object Learn : Destination { override val route = "learn" }
    data object About : Destination { override val route = "about" }
}

private fun String.routeEncoded(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.toString()).replace("+", "%20")

data class ScannerContext(val match: String, val round: String, val participant: String,
    val ordinal: Int, val rank: String, val color: String, val token: String, val correction: String?) {
    /** Arguments are already decoded once by Navigation. Never decode a literal percent escape again. */
    fun resolve(snapshot: MatchSnapshot?, allowCommitted: Boolean = false): ScanRequest? = runCatching {
        require(listOf(match, round, participant, token).all { it.isNotBlank() })
        require(ordinal >= 0 && (correction == null || correction == participant && ordinal == 0))
        val current = requireNotNull(snapshot)
        require(current.id.value == match)
        val player = current.participants.single { it.id.value == participant }
        val indicator = Indicator(Rank.valueOf(rank), CardColor.valueOf(color))
        val persisted = current.rounds.singleOrNull { it.id.value == round }
        val prepared = if (persisted == null) {
            require(correction == null && ordinal > 0 && !current.isFinished)
            require(current.openRound == null && current.pausedRounds.isEmpty())
            require(ordinal == (current.rounds.maxOfOrNull { it.ordinal } ?: 0) + 1)
            require(player.eligibleFromRoundOrdinal <= ordinal)
            PreparedRound(RoundId(round), current.id, ordinal, indicator)
        } else {
            require(persisted.indicator == indicator && (ordinal == 0 || ordinal == persisted.ordinal))
            require(player.id in persisted.draft.eligibleParticipants)
            val entry = persisted.draft.entries[player.id]
            if (correction != null) require(entry?.origin == RoundEntryOrigin.EXPLICIT)
            else if (allowCommitted && entry?.origin == RoundEntryOrigin.EXPLICIT) Unit
            else require(!current.isFinished && persisted.status == RoundStatus.OPEN && entry == null &&
                persisted.draft.designatedWinner != player.id)
            // Keep the original prepared payload for an exact idempotent retry after commit.
            if (ordinal > 0) PreparedRound(persisted.id, current.id, ordinal, indicator) else null
        }
        ScanRequest(current.id, player.id, RoundId(round), indicator, prepared, correction, token)
    }.getOrNull()
    companion object {
        fun decode(argument: (String) -> String?): ScannerContext? = runCatching {
            ScannerContext(requireNotNull(argument("match")), requireNotNull(argument("round")),
                requireNotNull(argument("participant")), requireNotNull(argument("ordinal")).toInt(),
                requireNotNull(argument("rank")), requireNotNull(argument("color")),
                requireNotNull(argument("token")), argument("correction"))
        }.getOrNull()
        fun from(request: ScanRequest) = ScannerContext(request.matchId.value, request.roundId.value,
            request.participantId.value, request.preparedRound?.ordinal ?: 0, request.indicator.rank.name,
            request.indicator.color.name, request.submissionToken, request.correctionScoreId)
    }
}
