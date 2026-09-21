package com.maxmfonseca.pontinho.domain.model

@JvmInline
value class PlayerId(val value: String) {
    init {
        require(value.isNotBlank()) { "PlayerId cannot be blank" }
    }
}

@JvmInline
value class ParticipantId(val value: String) {
    init {
        require(value.isNotBlank()) { "ParticipantId cannot be blank" }
    }
}

@JvmInline
value class MatchId(val value: String) {
    init {
        require(value.isNotBlank()) { "MatchId cannot be blank" }
    }
}

@JvmInline
value class RoundId(val value: String) {
    init {
        require(value.isNotBlank()) { "RoundId cannot be blank" }
    }
}
