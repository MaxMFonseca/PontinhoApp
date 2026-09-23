package com.maxmfonseca.pontinho.ui.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed interface Destination {
    val route: String

    data object Home : Destination { override val route = "home" }
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
