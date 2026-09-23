package com.maxmfonseca.pontinho.ui.test

import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate

class FakePlayerRepository : PlayerRepository {
    val upserts = mutableListOf<UpsertPlayer>()
    val returnedIds = mutableListOf<PlayerId>()
    private val players = MutableStateFlow<List<Player>>(emptyList())

    override fun observePlayers(): Flow<List<Player>> = players

    override suspend fun upsert(command: UpsertPlayer): PlayerId {
        upserts += command
        val id = command.id ?: PlayerId("created-${returnedIds.size + 1}")
        returnedIds += id
        return id
    }
}

class FakeMatchRepository(
    val home: MutableStateFlow<List<DaySummary>> = MutableStateFlow(emptyList()),
    private val createdMatchId: MatchId = MatchId("created-match"),
    private val createBlock: (suspend (CreateMatch) -> MatchId)? = null,
) : MatchRepository {
    val createdCommands = mutableListOf<CreateMatch>()

    override fun observeHome(): Flow<List<DaySummary>> = home
    override fun observeMatch(matchId: MatchId): Flow<MatchSnapshot?> = MutableStateFlow(null)
    override suspend fun createMatch(command: CreateMatch): MatchId {
        createdCommands += command
        return createBlock?.invoke(command) ?: createdMatchId
    }
    override suspend fun addParticipant(command: AddParticipant) = unsupported()
    override suspend fun addPlayerToMatch(command: AddPlayerToMatch): ParticipantId = unsupported()
    override suspend fun reorderParticipants(command: ReorderParticipants) = unsupported()
    override suspend fun chooseIndicator(command: ChooseIndicator): PreparedRound = unsupported()
    override suspend fun designateWinner(command: DesignateWinner) = unsupported()
    override suspend fun submitScore(command: SubmitScore) = unsupported()
    override suspend fun removeRoundEntry(command: RemoveRoundEntry) = unsupported()
    override suspend fun updateExplicitScore(command: UpdateExplicitScore) = unsupported()
    override suspend fun acknowledgeResults(matchId: MatchId, revision: Long) = unsupported()

    private fun unsupported(): Nothing = error("Not used by this test")
}

fun matchSnapshot(
    id: String,
    date: LocalDate,
    lastPlayedEpochSecond: Long,
    players: List<Pair<String, String>>,
): MatchSnapshot {
    val participants = players.mapIndexed { index, (playerId, name) ->
        MatchParticipant(
            id = ParticipantId("$id-participant-$index"),
            playerId = PlayerId(playerId),
            displayName = name,
            rosterOrder = index,
            eligibleFromRoundOrdinal = 1,
        )
    }
    return MatchSnapshot(
        id = MatchId(id),
        localDate = date,
        timeZoneId = "America/Sao_Paulo",
        createdAt = Instant.ofEpochSecond(lastPlayedEpochSecond - 1),
        lastPlayedAt = Instant.ofEpochSecond(lastPlayedEpochSecond),
        participants = participants,
        rounds = emptyList(),
        totals = participants.associate { it.id to 0 },
        dealerParticipantId = participants.first().id,
        isFinished = false,
        finishedAt = null,
        outcome = null,
        resultRevision = 0,
        acknowledgedResultRevision = 0,
    )
}
