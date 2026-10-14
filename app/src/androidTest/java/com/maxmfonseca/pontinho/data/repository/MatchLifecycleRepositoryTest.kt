package com.maxmfonseca.pontinho.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MatchLifecycleRepositoryTest {
    private lateinit var db: PontinhoDatabase
    private lateinit var repo: RoomMatchRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC)
    private val indicator = Indicator(Rank.KING, CardColor.RED)

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PontinhoDatabase::class.java).build()
        repo = RoomMatchRepository(db, clock)
    }

    @After fun close() { db.close() }

    @Test fun historyPlayersSortDescendingWithDeterministicTies() = runBlocking {
        val id = finish(listOf(0, 110, 110, 0, 40, 40, 40))
        val stored = db.matchDao().participants(id.value)
        // Snapshot names can differ from canonical names. Seed every tie-break level.
        val names = listOf("zoe", "bruno", "Ana", "Alice", "same", "same", "Same")
        val sameNameOrder = listOf(4, 5).sortedBy { stored[it].id }
        // Make roster order oppose the stable-ID tie-break, regardless of generated IDs.
        val rosterOrder = listOf(0, 1, 2, 3) + sameNameOrder.reversed() + 6
        stored.forEachIndexed { index, participant ->
            db.matchDao().updateParticipant(participant.copy(rosterOrder = -index - 1))
        }
        rosterOrder.forEachIndexed { order, index ->
            db.matchDao().updateParticipant(stored[index].copy(nameSnapshot = names[index], rosterOrder = order))
        }
        val expectedOrder = listOf(2, 1, 6) + sameNameOrder + listOf(3, 0)

        val history = repo.observeDay(LocalDate.parse("2026-10-14")).first().matches.single()

        assertEquals(listOf(110, 110, 40, 40, 40, 0, 0), history.players.map { it.total })
        assertEquals(expectedOrder.map { PlayerId(stored[it].playerId) }, history.players.map { it.playerId })
        assertEquals(listOf("Alice", "zoe"), history.players.filter { it.isWinner }.map { it.displayName })
        assertEquals(listOf("Ana", "bruno"), history.players.filter { it.isHighestPenalty }.map { it.displayName })

        val active = create()
        val activePlayers = db.matchDao().participants(active.value)
        listOf("Zed", "alice", "Bob").forEachIndexed { index, name ->
            db.matchDao().updateParticipant(activePlayers[index].copy(nameSnapshot = name))
        }
        val activeHistory = repo.observeDay(LocalDate.parse("2026-10-14")).first().matches.single { it.id == active }
        assertEquals(listOf("alice", "Bob", "Zed"), activeHistory.players.map { it.displayName })
        assertTrue(activeHistory.players.all { !it.isWinner && !it.isHighestPenalty })
    }

    @Test fun deleteActiveMatchCascadesMatchRowsAndKeepsCanonicalPlayers() = runBlocking {
        val id = create()
        completeFirstRound(id)
        val completed = snapshot(id)
        repo.updateExplicitScore(UpdateExplicitScore(id, completed.rounds.single().id, completed.participants[0].id, 11))
        val prepared = repo.prepareRound(PrepareRound(id, indicator))
        submit(prepared, completed.participants[0].id, 4)
        val survivor = create()
        completeFirstRound(survivor)
        val survivorBefore = snapshot(survivor)
        val playersBefore = db.playerDao().players()
        val dependentTables = listOf("participants", "rounds", "scores", "submissions", "corrections")
        val survivorCounts = dependentTables.associateWith { rowCount(it, survivor) }
        assertFalse(snapshot(id).isFinished)
        dependentTables.forEach { assertTrue("Seeded $it", rowCount(it, id) > 0) }

        repo.deleteMatch(id)

        assertNull(db.matchDao().get(id.value))
        assertNull(repo.observeMatch(id).first())
        dependentTables.forEach { assertEquals("Deleted $it", 0, rowCount(it, id)) }
        assertEquals(playersBefore, db.playerDao().players())
        assertEquals(survivorBefore, snapshot(survivor))
        assertEquals(survivorCounts, dependentTables.associateWith { rowCount(it, survivor) })
        try {
            repo.deleteMatch(id)
            fail("Unknown match must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(survivorBefore, snapshot(survivor))
        assertEquals(playersBefore, db.playerDao().players())
    }

    @Test fun deleteFinishedTiedMatchRecalculatesDaySummary() = runBlocking {
        val removed = finish(listOf(0, 0, 110, 110))
        val remaining = finish(listOf(110, 20, 0, 30))
        val date = LocalDate.parse("2026-10-14")
        val before = repo.observeDay(date).first()
        val tied = before.matches.single { it.id == removed }
        assertEquals(listOf("Player 1", "Player 2"), tied.players.filter { it.isWinner }.map { it.displayName })
        assertEquals(listOf("Player 3", "Player 4"), tied.players.filter { it.isHighestPenalty }.map { it.displayName })
        assertEquals(mapOf("player 1" to 1, "player 2" to 1, "player 3" to 1, "player 4" to 0), before.wins.associate { it.playerKey.value to it.value })
        assertEquals(mapOf("player 1" to 110, "player 2" to 20, "player 3" to 110, "player 4" to 140), before.penalties.associate { it.playerKey.value to it.value })

        repo.deleteMatch(removed)

        val after = repo.observeDay(date).first()
        assertEquals(listOf(remaining), after.matches.map { it.id })
        assertEquals(mapOf("player 1" to 0, "player 2" to 0, "player 3" to 1, "player 4" to 0), after.wins.associate { it.playerKey.value to it.value })
        assertEquals(mapOf("player 1" to 110, "player 2" to 20, "player 3" to 0, "player 4" to 30), after.penalties.associate { it.playerKey.value to it.value })
        assertEquals(4, db.playerDao().players().size)
    }

    private fun rowCount(table: String, id: MatchId): Int = db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table WHERE matchId = ?", arrayOf(id.value)).use {
            check(it.moveToFirst())
            it.getInt(0)
        }

    private suspend fun finish(points: List<Int>): MatchId {
        val id = repo.createMatch(CreateMatch(UUID.randomUUID().toString(), points.indices.map {
            CreateMatchPlayer("draft-$it", displayName = "Player ${it + 1}")
        }))
        val participants = snapshot(id).participants
        val round = repo.prepareRound(PrepareRound(id, indicator))
        val winner = points.indexOf(0)
        repo.designateWinner(DesignateWinner(id, round.id, participants[winner].id, round))
        points.forEachIndexed { index, score -> if (index != winner) submit(round, participants[index].id, score) }
        assertTrue(snapshot(id).isFinished)
        return id
    }

    @Test fun joinOpenRoundUsesCurrentOrdinalWithoutBackfillingScores() = runBlocking {
        val id = create()
        completeFirstRound(id)
        val prepared = repo.prepareRound(PrepareRound(id, indicator))
        val original = snapshot(id).participants
        submit(prepared, original[0].id, 5)
        val before = snapshot(id)
        val priorScores = db.roundDao().scores(before.rounds.first().id.value)
        val command = AddPlayerToMatch(id, "open-join", " Dora ")

        val joined = repo.addPlayerToMatch(command)

        val after = snapshot(id)
        assertEquals(2, after.participants.last().eligibleFromRoundOrdinal)
        assertEquals("Dora", after.participants.last().displayName)
        assertEquals(original, after.participants.take(3))
        assertEquals(before.rounds.first(), after.rounds.first())
        assertFalse(joined in after.rounds.first().draft.eligibleParticipants)
        assertNull(after.rounds.first().draft.entries[joined])
        assertEquals(priorScores, db.roundDao().scores(before.rounds.first().id.value))
        assertEquals(before.totals + (joined to 0), after.totals)
        assertEquals(original.map { it.id } + joined, after.openRound!!.draft.eligibleParticipants)
        assertEquals(before.openRound!!.draft.entries, after.openRound!!.draft.entries)
        assertEquals(4, db.roundDao().get(prepared.id.value)!!.eligibleParticipantCount)

        assertEquals(joined, RoomMatchRepository(db, clock).addPlayerToMatch(command))
        assertEquals(after, snapshot(id))
        assertEquals(4, db.roundDao().get(prepared.id.value)!!.eligibleParticipantCount)
    }

    @Test fun joinAfterWinnerKeepsRoundOpenUntilNewPlayerScores() = runBlocking {
        val id = create()
        val original = snapshot(id).participants
        val prepared = repo.prepareRound(PrepareRound(id, indicator))
        repo.designateWinner(DesignateWinner(id, prepared.id, original[0].id, prepared))

        val joined = repo.addPlayerToMatch(AddPlayerToMatch(id, "winner-join", "Dora"))
        submit(prepared, original[1].id, 7)
        submit(prepared, original[2].id, 8)

        val pending = snapshot(id)
        assertEquals(RoundStatus.OPEN, pending.rounds.single().status)
        assertFalse(pending.rounds.single().draft.isComplete)
        assertEquals(original[0].id, pending.openRound!!.draft.designatedWinner)
        assertNull(pending.openRound!!.draft.entries[joined])
        assertEquals(original.map { it.id } + joined, pending.openRound!!.draft.eligibleParticipants)
        assertEquals(1, pending.participants.last().eligibleFromRoundOrdinal)
        assertEquals(4, db.roundDao().get(prepared.id.value)!!.eligibleParticipantCount)
        assertEquals(original.associate { it.id to 0 } + (joined to 0), pending.totals)

        submit(prepared, joined, 9)

        val complete = snapshot(id)
        assertNull(complete.openRound)
        assertEquals(RoundStatus.COMPLETE, complete.rounds.single().status)
        assertTrue(complete.rounds.single().draft.isComplete)
        assertEquals(original[0].id, complete.rounds.single().draft.designatedWinner)
        assertEquals(RoundEntry(0, RoundEntryOrigin.AUTOMATIC_WINNER), complete.rounds.single().draft.entries[original[0].id])
        assertEquals(mapOf(original[0].id to 0, original[1].id to 7, original[2].id to 8, joined to 9), complete.totals)
    }

    @Test fun preparedWildcardJoinUsesUpcomingOrdinalAndNormalizedDuplicateStillFails() = runBlocking {
        val id = create()
        completeFirstRound(id)
        val before = snapshot(id)
        val prepared = repo.prepareRound(PrepareRound(id, Indicator(Rank.FIVE, CardColor.BLACK)))
        assertEquals(before, snapshot(id))

        val joined = repo.addPlayerToMatch(AddPlayerToMatch(id, "prepared-join", " Ana "))

        val afterJoin = snapshot(id)
        assertEquals(2, afterJoin.participants.last().eligibleFromRoundOrdinal)
        assertEquals(before.rounds, afterJoin.rounds)
        assertFalse(joined in afterJoin.rounds.single().draft.eligibleParticipants)
        assertNull(afterJoin.rounds.single().draft.entries[joined])
        assertEquals(before.totals + (joined to 0), afterJoin.totals)
        val playersBeforeDuplicate = db.playerDao().players()
        try {
            repo.addPlayerToMatch(AddPlayerToMatch(id, "duplicate-join", "aNA"))
            fail("Expected normalized duplicate name rejection")
        } catch (_: DuplicatePlayerNameException) { }
        assertEquals(afterJoin, snapshot(id))
        assertEquals(playersBeforeDuplicate, db.playerDao().players())

        submit(prepared, joined, 4)

        val started = snapshot(id)
        assertEquals(prepared.id, started.openRound!!.id)
        assertEquals(2, started.openRound!!.ordinal)
        assertEquals(prepared.indicator, started.openRound!!.indicator)
        assertEquals(before.participants.map { it.id } + joined, started.openRound!!.draft.eligibleParticipants)
        assertEquals(RoundEntry(4, RoundEntryOrigin.EXPLICIT), started.openRound!!.draft.entries[joined])
        assertEquals(4, db.roundDao().get(prepared.id.value)!!.eligibleParticipantCount)
        assertEquals(before.totals + (joined to 0), started.totals)
    }

    @Test fun joinDuringHistoricalCorrectionIsRejectedWithoutMutation() = runBlocking {
        val id = create()
        completeFirstRound(id)
        val participants = snapshot(id).participants
        val second = repo.prepareRound(PrepareRound(id, indicator))
        submit(second, participants[0].id, 5)
        submit(second, participants[1].id, 6)
        val completed = snapshot(id)
        assertEquals(listOf(RoundStatus.COMPLETE, RoundStatus.COMPLETE), completed.rounds.map { it.status })

        repo.removeRoundEntry(RemoveRoundEntry(id, completed.rounds.first().id, participants[0].id))
        val before = snapshot(id)
        assertEquals(completed.rounds.first().id, before.openRound!!.id)
        assertTrue(before.openRound!!.isCorrection)
        assertEquals(completed.rounds.last(), before.rounds.last())
        val storedParticipants = db.matchDao().participants(id.value)
        val storedRounds = db.roundDao().rounds(id.value)
        val storedScores = storedRounds.associate { it.id to db.roundDao().scores(it.id) }
        val storedPlayers = db.playerDao().players()

        try {
            repo.addPlayerToMatch(AddPlayerToMatch(id, "correction-join", "Dora"))
            fail("Expected player addition during historical correction to be rejected")
        } catch (_: IllegalArgumentException) { }

        val after = snapshot(id)
        assertEquals(before, after)
        assertEquals(listOf(participants.map { it.id }, participants.map { it.id }), after.rounds.map { it.draft.eligibleParticipants })
        assertEquals(storedParticipants, db.matchDao().participants(id.value))
        assertEquals(storedRounds, db.roundDao().rounds(id.value))
        assertEquals(storedScores, storedRounds.associate { it.id to db.roundDao().scores(it.id) })
        assertEquals(storedPlayers, db.playerDao().players())
    }

    private suspend fun create(): MatchId = repo.createMatch(CreateMatch(
        UUID.randomUUID().toString(),
        (1..3).map { CreateMatchPlayer("draft-$it", displayName = "Player $it") },
    ))

    private suspend fun snapshot(id: MatchId) = repo.observeMatch(id).first()!!

    private suspend fun completeFirstRound(id: MatchId) {
        val participants = snapshot(id).participants
        val prepared = repo.prepareRound(PrepareRound(id, indicator))
        submit(prepared, participants[0].id, 10)
        submit(prepared, participants[1].id, 20)
        assertEquals(RoundStatus.COMPLETE, snapshot(id).rounds.single().status)
    }

    private suspend fun submit(prepared: PreparedRound, participant: ParticipantId, points: Int) {
        repo.submitScore(SubmitScore(prepared.matchId, prepared.id, participant, points, UUID.randomUUID().toString(), prepared))
    }
}
