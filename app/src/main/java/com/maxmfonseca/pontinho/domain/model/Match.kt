package com.maxmfonseca.pontinho.domain.model

data class MatchOutcome(
    val winners: Set<ParticipantId>,
    val highestScorers: Set<ParticipantId>,
    val minimumTotal: Int,
    val maximumTotal: Int,
)
