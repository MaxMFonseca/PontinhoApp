package com.maxmfonseca.pontinho.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationTest {
    @Test
    fun staticDestinationsExposeStableRoutes() {
        assertEquals("home", Destination.Home.route)
        assertEquals("setup", Destination.Setup.route)
        assertEquals("settings", Destination.Settings.route)
        assertEquals("appearance", Destination.Appearance.route)
        assertEquals("language", Destination.Language.route)
        assertEquals("learn", Destination.Learn.route)
        assertEquals("about", Destination.About.route)
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
