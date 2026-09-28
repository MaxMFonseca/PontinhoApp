package com.maxmfonseca.pontinho.data.repository

import androidx.room.withTransaction
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.local.entity.*
import com.maxmfonseca.pontinho.data.local.mapper.toDomain
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.domain.rules.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

class RoomMatchRepository(
    private val db: PontinhoDatabase,
    clock: Clock? = null,
) : MatchRepository {
    // A long-lived repository must not freeze the device's zone at construction.
    private val clock: Clock = clock ?: object : Clock() {
        override fun instant(): Instant = Instant.now()
        override fun getZone(): ZoneId = ZoneId.systemDefault()
        override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
    }
    private val matches = db.matchDao()
    private val rounds = db.roundDao()

    // Observe all snapshot inputs, then read the complete aggregate in one transaction.
    private fun changes() = db.invalidationTracker.createFlow("matches", "participants", "rounds", "scores")

    override fun observeMatch(matchId: MatchId): Flow<MatchSnapshot?> = changes().map {
        db.withTransaction { snapshot(matchId.value) }
    }.distinctUntilChanged()

    override fun observeHome(): Flow<List<DaySummary>> = changes().map {
        db.withTransaction {
            matches.matches().map { requireNotNull(snapshot(it.id)) }
                .groupBy { it.localDate }.toSortedMap(compareByDescending { it })
                .map { (date, items) -> DaySummary(date, items) }
        }
    }.distinctUntilChanged()

    override fun observeDay(localDate: LocalDate): Flow<DayHistory> = changes().map {
        db.withTransaction {
            val snapshots = matches.matchesForDate(localDate.toString()).map { requireNotNull(snapshot(it.id)) }
            dayHistory(localDate, snapshots)
        }
    }.distinctUntilChanged()

    override suspend fun createMatch(command: CreateMatch): MatchId = db.withTransaction {
        require(command.creationToken.isNotBlank()) { "Creation token must not be blank" }
        require(command.players.size >= 2) { "At least two players are required" }
        require(command.players.map { it.draftId }.all { it.isNotBlank() }) { "Draft identity must not be blank" }
        require(command.players.map { it.draftId }.distinct().size == command.players.size) { "Duplicate draft identity" }
        val normalized = command.players.map { player ->
            player.copy(displayName = player.displayName.trim().also { require(it.isNotEmpty()) { "Player name must not be blank" } })
        }
        val fingerprint = creationFingerprint(normalized)
        matches.getByCreationToken(command.creationToken)?.let { existing ->
            require(existing.creationFingerprint == fingerprint) { "Creation token was already used for a different command" }
            return@withTransaction MatchId(existing.id)
        }
        val nameKeys = normalized.map { PlayerNameKey.from(it.displayName) }
        if (nameKeys.distinct().size != nameKeys.size) throw DuplicatePlayerNameException()
        val now = clock.instant()
        val zone = clock.zone
        val matchId = uuid()
        val players = normalized.map { requested ->
            requested.playerId?.let {
                val referenced = requireNotNull(db.playerDao().get(it.value)) { "Unknown player" }
                require(referenced.archivedAt == null) { "Archived player" }
            }
            resolvePlayer(requested.displayName, stablePlayerId(command.creationToken, requested.draftId), now.toEpochMilli())
        }
        val roster = players.mapIndexed { index, player ->
            MatchParticipantEntity(uuid(), matchId, player.id, player.displayName, index, 1, now.toEpochMilli())
        }
        matches.insertMatch(MatchEntity(
            matchId, now.atZone(zone).toLocalDate().toString(), zone.id,
            now.toEpochMilli(), now.toEpochMilli(), dealerParticipantId = roster.first().id,
            creationToken = command.creationToken,
            creationFingerprint = fingerprint,
        ))
        roster.forEach { matches.insertParticipant(it) }
        MatchId(matchId)
    }

    override suspend fun addPlayerToMatch(command: AddPlayerToMatch): ParticipantId = db.withTransaction {
        require(command.operationToken.isNotBlank()) { "Operation token must not be blank" }
        val name = command.displayName.trim().also { require(it.isNotEmpty()) { "Player name must not be blank" } }
        val participantId = stablePlayerId("late-participant:${command.matchId.value}", command.operationToken)
        val playerId = stablePlayerId("late-player:${command.matchId.value}", command.operationToken)
        val roster = matches.participants(command.matchId.value)
        // The immutable participation snapshot is the durable operation receipt.
        // Check replay before gameplay guards: a committed retry must still succeed
        // after another command reorders the roster, opens a round, or ends the match.
        roster.singleOrNull { it.id == participantId }?.let { existing ->
            require(existing.nameSnapshot == name) {
                "Operation token was already used for a different command"
            }
            return@withTransaction ParticipantId(existing.id)
        }
        val match = activeMatch(command.matchId)
        betweenRounds(match.id)
        if (roster.any { PlayerNameKey.from(it.nameSnapshot) == PlayerNameKey.from(name) }) {
            throw DuplicatePlayerNameException()
        }
        val ordinal = (rounds.rounds(match.id).maxOfOrNull { it.ordinal } ?: 0) + 1
        val now = clock.millis()
        val player = resolvePlayer(name, playerId, now, roster.map { it.playerId }.toSet())
        matches.insertParticipant(MatchParticipantEntity(participantId, match.id, player.id, name, roster.size, ordinal, now))
        matches.updateMatch(match.copy(lastPlayedAt = now))
        ParticipantId(participantId)
    }

    override suspend fun addParticipant(command: AddParticipant) = db.withTransaction {
        val match = activeMatch(command.matchId)
        betweenRounds(match.id)
        val roster = matches.participants(match.id)
        require(command.rosterOrder in 0..roster.size) { "Invalid roster position" }
        val player = requireNotNull(db.playerDao().get(command.playerId.value)) { "Unknown player" }
        require(player.archivedAt == null) { "Archived player" }
        if (roster.any { PlayerNameKey.from(it.nameSnapshot) == PlayerNameKey.from(player.displayName) }) {
            throw DuplicatePlayerNameException()
        }
        val canonical = resolvePlayer(player.displayName, uuid(), clock.millis(), roster.map { it.playerId }.toSet())
        val ordinal = (rounds.rounds(match.id).maxOfOrNull { it.ordinal } ?: 0) + 1
        // Shift from the tail so the unique roster position is never violated.
        roster.filter { it.rosterOrder >= command.rosterOrder }.asReversed().forEach {
            matches.updateParticipant(it.copy(rosterOrder = it.rosterOrder + 1))
        }
        matches.insertParticipant(MatchParticipantEntity(
            uuid(), match.id, canonical.id, player.displayName, command.rosterOrder, ordinal, clock.millis(),
        ))
        matches.updateMatch(match.copy(lastPlayedAt = clock.millis()))
    }

    override suspend fun reorderParticipants(command: ReorderParticipants) = db.withTransaction {
        val match = activeMatch(command.matchId)
        betweenRounds(match.id)
        val roster = matches.participants(match.id)
        require(command.participantIds.size == roster.size && command.participantIds.toSet() == roster.map { ParticipantId(it.id) }.toSet()) {
            "Order must contain every participant exactly once"
        }
        // Temporary negative positions avoid uniqueness collisions when swapping.
        roster.forEachIndexed { index, row -> matches.updateParticipant(row.copy(rosterOrder = -index - 1)) }
        command.participantIds.forEachIndexed { index, id ->
            matches.updateParticipant(roster.single { it.id == id.value }.copy(rosterOrder = index))
        }
        matches.updateMatch(match.copy(lastPlayedAt = clock.millis()))
    }

    override suspend fun chooseIndicator(command: ChooseIndicator): PreparedRound = db.withTransaction {
        val match = activeMatch(command.matchId)
        betweenRounds(match.id)
        val ordinal = (rounds.rounds(match.id).maxOfOrNull { it.ordinal } ?: 0) + 1
        PreparedRound(RoundId(uuid()), command.matchId, ordinal, command.indicator)
    }

    override suspend fun designateWinner(command: DesignateWinner) = db.withTransaction {
        val match = activeMatch(command.matchId)
        val round = writableRound(match, command.roundId, command.preparedRound)
        val draft = draft(round)
        val after = RoundEngine.designateWinner(draft, command.participantId)
        persistTransition(match, round, after)
    }

    override suspend fun submitScore(command: SubmitScore) = db.withTransaction {
        require(command.submissionToken.isNotBlank()) { "Submission token must not be blank" }
        val receipt = SubmissionEntity(command.submissionToken, command.matchId.value, command.roundId.value, command.participantId.value, command.points)
        rounds.submission(command.submissionToken)?.let {
            require(it == receipt) { "Submission token was already used for a different command" }
            return@withTransaction
        }
        val match = activeMatch(command.matchId)
        val round = writableRound(match, command.roundId, command.preparedRound)
        val after = applied(RoundEngine.submitScore(draft(round), command.participantId, command.points))
        persistTransition(match, round, after, command.submissionToken)
        rounds.insertSubmission(receipt)
    }

    override suspend fun removeRoundEntry(command: RemoveRoundEntry) = db.withTransaction {
        val match = requireMatch(command.matchId)
        val round = ownedRound(match, command.roundId)
        val before = draft(round)
        val after = applied(RoundEngine.removeEntry(before, command.participantId))
        val correctingRound = if (round.status == "COMPLETE") {
            pauseOpenRounds(match.id)
            round.copy(isCorrection = true)
        } else round
        // Do not invoke completion inference here. The domain removal is a stable correction state.
        persistTransition(match, correctingRound, after, correction = true)
        audit(match.id, "REMOVE_ENTRY", round, before, after)
    }

    override suspend fun updateExplicitScore(command: UpdateExplicitScore) = db.withTransaction {
        require(command.points >= 0) { "Points must be nonnegative" }
        val match = requireMatch(command.matchId)
        val round = ownedRound(match, command.roundId)
        val before = draft(round)
        val entry = before.entries[command.participantId]
        require(entry?.origin == RoundEntryOrigin.EXPLICIT) { "Only explicit scores can be edited" }
        val after = if (before.isComplete) {
            before.copy(entries = before.entries + (command.participantId to requireNotNull(entry).copy(points = command.points)))
        } else {
            val withoutOldScore = applied(RoundEngine.removeEntry(before, command.participantId))
            applied(RoundEngine.submitScore(withoutOldScore, command.participantId, command.points))
        }
        persistTransition(match, round, after, correction = true)
        audit(match.id, "UPDATE_EXPLICIT_SCORE", round, before, after)
    }

    override suspend fun acknowledgeResults(matchId: MatchId, revision: Long) = db.withTransaction {
        val match = requireMatch(matchId)
        require(match.status == "FINISHED" && revision == match.resultRevision) { "Stale result revision" }
        matches.updateMatch(match.copy(acknowledgedResultRevision = revision))
    }

    private suspend fun persistTransition(
        match: MatchEntity,
        round: RoundEntity,
        after: RoundDraft,
        submissionToken: String? = null,
        correction: Boolean = false,
    ) {
        val now = clock.millis()
        val oldScores = rounds.scores(round.id).associateBy { ParticipantId(it.participantId) }
        oldScores.filterKeys { it !in after.entries }.values.forEach { rounds.deleteScore(it.id) }
        after.entries.forEach { (participant, entry) ->
            val old = oldScores[participant]
            val origin = if (entry.origin == RoundEntryOrigin.AUTOMATIC_WINNER) "AUTO_WINNER" else "MANUAL"
            if (old == null) {
                rounds.insertScore(RoundScoreEntity(uuid(), match.id, round.id, participant.value, entry.points, origin, now,
                    if (origin == "AUTO_WINNER") null else requireNotNull(submissionToken)))
            } else if (old.points != entry.points || old.origin != origin) {
                rounds.updateScore(old.copy(points = entry.points, origin = origin))
            }
        }
        val nextDealer = if (after.isComplete && round.status != "COMPLETE") {
            DealerRotation.next(ParticipantId(round.dealerParticipantId), after.eligibleParticipants).value
        } else round.nextDealerParticipantId
        val status = when {
            after.isComplete -> "COMPLETE"
            round.status == "PAUSED" -> "PAUSED"
            else -> "OPEN"
        }
        rounds.updateRound(round.copy(
            designatedWinnerParticipantId = after.designatedWinner?.value,
            designationOrigin = after.winner?.origin?.name,
            status = status,
            openSlot = if (status == "OPEN") 1 else null,
            completedAt = if (after.isComplete) round.completedAt ?: now else null,
            nextDealerParticipantId = nextDealer,
            isCorrection = !after.isComplete && round.isCorrection,
            pauseOrder = if (status == "PAUSED") round.pauseOrder else null,
        ))
        recompute(match, correction, now)
    }

    private suspend fun recompute(match: MatchEntity, correction: Boolean, now: Long) {
        val roster = matches.participants(match.id)
        var history = rounds.rounds(match.id)
        var totals = roster.associate { ParticipantId(it.id) to 0 }
        history.filter { it.status == "COMPLETE" }.forEach { round ->
            totals = when (val transition = MatchOutcomeCalculator.addRound(totals,
                rounds.scores(round.id).associate { ParticipantId(it.participantId) to it.points })) {
                is MatchTotalsTransition.Applied -> transition.totals
                is MatchTotalsTransition.Rejected -> throw IllegalArgumentException(transition.failure.toString())
            }
        }
        roster.forEach { matches.updateParticipant(it.copy(total = totals.getValue(ParticipantId(it.id)))) }
        // Only an intentionally reopened correction defers results. An unrelated
        // unfinished draft must not hide a threshold crossing in completed history.
        val hasIncompleteCorrection = history.any { it.isCorrection && it.status != "COMPLETE" }
        val outcome = if (hasIncompleteCorrection) null else MatchOutcomeCalculator.evaluate(totals)
        val finished = outcome != null
        if (finished) {
            pauseOpenRounds(match.id)
        } else if (history.none { it.status == "OPEN" }) {
            val resume = history.filter { it.status == "PAUSED" }.maxWithOrNull(
                compareBy<RoundEntity> { it.isCorrection }.thenBy { it.pauseOrder ?: 0 },
            )
            resume?.let { rounds.updateRound(it.copy(status = "OPEN", openSlot = 1, pauseOrder = null)) }
        }
        history = rounds.rounds(match.id)
        val open = history.singleOrNull { it.status == "OPEN" }
        val resultChanged = (finished && match.status != "FINISHED") || (correction && (finished || match.status == "FINISHED"))
        val dealer = open?.dealerParticipantId ?: history.lastOrNull { it.status == "COMPLETE" }?.nextDealerParticipantId ?: match.dealerParticipantId
        matches.updateMatch(match.copy(
            lastPlayedAt = now,
            status = if (finished) "FINISHED" else "ACTIVE",
            finishedAt = if (finished) match.finishedAt ?: now else null,
            dealerParticipantId = requireNotNull(dealer),
            resultRevision = match.resultRevision + if (resultChanged) 1 else 0,
            acknowledgedResultRevision = if (correction || resultChanged) 0 else match.acknowledgedResultRevision,
        ))
    }

    private suspend fun snapshot(id: String): MatchSnapshot? {
        val match = matches.get(id) ?: return null
        val roster = matches.participants(id)
        val totals = roster.associate { ParticipantId(it.id) to it.total }
        return MatchSnapshot(
            MatchId(id), LocalDate.parse(match.localDate), match.timeZoneId, Instant.ofEpochMilli(match.createdAt),
            Instant.ofEpochMilli(match.lastPlayedAt), roster.map { it.toDomain() },
            rounds.rounds(id).map { it.toDomain(roster, rounds.scores(it.id)) }, totals,
            ParticipantId(match.dealerParticipantId), match.status == "FINISHED",
            match.finishedAt?.let(Instant::ofEpochMilli),
            if (match.status == "FINISHED") MatchOutcomeCalculator.evaluate(totals) else null,
            match.resultRevision, match.acknowledgedResultRevision,
        )
    }

    private suspend fun draft(round: RoundEntity) = round.toDomain(matches.participants(round.matchId), rounds.scores(round.id)).draft
    private suspend fun requireMatch(id: MatchId) = requireNotNull(matches.get(id.value)) { "Unknown match" }
    private suspend fun activeMatch(id: MatchId) = requireMatch(id).also { require(it.status == "ACTIVE") { "Match is finished" } }
    private suspend fun betweenRounds(id: String) { require(rounds.rounds(id).all { it.status == "COMPLETE" }) { "A round is unfinished" } }
    private suspend fun ownedRound(match: MatchEntity, id: RoundId) = requireNotNull(rounds.get(id.value)) { "Unknown round" }
        .also { require(it.matchId == match.id) { "Round belongs to another match" } }

    private suspend fun writableRound(match: MatchEntity, id: RoundId, prepared: PreparedRound?): RoundEntity {
        val existing = rounds.get(id.value)
        if (existing != null) {
            require(existing.matchId == match.id) { "Round belongs to another match" }
            require(existing.status == "OPEN") { "Round is not open" }
            if (prepared != null) {
                require(prepared.id == id && prepared.matchId.value == match.id && prepared.ordinal == existing.ordinal &&
                    prepared.indicator.rank.name == existing.indicatorRank && prepared.indicator.color.name == existing.indicatorColor) {
                    "Prepared round does not match the persisted round"
                }
            }
            return existing
        }
        val preparation = requireNotNull(prepared) { "The first entry requires a prepared indicator" }
        require(preparation.id == id && preparation.matchId.value == match.id) { "Prepared round belongs to another command" }
        betweenRounds(match.id)
        val ordinal = (rounds.rounds(match.id).maxOfOrNull { it.ordinal } ?: 0) + 1
        require(preparation.ordinal == ordinal) { "Prepared round is stale" }
        val roster = matches.eligibleParticipants(match.id, ordinal)
        val round = RoundEntity(
            id.value, match.id, ordinal, preparation.indicator.rank.name, preparation.indicator.color.name,
            match.dealerParticipantId, roster.size, createdAt = clock.millis(),
        )
        // This insert shares the caller's transaction with the first score or designation.
        rounds.insertRound(round)
        return round
    }

    private suspend fun pauseOpenRounds(matchId: String) {
        val history = rounds.rounds(matchId)
        var order = history.maxOfOrNull { it.pauseOrder ?: 0 } ?: 0
        history.filter { it.status == "OPEN" }.forEach {
            rounds.updateRound(it.copy(status = "PAUSED", openSlot = null, pauseOrder = ++order))
        }
    }
    private fun applied(transition: RoundTransition) = when (transition) {
        is RoundTransition.Applied -> transition.state
        is RoundTransition.Rejected -> throw RoundRuleException(transition.failure)
    }

    private suspend fun audit(matchId: String, operation: String, round: RoundEntity, before: RoundDraft, after: RoundDraft) {
        matches.insertCorrection(CorrectionEventEntity(uuid(), matchId, clock.millis(), operation, payload(round, before), payload(round, after)))
    }

    private fun payload(round: RoundEntity, state: RoundDraft): String = JSONObject().apply {
        put("roundId", round.id)
        put("ordinal", round.ordinal)
        put("complete", state.isComplete)
        put("winnerId", state.designatedWinner?.value ?: JSONObject.NULL)
        put("winnerOrigin", state.winner?.origin?.name ?: JSONObject.NULL)
        put("entries", JSONArray().apply {
            state.entries.toSortedMap(compareBy { it.value }).forEach { (id, entry) ->
                put(JSONObject().put("participantId", id.value).put("points", entry.points).put("origin", entry.origin.name))
            }
        })
    }.toString()

    private fun uuid(): String = UUID.randomUUID().toString()

    // Call only inside the command transaction: lookup and insertion must serialize.
    private suspend fun resolvePlayer(
        name: String,
        newId: String,
        now: Long,
        occupiedPlayerIds: Set<String> = emptySet(),
    ): PlayerEntity {
        val key = PlayerNameKey.from(name)
        val canonical = db.playerDao().getCanonicalByNormalizedName(key.value)
        require(canonical?.archivedAt == null) { "Archived player" }
        // A legacy rename may leave this UUID in the roster under another snapshot name.
        // Use a new technical row in that case; the immutable snapshot and name identity stay intact.
        val existing = canonical?.takeUnless { it.id in occupiedPlayerIds }
        if (existing == null) {
            require(db.playerDao().get(newId) == null) { "Generated player identity collision" }
        }
        return PlayerEntity(existing?.id ?: newId, name, key.value, existing?.createdAt ?: now, now)
            .also { db.playerDao().upsert(it) }
    }

    private fun stablePlayerId(token: String, draftId: String): String = UUID.nameUUIDFromBytes(
        "$token\u0000$draftId".toByteArray(StandardCharsets.UTF_8),
    ).toString()

    private fun creationFingerprint(players: List<CreateMatchPlayer>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        players.forEach { player ->
            listOf(player.draftId, player.playerId?.value.orEmpty(), player.displayName).forEach { value ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(byteArrayOf(
                    (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(), bytes.size.toByte(),
                ))
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun dayHistory(localDate: LocalDate, snapshots: List<MatchSnapshot>): DayHistory {
        val ordered = snapshots.sortedWith(compareByDescending<MatchSnapshot> { it.createdAt }.thenByDescending { it.id.value })
        val historyMatches = ordered.map { match ->
            val winners = match.outcome?.winners.orEmpty()
            val highest = match.outcome?.highestScorers.orEmpty()
            HistoryMatch(
                id = match.id,
                createdAtEpochMillis = match.createdAt.toEpochMilli(),
                isFinished = match.isFinished,
                players = match.participants.sortedBy { it.rosterOrder }.map { participant ->
                    HistoryPlayer(
                        playerId = participant.playerId,
                        displayName = participant.displayName,
                        total = match.totals.getValue(participant.id),
                        isWinner = match.isFinished && participant.id in winners,
                        isHighestPenalty = match.isFinished && participant.id in highest,
                    )
                },
                timeZoneId = match.timeZoneId,
            )
        }
        val completed = historyMatches.filter { it.isFinished }
        val names = buildMap {
            // Matches are newest first, so the first snapshot is the player's latest display name.
            completed.flatMap { it.players }.forEach { putIfAbsent(PlayerNameKey.from(it.displayName), it.displayName) }
        }
        val winsByPlayer = completed.flatMap { match -> match.players.filter { it.isWinner }.map { PlayerNameKey.from(it.displayName) } }
            .groupingBy { it }.eachCount()
        val wins = names.map { (id, displayName) ->
            DailyStanding(id, displayName, winsByPlayer[id] ?: 0)
        }
            .sortedWith(compareByDescending<DailyStanding> { it.value }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                .thenBy { it.displayName }.thenBy { it.playerKey.value })
        val penalties = completed.flatMap { it.players }.groupBy { PlayerNameKey.from(it.displayName) }
            .map { (id, entries) -> DailyStanding(id, names.getValue(id), entries.sumOf { it.total }) }
            .sortedWith(compareByDescending<DailyStanding> { it.value }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                .thenBy { it.displayName }.thenBy { it.playerKey.value })
        return DayHistory(localDate, historyMatches, wins, penalties)
    }
}
