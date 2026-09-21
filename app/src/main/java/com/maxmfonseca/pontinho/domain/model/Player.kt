package com.maxmfonseca.pontinho.domain.model

data class Player(
    val id: PlayerId,
    val displayName: String,
)

data class MatchParticipant(
    val id: ParticipantId,
    val playerId: PlayerId,
    val displayName: String,
    val rosterOrder: Int,
    val eligibleFromRoundOrdinal: Int,
)
