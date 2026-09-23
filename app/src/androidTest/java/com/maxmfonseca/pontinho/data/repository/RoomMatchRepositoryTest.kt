package com.maxmfonseca.pontinho.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class RoomMatchRepositoryTest {
    private lateinit var db: PontinhoDatabase
    private lateinit var repo: RoomMatchRepository
    private lateinit var players: RoomPlayerRepository
    private val clock = TestClock(Instant.parse("2026-10-14T02:59:59Z"), ZoneId.of("America/Sao_Paulo"))
    private val indicator = Indicator(Rank.KING, CardColor.RED)

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PontinhoDatabase::class.java).build()
        repo = RoomMatchRepository(db, clock)
        players = RoomPlayerRepository(db, clock)
    }
    @After fun close() { db.close() }

    private suspend fun create(count: Int = 2): MatchId {
        return repo.createMatch(CreateMatch(
            creationToken = java.util.UUID.randomUUID().toString(),
            players = (1..count).map { index ->
                CreateMatchPlayer("draft-$index", displayName = "Player $index")
            },
        ))
    }
    private suspend fun snapshot(id: MatchId) = repo.observeMatch(id).first()!!
    private val preparations = mutableMapOf<RoundId, PreparedRound>()
    private suspend fun start(id: MatchId, chosenIndicator: Indicator = indicator): RoundId {
        val prepared = repo.chooseIndicator(ChooseIndicator(id, chosenIndicator))
        preparations[prepared.id] = prepared
        return prepared.id
    }
    private suspend fun designate(command: DesignateWinner) {
        repo.designateWinner(command.copy(preparedRound = preparations[command.roundId]))
    }
    private suspend fun submit(id: MatchId, round: RoundId, index: Int, points: Int, token: String = java.util.UUID.randomUUID().toString()) {
        repo.submitScore(SubmitScore(id, round, snapshot(id).participants[index].id, points, token, preparations[round]))
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun createIsAtomicAcrossIdentityMatchAndParticipantWrites() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_atomic_create BEFORE INSERT ON matches " +
                "BEGIN SELECT RAISE(ABORT, 'injected match failure'); END",
        )

        try {
            repo.createMatch(CreateMatch(
                creationToken = "atomic-token",
                players = listOf(
                    CreateMatchPlayer("new-a", displayName = "Same"),
                    CreateMatchPlayer("new-b", displayName = "Same"),
                ),
            ))
            fail("Expected SQLite failure")
        } catch (_: android.database.sqlite.SQLiteException) { }

        assertTrue(db.playerDao().players().isEmpty())
        assertTrue(db.matchDao().matches().isEmpty())
        assertTrue(db.matchDao().allParticipants().isEmpty())
    }

    @Test fun createTokenIsDurablyIdempotentAndRejectsConflictingPayload() = runBlocking {
        val command = CreateMatch(
            creationToken = "durable-token",
            players = listOf(
                CreateMatchPlayer("equal-a", displayName = "Same"),
                CreateMatchPlayer("equal-b", displayName = "Same"),
            ),
        )

        val first = repo.createMatch(command)
        val retry = RoomMatchRepository(db, clock).createMatch(command)

        assertEquals(first, retry)
        assertEquals(1, db.matchDao().matches().size)
        assertEquals(2, db.playerDao().players().size)
        assertEquals(2, db.matchDao().allParticipants().size)
        assertEquals(2, snapshot(first).participants.map { it.playerId }.distinct().size)
        rejected {
            repo.createMatch(command.copy(
                players = command.players.mapIndexed { index, player ->
                    if (index == 0) player.copy(displayName = "Changed") else player
                },
            ))
        }
        assertEquals(1, db.matchDao().matches().size)
        assertEquals(listOf("Same", "Same"), snapshot(first).participants.map { it.displayName })
    }

    @Test fun createReuseAndPlayerRenameKeepIdentityAndHistoricalNameSnapshots() = runBlocking {
        val a = players.upsert(UpsertPlayer(displayName = " Ana "))
        val b = players.upsert(UpsertPlayer(displayName = "Ana"))
        assertNotEquals(a, b)
        val id = repo.createMatch(CreateMatch("rename-first", listOf(
            CreateMatchPlayer("a", a, "Ana"), CreateMatchPlayer("b", b, "Ana"),
        )))
        players.upsert(UpsertPlayer(a, "Bea"))
        val saved = snapshot(id)
        assertEquals(listOf("Ana", "Ana"), saved.participants.map { it.displayName })
        assertEquals(saved.participants.first().id, saved.dealerParticipantId)
        assertTrue(saved.rounds.isEmpty())
        val reused = repo.createMatch(CreateMatch("rename-second", saved.participants.reversed().mapIndexed { index, participant ->
            CreateMatchPlayer("reused-$index", participant.playerId, participant.displayName)
        }))
        assertEquals(listOf(b, a), snapshot(reused).participants.map { it.playerId })
        assertEquals(listOf("Ana", "Ana"), snapshot(reused).participants.map { it.displayName })
        assertEquals(2, players.observePlayers().first().size)
        rejected { repo.createMatch(CreateMatch("duplicate", listOf(
            CreateMatchPlayer("a", a, "Ana"), CreateMatchPlayer("b", a, "Ana"),
        ))) }
        rejected { players.upsert(UpsertPlayer(displayName = "  ")) }
        assertEquals(2, db.matchDao().matches().size)
    }

    @Test fun manualWinnerWaitsForOtherScoresAndExplicitZeroStaysExplicit() = runBlocking {
        val id = create(3); val round = start(id); val roster = snapshot(id).participants
        designate(DesignateWinner(id, round, roster[2].id))
        assertTrue(snapshot(id).rounds.single().draft.entries.isEmpty())
        submit(id, round, 0, 0)
        var state = snapshot(id)
        assertFalse(state.rounds.single().draft.isComplete)
        assertEquals(listOf(0, 0, 0), state.participants.map { state.totals[it.id] })
        submit(id, round, 1, 12)
        state = snapshot(id)
        assertTrue(state.rounds.single().draft.isComplete)
        assertEquals(RoundEntryOrigin.EXPLICIT, state.rounds.single().draft.entries[roster[0].id]!!.origin)
        assertEquals(RoundEntryOrigin.AUTOMATIC_WINNER, state.rounds.single().draft.entries[roster[2].id]!!.origin)
        assertEquals(roster[1].id, state.dealerParticipantId)
        assertEquals(listOf(0, 12, 0), roster.map { state.totals[it.id] })
    }

    @Test fun thresholdIsStrictAndResultsSurviveRepositoryRecreation() = runBlocking {
        val id = create(); submit(id, start(id), 0, 100)
        assertFalse(snapshot(id).isFinished)
        submit(id, start(id), 0, 1)
        repo = RoomMatchRepository(db, clock)
        val state = snapshot(id)
        assertTrue(state.isFinished)
        assertEquals(101, state.totals[state.participants[0].id])
        assertEquals(setOf(state.participants[1].id), state.outcome!!.winners)
        assertTrue(state.hasPendingResults)
        assertEquals(1L, state.resultRevision)
        rejected { start(id) }
    }

    @Test fun duplicateTokenIsIdempotentEvenConcurrentlyAndAfterCorrectionDeletion() = runBlocking {
        val id = create(); val round = start(id); val p = snapshot(id).participants[0].id
        val command = SubmitScore(id, round, p, 101, "same-token", preparations[round])
        coroutineScope { List(6) { async(Dispatchers.Default) { repo.submitScore(command) } }.awaitAll() }
        var state = snapshot(id)
        assertEquals(2, state.rounds.single().draft.entries.size)
        assertEquals(1L, state.resultRevision)
        assertEquals(state.participants[1].id, state.dealerParticipantId)
        rejected { repo.submitScore(command.copy(points = 99)) }
        repo.removeRoundEntry(RemoveRoundEntry(id, round, p))
        repo.submitScore(command)
        state = snapshot(id)
        assertFalse(state.rounds.single().draft.isComplete)
        assertTrue(state.rounds.single().draft.entries.isEmpty())
    }

    @Test fun lateJoinHasNoFakeScoresAndOrderChangesFutureDealerRotation() = runBlocking {
        val id = create(); val r1 = start(id); submit(id, r1, 0, 10)
        val c = players.upsert(UpsertPlayer(displayName = "Late"))
        repo.addParticipant(AddParticipant(id, c, 1))
        var state = snapshot(id)
        val late = state.participants[1]
        assertEquals(2, late.eligibleFromRoundOrdinal)
        assertEquals(0, state.totals[late.id])
        assertEquals(2, state.rounds[0].draft.entries.size)
        assertFalse(late.id in state.rounds[0].draft.eligibleParticipants)
        repo.reorderParticipants(ReorderParticipants(id, state.participants.map { it.id }.reversed()))
        val r2 = start(id)
        submit(id, r2, 0, 3)
        state = snapshot(id)
        rejected { repo.addParticipant(AddParticipant(id, players.upsert(UpsertPlayer(displayName = "No")), 0)) }
        rejected { repo.reorderParticipants(ReorderParticipants(id, state.participants.map { it.id }.reversed())) }
        rejected { repo.submitScore(SubmitScore(id, r1, late.id, 9, "ineligible")) }
        assertFalse(snapshot(id).rounds[1].draft.isComplete)
        submit(id, r2, 1, 4)
        assertEquals(late.id, snapshot(id).dealerParticipantId)
        assertEquals(state.participants.last().id, snapshot(id).rounds[0].dealerParticipantId)
    }

    @Test fun deletingAutomaticZeroReopensWithoutReinferenceAndClearsResults() = runBlocking {
        val id = create(); val round = start(id); submit(id, round, 0, 101)
        val old = snapshot(id)
        repo.acknowledgeResults(id, old.resultRevision)
        repo.removeRoundEntry(RemoveRoundEntry(id, round, old.participants[1].id))
        var state = snapshot(id)
        assertFalse(state.isFinished)
        assertFalse(state.hasPendingResults)
        assertNull(state.outcome)
        assertNull(state.rounds.single().draft.winner)
        assertFalse(state.rounds.single().draft.isComplete)
        assertEquals(1, state.rounds.single().draft.entries.size)
        assertEquals(listOf(0, 0), state.participants.map { state.totals[it.id] })
        assertEquals(old.participants[0].id, state.dealerParticipantId)
        assertEquals(0L, state.acknowledgedResultRevision)
        assertEquals(1, db.matchDao().corrections(id.value).size)
        designate(DesignateWinner(id, round, old.participants[1].id))
        state = snapshot(id)
        assertTrue(state.isFinished)
        assertTrue(state.hasPendingResults)
        assertTrue(state.resultRevision > old.resultRevision)
    }

    @Test fun explicitCorrectionRecomputesCompletedTotalsAndCanReopenMatch() = runBlocking {
        val id = create(); val round = start(id); submit(id, round, 0, 101)
        val p = snapshot(id).participants[0].id
        repo.updateExplicitScore(UpdateExplicitScore(id, round, p, 100))
        val state = snapshot(id)
        assertTrue(state.rounds.single().draft.isComplete)
        assertFalse(state.isFinished)
        assertEquals(100, state.totals[p])
        assertEquals(state.participants[1].id, state.dealerParticipantId)
        assertEquals(1, db.matchDao().corrections(id.value).size)
        rejected { repo.updateExplicitScore(UpdateExplicitScore(id, round, state.participants[1].id, 5)) }
    }

    @Test fun databaseFailureAfterScoreWritesRollsBackScoresCompletionTotalsDealerAndReceipt() = runBlocking {
        val id = create(); val round = start(id); val before = snapshot(id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_match BEFORE UPDATE ON matches BEGIN SELECT RAISE(ABORT, 'injected interruption'); END")
        try { submit(id, round, 0, 101, "rollback"); fail("Expected SQLite failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, snapshot(id))
        assertNull(db.roundDao().submission("rollback"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_match")
        submit(id, round, 0, 101, "rollback")
        assertTrue(snapshot(id).isFinished)
    }

    @Test fun failedCorrectionRollsBackAuditAndAllDerivedState() = runBlocking {
        val id = create(); val round = start(id); submit(id, round, 0, 101)
        val before = snapshot(id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_audit BEFORE INSERT ON corrections BEGIN SELECT RAISE(ABORT, 'injected audit failure'); END")
        try { repo.removeRoundEntry(RemoveRoundEntry(id, round, before.participants[0].id)); fail("Expected failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, snapshot(id))
        assertTrue(db.matchDao().corrections(id.value).isEmpty())
    }

    @Test fun overflowCorrectionRollsBackInsteadOfWrappingTotals() = runBlocking {
        val id = create(); val r1 = start(id); submit(id, r1, 0, 100)
        val r2 = start(id); submit(id, r2, 0, 1)
        val before = snapshot(id)
        rejected { repo.updateExplicitScore(UpdateExplicitScore(id, r2, before.participants[0].id, Int.MAX_VALUE)) }
        assertEquals(before, snapshot(id))
        assertTrue(db.matchDao().corrections(id.value).isEmpty())
    }

    @Test fun creationDateAndZoneSurviveMidnightAndTimeZoneChanges() = runBlocking {
        val id = create()
        clock.time = Instant.parse("2026-10-14T03:00:01Z")
        clock.zoneId = ZoneId.of("Asia/Tokyo")
        val newer = create()
        submit(id, start(id), 0, 1)
        val state = snapshot(id)
        assertEquals(LocalDate.parse("2026-10-13"), state.localDate)
        assertEquals("America/Sao_Paulo", state.timeZoneId)
        assertEquals(Instant.parse("2026-10-14T02:59:59Z"), state.createdAt)
        val home = repo.observeHome().first()
        assertEquals(listOf("2026-10-14", "2026-10-13"), home.map { it.localDate.toString() })
        assertEquals(newer, home[0].matches.single().id)
        assertEquals(id, home[1].matches.single().id)
    }

    @Test fun historicalReopenUsesOriginalEligibilityAndPausesAnotherOpenRound() = runBlocking {
        val id = create(); val r1 = start(id); submit(id, r1, 0, 1)
        val late = players.upsert(UpsertPlayer(displayName = "Late"))
        repo.addParticipant(AddParticipant(id, late, 2))
        val r2 = start(id); submit(id, r2, 0, 1); val state = snapshot(id)
        repo.removeRoundEntry(RemoveRoundEntry(id, r1, state.participants[1].id))
        assertEquals(listOf(r2), snapshot(id).pausedRounds.map { it.id })
        assertEquals(2, snapshot(id).rounds[0].draft.eligibleParticipants.size)
        designate(DesignateWinner(id, r1, state.participants[1].id))
        assertEquals(2, snapshot(id).rounds[0].draft.entries.size)
        assertEquals(r2, snapshot(id).openRound!!.id)
        submit(id, r2, 1, 1)
        assertEquals(state.participants[2].id, snapshot(id).dealerParticipantId)
    }

    @Test fun editingPendingScoreAfterWinnerDeletionCanCompleteAgain() = runBlocking {
        val id = create(); val round = start(id); submit(id, round, 0, 101)
        val roster = snapshot(id).participants
        repo.removeRoundEntry(RemoveRoundEntry(id, round, roster[1].id))
        repo.updateExplicitScore(UpdateExplicitScore(id, round, roster[0].id, 99))
        val state = snapshot(id)
        assertTrue(state.rounds.single().draft.isComplete)
        assertFalse(state.isFinished)
        assertEquals(99, state.totals[roster[0].id])
        assertEquals(RoundEntryOrigin.AUTOMATIC_WINNER, state.rounds.single().draft.entries[roster[1].id]!!.origin)
        assertEquals(roster[1].id, state.dealerParticipantId)
    }

    @Test fun productionClockReadsCurrentDeviceZoneForEachCreation() = runBlocking {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Pago_Pago"))
            repo = RoomMatchRepository(db)
            val first = create()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"))
            val second = create()
            assertEquals("Pacific/Pago_Pago", snapshot(first).timeZoneId)
            val latest = snapshot(second)
            assertEquals("Pacific/Kiritimati", latest.timeZoneId)
            assertEquals(latest.createdAt.atZone(ZoneId.of("Pacific/Kiritimati")).toLocalDate(), latest.localDate)
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test fun addingAndReorderingParticipantsRollBackAsAWholeOnDatabaseFailure() = runBlocking {
        val id = create(3)
        val late = players.upsert(UpsertPlayer(displayName = "Late"))
        val before = snapshot(id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_roster BEFORE UPDATE ON matches BEGIN SELECT RAISE(ABORT, 'injected roster failure'); END")
        try { repo.addParticipant(AddParticipant(id, late, 0)); fail("Expected failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, snapshot(id))
        try { repo.reorderParticipants(ReorderParticipants(id, before.participants.map { it.id }.reversed())); fail("Expected failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, snapshot(id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_roster")
        repo.addParticipant(AddParticipant(id, late, 0))
        assertEquals(listOf(0, 1, 2, 3), snapshot(id).participants.map { it.rosterOrder })
        rejected { repo.addParticipant(AddParticipant(id, late, 0)) }
    }

    @Test fun deletingExplicitScoreFromManualCompletionRetainsDesignationButRemovesAutomaticZero() = runBlocking {
        val id = create(3); val round = start(id); val roster = snapshot(id).participants
        designate(DesignateWinner(id, round, roster[2].id))
        submit(id, round, 0, 20); submit(id, round, 1, 10)
        repo.removeRoundEntry(RemoveRoundEntry(id, round, roster[0].id))
        val state = snapshot(id)
        assertFalse(state.rounds.single().draft.isComplete)
        assertEquals(roster[2].id, state.rounds.single().draft.designatedWinner)
        assertEquals(setOf(roster[1].id), state.rounds.single().draft.entries.keys)
        assertEquals(listOf(0, 0, 0), roster.map { state.totals[it.id] })
        assertEquals(roster[0].id, state.dealerParticipantId)
    }

    @Test fun fileDatabaseReopenPreservesOpenRoundAndPendingFinalResults() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "task3-restart-${java.util.UUID.randomUUID()}.db"
        db.close()
        fun reopen() {
            db = Room.databaseBuilder(context, PontinhoDatabase::class.java, name).build()
            repo = RoomMatchRepository(db, clock)
            players = RoomPlayerRepository(db, clock)
        }
        try {
            reopen()
            val id = create(3); val round = start(id); val roster = snapshot(id).participants
            designate(DesignateWinner(id, round, roster[2].id))
            submit(id, round, 0, 0)
            val openState = snapshot(id)
            db.close(); reopen()
            assertEquals(openState, snapshot(id))
            submit(id, round, 1, 20)
            val later = start(id, Indicator(Rank.FIVE, CardColor.BLACK))
            designate(DesignateWinner(id, later, roster[0].id))
            submit(id, later, 1, 7)
            val laterBefore = snapshot(id).rounds[1]
            repo.removeRoundEntry(RemoveRoundEntry(id, round, roster[2].id))
            val pausedState = snapshot(id)
            assertTrue(pausedState.openRound!!.isCorrection)
            assertEquals(listOf(later), pausedState.pausedRounds.map { it.id })
            db.close(); reopen()
            assertEquals(pausedState, snapshot(id))
            designate(DesignateWinner(id, round, roster[2].id))
            assertEquals(laterBefore, snapshot(id).openRound)
            submit(id, later, 2, 101)
            val finished = snapshot(id)
            db.close(); reopen()
            assertEquals(finished, snapshot(id))
            assertTrue(snapshot(id).hasPendingResults)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun indicatorPreparationCanBeAbandonedWithoutPersistingARound() = runBlocking {
        val id = create(); val before = snapshot(id)
        start(id)
        start(id, Indicator(Rank.TWO, CardColor.BLACK))
        assertEquals(before, snapshot(id))
        assertTrue(db.roundDao().rounds(id.value).isEmpty())
    }

    @Test fun firstScoreCreatesAndCompletesRoundInOneTransaction() = runBlocking {
        val id = create(); val before = snapshot(id); val round = start(id)
        assertEquals(before, snapshot(id))
        submit(id, round, 0, 101, "first-score")
        val state = snapshot(id)
        assertTrue(state.isFinished)
        assertEquals(2, state.rounds.single().draft.entries.size)
        assertEquals(1L, state.resultRevision)
        assertEquals(state.participants[1].id, state.dealerParticipantId)
    }

    @Test fun firstDesignationFailureLeavesNoEmptyRoundAndRetryCommitsDesignation() = runBlocking {
        val id = create(3); val before = snapshot(id); val round = start(id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_first BEFORE UPDATE ON matches BEGIN SELECT RAISE(ABORT, 'first designation failure'); END")
        try { designate(DesignateWinner(id, round, before.participants[2].id)); fail("Expected failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, snapshot(id))
        assertTrue(db.roundDao().rounds(id.value).isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_first")
        designate(DesignateWinner(id, round, before.participants[2].id))
        val state = snapshot(id)
        assertEquals(before.participants[2].id, state.rounds.single().draft.designatedWinner)
        assertTrue(state.rounds.single().draft.entries.isEmpty())
        assertFalse(state.rounds.single().draft.isComplete)
    }

    @Test fun historicalDeletionPausesLaterDraftAndResumesWithoutInputLoss() = runBlocking {
        val id = create(3); val first = start(id)
        submit(id, first, 0, 20); submit(id, first, 1, 10)
        val roster = snapshot(id).participants
        val later = start(id, Indicator(Rank.FIVE, CardColor.BLACK))
        designate(DesignateWinner(id, later, roster[0].id))
        submit(id, later, 1, 7)
        val laterBefore = snapshot(id).rounds[1]
        repo.removeRoundEntry(RemoveRoundEntry(id, first, roster[2].id))
        var state = snapshot(id)
        assertEquals("PAUSED", db.roundDao().get(later.value)!!.status)
        assertEquals(listOf(later), state.pausedRounds.map { it.id })
        assertTrue(state.openRound!!.isCorrection)
        assertFalse(state.pausedRounds.single().isCorrection)
        assertEquals(listOf(first.value), db.roundDao().rounds(id.value).filter { it.status == "OPEN" }.map { it.id })
        assertEquals(laterBefore.draft, state.rounds[1].draft)
        assertEquals(laterBefore.indicator, state.rounds[1].indicator)
        assertEquals(laterBefore.dealerParticipantId, state.rounds[1].dealerParticipantId)
        assertFalse(state.rounds[0].draft.isComplete)
        assertNull(state.rounds[0].draft.winner)
        assertFalse(state.isFinished)
        rejected { submit(id, later, 2, 8) }
        designate(DesignateWinner(id, first, roster[2].id))
        state = snapshot(id)
        assertEquals(laterBefore, state.rounds[1])
        assertEquals(later, state.openRound!!.id)
        assertEquals(laterBefore.dealerParticipantId, state.dealerParticipantId)
        assertEquals(listOf(later.value), db.roundDao().rounds(id.value).filter { it.status == "OPEN" }.map { it.id })
    }

    @Test fun completedHistoryEditCrossingThresholdPausesDraftAndReversalResumes() = runBlocking {
        val id = create(3); val first = start(id)
        submit(id, first, 0, 90); submit(id, first, 1, 2)
        val roster = snapshot(id).participants
        val later = start(id)
        designate(DesignateWinner(id, later, roster[0].id))
        submit(id, later, 1, 7)
        val laterBefore = snapshot(id).rounds[1]
        repo.updateExplicitScore(UpdateExplicitScore(id, first, roster[0].id, 101))
        var state = snapshot(id)
        assertTrue(state.isFinished)
        assertEquals(101, state.totals[roster[0].id])
        assertEquals(setOf(roster[2].id), state.outcome!!.winners)
        assertTrue(state.hasPendingResults)
        assertNull(state.openRound)
        assertEquals("PAUSED", db.roundDao().get(later.value)!!.status)
        assertEquals(laterBefore.draft, state.rounds[1].draft)
        repo.acknowledgeResults(id, state.resultRevision)
        repo.updateExplicitScore(UpdateExplicitScore(id, first, roster[0].id, 90))
        state = snapshot(id)
        assertFalse(state.isFinished)
        assertNull(state.outcome)
        assertEquals(0L, state.acknowledgedResultRevision)
        assertEquals(laterBefore, state.rounds[1])
        assertEquals(later, state.openRound!!.id)
        assertEquals(laterBefore.dealerParticipantId, state.dealerParticipantId)
    }

    @Test fun completingHistoricalCorrectionAboveThresholdKeepsLaterPausedUntilReversal() = runBlocking {
        val id = create(3); val first = start(id)
        submit(id, first, 0, 90); submit(id, first, 1, 2)
        val roster = snapshot(id).participants; val later = start(id)
        designate(DesignateWinner(id, later, roster[0].id)); submit(id, later, 1, 7)
        val laterBefore = snapshot(id).rounds[1]
        repo.removeRoundEntry(RemoveRoundEntry(id, first, roster[2].id))
        assertFalse(snapshot(id).rounds[0].draft.isComplete)
        repo.updateExplicitScore(UpdateExplicitScore(id, first, roster[0].id, 101))
        var state = snapshot(id)
        assertTrue(state.isFinished)
        assertNull(state.openRound)
        assertEquals("PAUSED", db.roundDao().get(later.value)!!.status)
        assertEquals(laterBefore.draft, state.rounds[1].draft)
        repo.updateExplicitScore(UpdateExplicitScore(id, first, roster[0].id, 90))
        state = snapshot(id)
        assertFalse(state.isFinished)
        assertEquals(laterBefore, state.rounds[1])
        assertEquals(later, state.openRound!!.id)
    }

    @Test fun failureWhilePausingAndReopeningRollsBackAllRoundsAndAudit() = runBlocking {
        val id = create(3); val first = start(id)
        submit(id, first, 0, 20); submit(id, first, 1, 10)
        val later = start(id); submit(id, later, 0, 9)
        val before = snapshot(id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_pause_audit BEFORE INSERT ON corrections BEGIN SELECT RAISE(ABORT, 'pause audit failure'); END")
        try { repo.removeRoundEntry(RemoveRoundEntry(id, first, before.participants[2].id)); fail("Expected failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, snapshot(id))
        assertTrue(db.matchDao().corrections(id.value).isEmpty())
        assertEquals("OPEN", db.roundDao().get(later.value)!!.status)
    }

    @Test fun incompleteCorrectionStaysActiveDespiteOtherCompletedTotalsAboveThreshold() = runBlocking {
        val id = create(3); val first = start(id)
        submit(id, first, 0, 40); submit(id, first, 1, 1)
        val second = start(id); submit(id, second, 0, 50); submit(id, second, 1, 1)
        val later = start(id); submit(id, later, 0, 3)
        val roster = snapshot(id).participants
        repo.removeRoundEntry(RemoveRoundEntry(id, first, roster[2].id))
        repo.updateExplicitScore(UpdateExplicitScore(id, second, roster[0].id, 101))
        var state = snapshot(id)
        assertEquals(101, state.totals[roster[0].id])
        assertFalse(state.isFinished)
        assertNull(state.outcome)
        assertEquals(first, state.openRound!!.id)
        assertEquals("PAUSED", db.roundDao().get(later.value)!!.status)
        designate(DesignateWinner(id, first, roster[2].id))
        state = snapshot(id)
        assertTrue(state.isFinished)
        assertEquals(141, state.totals[roster[0].id])
        assertEquals("PAUSED", db.roundDao().get(later.value)!!.status)
    }

    @Test fun nestedHistoricalCorrectionsResumeInOrderBeforeTheUnfinishedDraft() = runBlocking {
        val id = create(3); val first = start(id)
        submit(id, first, 0, 10); submit(id, first, 1, 1)
        val second = start(id); submit(id, second, 0, 10); submit(id, second, 1, 1)
        val later = start(id); submit(id, later, 0, 7)
        val before = snapshot(id); val winner = before.participants[2].id
        repo.removeRoundEntry(RemoveRoundEntry(id, second, winner))
        repo.removeRoundEntry(RemoveRoundEntry(id, first, winner))
        assertEquals(first, snapshot(id).openRound!!.id)
        assertEquals(listOf(second, later), snapshot(id).pausedRounds.map { it.id })
        designate(DesignateWinner(id, first, winner))
        var state = snapshot(id)
        assertEquals(second, state.openRound!!.id)
        assertTrue(state.openRound!!.isCorrection)
        assertEquals(listOf(later), state.pausedRounds.map { it.id })
        designate(DesignateWinner(id, second, winner))
        state = snapshot(id)
        assertEquals(before.rounds[2], state.openRound)
        assertTrue(state.pausedRounds.isEmpty())
        assertFalse(state.rounds.any { it.isCorrection })
        assertEquals(1, db.roundDao().rounds(id.value).count { it.status == "OPEN" })
    }

    @Test fun stalePreparationAndInvalidFirstSubmissionLeaveNoPartialRound() = runBlocking {
        val id = create(); val first = start(id); val stale = start(id)
        val before = snapshot(id)
        rejected { submit(id, first, 0, -1, "invalid-first") }
        assertEquals(before, snapshot(id))
        assertNull(db.roundDao().submission("invalid-first"))
        submit(id, first, 0, 10, "valid-first")
        val completed = snapshot(id)
        rejected { submit(id, stale, 0, 20, "stale-first") }
        assertEquals(completed, snapshot(id))
        assertNull(db.roundDao().submission("stale-first"))
        val prepared = repo.chooseIndicator(ChooseIndicator(id, indicator))
        assertEquals(2, prepared.ordinal)
        assertEquals(1, snapshot(id).rounds.size)
    }

    private class TestClock(var time: Instant, var zoneId: ZoneId) : Clock() {
        override fun instant() = time
        override fun getZone() = zoneId
        override fun withZone(zone: ZoneId): Clock = TestClock(time, zone)
    }
}
