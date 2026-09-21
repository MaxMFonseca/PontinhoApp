package com.maxmfonseca.pontinho.domain.model

enum class RoundEntryOrigin {
    EXPLICIT,
    AUTOMATIC_WINNER,
}

data class RoundEntry(
    val points: Int,
    val origin: RoundEntryOrigin,
)

enum class WinnerDesignationOrigin {
    MANUAL,
    INFERRED,
}

data class WinnerDesignation(
    val participantId: ParticipantId,
    val origin: WinnerDesignationOrigin,
)

data class RoundDraft(
    val eligibleParticipants: List<ParticipantId>,
    val entries: Map<ParticipantId, RoundEntry> = emptyMap(),
    val winner: WinnerDesignation? = null,
    val isComplete: Boolean = false,
) {
    init {
        require(eligibleParticipants.size >= 2) { "A round requires at least two eligible participants" }
        require(eligibleParticipants.distinct().size == eligibleParticipants.size) {
            "Eligible participants must be unique"
        }
    }

    val designatedWinner: ParticipantId?
        get() = winner?.participantId
}
