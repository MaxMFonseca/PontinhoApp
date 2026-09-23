package com.maxmfonseca.pontinho.settings

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class LanguagePreference {
    SYSTEM,
    PORTUGUESE_BRAZIL,
    ENGLISH_UNITED_STATES;

    fun resolve(deviceLanguageTag: String): String = when (this) {
        PORTUGUESE_BRAZIL -> PORTUGUESE_BRAZIL_TAG
        ENGLISH_UNITED_STATES -> ENGLISH_UNITED_STATES_TAG
        SYSTEM -> when (deviceLanguageTag) {
            PORTUGUESE_BRAZIL_TAG -> PORTUGUESE_BRAZIL_TAG
            ENGLISH_UNITED_STATES_TAG -> ENGLISH_UNITED_STATES_TAG
            else -> PORTUGUESE_BRAZIL_TAG
        }
    }

    companion object {
        const val PORTUGUESE_BRAZIL_TAG = "pt-BR"
        const val ENGLISH_UNITED_STATES_TAG = "en-US"
    }
}

data class AppPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val language: LanguagePreference = LanguagePreference.SYSTEM,
    /** `null` follows the device animation setting; a Boolean is an explicit override. */
    val reducedMotion: Boolean? = null,
) {
    fun resolveReducedMotion(systemReducedMotion: Boolean): Boolean =
        reducedMotion ?: systemReducedMotion
}
