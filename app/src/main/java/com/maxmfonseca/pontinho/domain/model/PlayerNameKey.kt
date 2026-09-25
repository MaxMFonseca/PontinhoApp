package com.maxmfonseca.pontinho.domain.model

import java.util.Locale

@JvmInline
value class PlayerNameKey(val value: String) {
    companion object {
        fun from(displayName: String): PlayerNameKey {
            val normalized = displayName.trim().lowercase(Locale.ROOT)
            require(normalized.isNotBlank()) { "Player name must not be blank" }
            return PlayerNameKey(normalized)
        }
    }
}
