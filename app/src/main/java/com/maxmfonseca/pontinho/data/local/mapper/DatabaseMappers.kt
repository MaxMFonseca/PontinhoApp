package com.maxmfonseca.pontinho.data.local.mapper

import com.maxmfonseca.pontinho.data.local.entity.*
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.RoundSnapshot
import com.maxmfonseca.pontinho.domain.repository.RoundStatus
import java.time.Instant

internal fun PlayerEntity.toDomain() = Player(PlayerId(id), displayName)
internal fun MatchParticipantEntity.toDomain() = MatchParticipant(
    ParticipantId(id), PlayerId(playerId), nameSnapshot, rosterOrder, eligibleFromRoundOrdinal,
)

internal fun RoundEntity.toDomain(
    participants: List<MatchParticipantEntity>,
    scores: List<RoundScoreEntity>,
) = RoundSnapshot(
    id = RoundId(id),
    ordinal = ordinal,
    indicator = Indicator(Rank.valueOf(indicatorRank), CardColor.valueOf(indicatorColor)),
    dealerParticipantId = ParticipantId(dealerParticipantId),
    draft = RoundDraft(
        eligibleParticipants = participants.filter { it.eligibleFromRoundOrdinal <= ordinal }.map { ParticipantId(it.id) },
        entries = scores.associate { ParticipantId(it.participantId) to RoundEntry(
            it.points, if (it.origin == "AUTO_WINNER") RoundEntryOrigin.AUTOMATIC_WINNER else RoundEntryOrigin.EXPLICIT,
        ) },
        winner = designatedWinnerParticipantId?.let {
            WinnerDesignation(ParticipantId(it), WinnerDesignationOrigin.valueOf(requireNotNull(designationOrigin)))
        },
        isComplete = status == "COMPLETE",
    ),
    createdAt = Instant.ofEpochMilli(createdAt),
    completedAt = completedAt?.let(Instant::ofEpochMilli),
    status = RoundStatus.valueOf(status),
    isCorrection = isCorrection,
)
