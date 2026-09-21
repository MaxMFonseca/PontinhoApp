package com.maxmfonseca.pontinho.domain.rules

import com.maxmfonseca.pontinho.domain.model.ParticipantId

object DealerRotation {
    fun next(current: ParticipantId, roster: List<ParticipantId>): ParticipantId {
        val currentIndex = roster.indexOf(current)
        require(currentIndex >= 0) { "Current dealer must be in the eligible roster" }
        return roster[(currentIndex + 1) % roster.size]
    }
}
