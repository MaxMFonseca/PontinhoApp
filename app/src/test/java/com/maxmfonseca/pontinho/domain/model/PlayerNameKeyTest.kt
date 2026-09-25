package com.maxmfonseca.pontinho.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PlayerNameKeyTest {
    @Test fun trimsAndIgnoresCapitalizationButPreservesSpelling() {
        assertEquals(PlayerNameKey("max"), PlayerNameKey.from(" MAX "))
        assertEquals(PlayerNameKey("julia2"), PlayerNameKey.from("Julia2"))
        assertNotEquals(PlayerNameKey.from("Julia"), PlayerNameKey.from("Julia2"))
    }

    @Test fun normalizationDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(PlayerNameKey("iris"), PlayerNameKey.from("IRIS"))
        } finally { Locale.setDefault(previous) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankNames() { PlayerNameKey.from(" \t\n ") }
}
