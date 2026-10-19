package com.maxmfonseca.pontinho.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.local.entity.*
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.domain.statistics.LifetimePlayerStatistics
import com.maxmfonseca.pontinho.domain.recognition.ConfirmedCard
import com.maxmfonseca.pontinho.domain.recognition.NormalizedRect
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
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
        val prepared = repo.prepareRound(PrepareRound(id, chosenIndicator))
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

    private fun card(
        rank: Rank,
        suit: Suit,
        confidence: Float? = .75f,
        corrected: Boolean = false,
        bounds: NormalizedRect? = NormalizedRect(.1f, .2f, .3f, .4f),
    ) = ConfirmedCard(Card.Suited(rank, suit), confidence, corrected, bounds)

    @Test fun manualScoreCreatesRoundWithoutIndicator() = runBlocking {
        val id = create()
        val prepared = repo.prepareRound(PrepareRound(id))
        assertTrue(snapshot(id).rounds.isEmpty())
        repo.submitScore(SubmitScore(id, prepared.id, snapshot(id).participants[0].id, 17, "manual-no-joker", prepared))
        val state = snapshot(id)
        assertNull(state.rounds.single().indicator)
        assertEquals(17, state.totals[state.participants[0].id])
        assertNull(db.roundDao().get(prepared.id.value)!!.indicatorRank)
        assertNull(db.roundDao().get(prepared.id.value)!!.indicatorColor)
    }

    @Test fun scannedScoreRejectsRoundWithoutIndicator() = runBlocking {
        val id = create(3)
        val prepared = repo.prepareRound(PrepareRound(id))
        preparations[prepared.id] = prepared
        val before = snapshot(id)
        rejected { scan(id, prepared.id, 0, listOf(card(Rank.TEN, Suit.CLUBS)), "no-joker") }
        assertEquals(before, snapshot(id))
        assertNull(db.roundDao().submission("no-joker"))
        submit(id, prepared.id, 0, 5)
        val persisted = snapshot(id)
        rejected { scan(id, prepared.id, 1, listOf(card(Rank.TEN, Suit.CLUBS)), "persisted-no-joker") }
        rejected { repo.updateScannedScore(UpdateScannedScore(id, prepared.id, persisted.participants[0].id, listOf(card(Rank.TEN, Suit.CLUBS)), "cards-v1")) }
        assertEquals(persisted, snapshot(id))
        assertTrue(db.roundDao().allCardObservations().isEmpty())
    }

    @Test fun updatingCompletedAndFinishedRoundIndicatorChangesNoScoreOrOutcomeState() = runBlocking {
        for (points in listOf(17, 101)) {
            val id = create()
            val round = start(id)
            val cards = if (points == 17) listOf(card(Rank.TEN, Suit.CLUBS), card(Rank.SEVEN, Suit.HEARTS))
                else List(9) { card(Rank.TEN, Suit.CLUBS) } + listOf(card(Rank.NINE, Suit.HEARTS), card(Rank.TWO, Suit.HEARTS))
            scan(id, round, 0, cards, "metadata-$points")
            val before = snapshot(id)
            assertEquals(points, before.totals[before.participants[0].id])
            val matchRow = db.matchDao().get(id.value)
            val scoreRows = db.roundDao().scores(round.value)
            val roundRow = db.roundDao().get(round.value)!!
            val observations = db.roundDao().allCardObservations()
            val corrections = db.matchDao().corrections(id.value)
            val receipt = db.roundDao().submission("metadata-$points")
            for (replacement in listOf(Indicator(Rank.FIVE, CardColor.BLACK), null)) {
                repo.updateRoundIndicator(UpdateRoundIndicator(id, round, replacement))
                val after = snapshot(id)
                assertEquals(before.copy(rounds = before.rounds.map { it.copy(indicator = replacement) }), after)
                assertEquals(matchRow, db.matchDao().get(id.value))
                assertEquals(scoreRows, db.roundDao().scores(round.value))
                assertEquals(observations, db.roundDao().allCardObservations())
                assertEquals(corrections, db.matchDao().corrections(id.value))
                assertEquals(receipt, db.roundDao().submission("metadata-$points"))
                assertEquals(roundRow.copy(indicatorRank = replacement?.rank?.name, indicatorColor = replacement?.color?.name), db.roundDao().get(round.value))
            }
        }
    }

    private suspend fun scan(
        matchId: MatchId,
        roundId: RoundId,
        participantIndex: Int,
        cards: List<ConfirmedCard>,
        token: String,
        modelVersion: String = "cards-v1",
    ) = repo.submitScannedScore(
        SubmitScannedScore(
            matchId,
            roundId,
            snapshot(matchId).participants[participantIndex].id,
            cards,
            token,
            modelVersion,
            preparations[roundId],
        ),
    )

    @Test fun scannedScoreIsRecomputedWithJokerAndDuplicateCardsAndPersistsPreparedRoundInOrder() = runBlocking {
        val id = create(3)
        val round = start(id, Indicator(Rank.KING, CardColor.RED))
        scan(id, round, 0, listOf(
            card(Rank.ACE, Suit.SPADES),
            card(Rank.ACE, Suit.SPADES, confidence = null, corrected = true, bounds = null),
            card(Rank.ACE, Suit.HEARTS),
        ), "scan-first")

        val score = db.roundDao().scores(round.value).single()
        assertEquals(50, score.points)
        assertEquals("SCAN", score.origin)
        assertEquals("cards-v1", score.recognitionModelVersion)
        assertEquals(listOf(0, 1, 2), db.roundDao().cardObservations(score.id).map { it.ordinal })
        assertEquals(listOf("SPADES", "SPADES", "HEARTS"), db.roundDao().cardObservations(score.id).map { it.suit })
        assertEquals(1, snapshot(id).rounds.size)
        assertEquals(50, snapshot(id).openRound!!.draft.entries.getValue(snapshot(id).participants[0].id).points)
        assertEquals(listOf("SUBMIT_SCANNED_SCORE"), db.matchDao().corrections(id.value).map { it.operation })
    }

    @Test fun scannedScoreCompletesExistingRoundAndReplayRequiresExactOrderedPayloadAndModel() = runBlocking {
        val id = create(3)
        val round = start(id, Indicator(Rank.FIVE, CardColor.BLACK))
        submit(id, round, 0, 8)
        val cards = listOf(card(Rank.SIX, Suit.CLUBS), card(Rank.TWO, Suit.HEARTS))
        scan(id, round, 1, cards, "scan-replay")
        val complete = snapshot(id)
        assertTrue(complete.rounds.single().draft.isComplete)
        assertEquals(22, complete.totals[complete.participants[1].id])

        repo = RoomMatchRepository(db, clock)
        scan(id, round, 1, cards, "scan-replay")
        assertEquals(3, db.roundDao().scores(round.value).size)
        rejected { scan(id, round, 1, cards.reversed(), "scan-replay") }
        rejected { scan(id, round, 1, cards, "scan-replay", modelVersion = "cards-v2") }
        val participant = complete.participants[1].id
        val original = SubmitScannedScore(id, round, participant, cards, "scan-replay", "cards-v1", preparations[round])
        rejected { repo.submitScannedScore(original.copy(cards = listOf(cards[0].copy(recognitionConfidence = null), cards[1]))) }
        rejected { repo.submitScannedScore(original.copy(cards = listOf(cards[0].copy(correctedByUser = true), cards[1]))) }
        rejected { repo.submitScannedScore(original.copy(cards = listOf(cards[0].copy(sourceBounds = null), cards[1]))) }
        rejected { repo.submitScannedScore(original.copy(preparedRound = null)) }
        assertEquals(3, db.roundDao().scores(round.value).size)
    }

    @Test fun deletingScannedEntryKeepsReceiptAndManualReplacementClearsScanMetadata() = runBlocking {
        val id = create(3)
        val round = start(id)
        scan(id, round, 0, listOf(card(Rank.TEN, Suit.CLUBS)), "durable-scan")
        submit(id, round, 1, 4)
        val participant = snapshot(id).participants[0].id
        val scoreId = db.roundDao().scores(round.value).single { it.participantId == participant.value }.id

        repo.updateExplicitScore(UpdateExplicitScore(id, round, participant, 7))

        val manual = db.roundDao().scores(round.value).single { it.id == scoreId }
        assertEquals("MANUAL", manual.origin)
        assertNull(manual.recognitionModelVersion)
        assertTrue(db.roundDao().cardObservations(scoreId).isEmpty())
        repo.removeRoundEntry(RemoveRoundEntry(id, round, participant))
        assertNotNull(db.roundDao().submission("durable-scan"))
        rejected { scan(id, round, 0, listOf(card(Rank.NINE, Suit.CLUBS)), "durable-scan") }
    }

    @Test fun scannedCorrectionReplacesObservationsRecomputesAndAuditsAtomically() = runBlocking {
        val id = create(3)
        val round = start(id)
        scan(id, round, 0, listOf(card(Rank.TEN, Suit.CLUBS), card(Rank.FIVE, Suit.HEARTS)), "to-correct")
        submit(id, round, 1, 3)
        val participant = snapshot(id).participants[0].id

        repo.updateScannedScore(UpdateScannedScore(
            id, round, participant,
            listOf(card(Rank.QUEEN, Suit.DIAMONDS, corrected = true)),
            "cards-v2",
        ))

        val score = db.roundDao().scores(round.value).single { it.participantId == participant.value }
        assertEquals(10, score.points)
        assertEquals("SCAN", score.origin)
        assertEquals("cards-v2", score.recognitionModelVersion)
        assertEquals(listOf("QUEEN"), db.roundDao().cardObservations(score.id).map { it.rank })
        assertEquals(
            setOf("SUBMIT_SCANNED_SCORE", "UPDATE_SCANNED_SCORE"),
            db.matchDao().corrections(id.value).map { it.operation }.toSet(),
        )
    }

    @Test fun scanFailureRollsBackPreparedRoundScoreObservationsTotalsDealerAndReceipt() = runBlocking {
        val id = create(3)
        val round = start(id)
        val before = snapshot(id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_scan_receipt BEFORE INSERT ON submissions BEGIN SELECT RAISE(ABORT, 'injected scan interruption'); END")
        try {
            scan(id, round, 0, listOf(card(Rank.ACE, Suit.CLUBS)), "scan-rollback")
            fail("Expected SQLite failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, snapshot(id))
        assertNull(db.roundDao().get(round.value))
        assertNull(db.roundDao().submission("scan-rollback"))
        assertTrue(db.roundDao().allCardObservations().isEmpty())
    }

    @Test fun scanCancellationDuringObservationWriteRollsBackEveryTransactionalWrite() = runBlocking {
        val inFlight = java.util.concurrent.atomic.AtomicReference<Job?>()
        val intercepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancellableDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PontinhoDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ ->
                if (sql.startsWith("INSERT", ignoreCase = true) && sql.contains("`card_observations`")) {
                    inFlight.get()?.let { job ->
                        intercepted.set(true)
                        job.cancel(CancellationException("Cancel while persisting scan observations"))
                    }
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        try {
            val cancellableRepo = RoomMatchRepository(cancellableDb, clock)
            val matchId = cancellableRepo.createMatch(CreateMatch("scan-cancel-match", listOf(
                CreateMatchPlayer("a", displayName = "Ana"), CreateMatchPlayer("b", displayName = "Bruno"),
            )))
            val prepared = cancellableRepo.prepareRound(PrepareRound(matchId, indicator))
            val before = cancellableRepo.observeMatch(matchId).first()!!
            val command = SubmitScannedScore(
                matchId, prepared.id, before.participants[0].id,
                listOf(card(Rank.THREE, Suit.CLUBS)), "scan-cancel", "cards-v1", prepared,
            )
            val operation = async(start = CoroutineStart.LAZY) { cancellableRepo.submitScannedScore(command) }
            inFlight.set(operation)
            operation.start()
            try { operation.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
            inFlight.set(null)
            assertTrue("Cancellation must intercept the observation INSERT", intercepted.get())
            assertEquals(before, cancellableRepo.observeMatch(matchId).first())
            assertNull(cancellableDb.roundDao().get(prepared.id.value))
            assertNull(cancellableDb.roundDao().submission("scan-cancel"))
            assertTrue(cancellableDb.roundDao().allCardObservations().isEmpty())
        } finally { cancellableDb.close() }
    }

    @Test fun historicalNamesSplitSharedIdsAndMergeDifferentIdsWithoutChangingSnapshots() = runBlocking {
        db.withTransaction {
            db.playerDao().upsert(PlayerEntity("shared", "Julia2", "julia2", 1, 2))
            db.playerDao().upsert(PlayerEntity("max-old", "Max", "max", 1, 1))
            db.playerDao().upsert(PlayerEntity("max-new", "MAX", "max", 2, 2))
            seedFinished("old", 1, listOf(Triple("shared", "Julia", 33), Triple("max-old", "Max", 110)))
            seedFinished("new", 2, listOf(Triple("shared", "Julia2", 110), Triple("max-new", "MAX", 0)))
        }
        val before = db.matchDao().allParticipants()
        val day = repo.observeDay(LocalDate.parse("2026-10-13")).first()
        assertEquals(mapOf("Julia" to 1, "MAX" to 1, "Julia2" to 0), day.wins.associate { it.displayName to it.value })
        assertEquals(mapOf("Julia" to 33, "MAX" to 110, "Julia2" to 110), day.penalties.associate { it.displayName to it.value })
        assertEquals(listOf("Julia", "MAX", "Julia2"), day.wins.map { it.displayName })
        assertEquals(listOf("Julia2", "MAX", "Julia"), day.penalties.map { it.displayName })
        assertEquals(before, db.matchDao().allParticipants())
    }

    private suspend fun seedFinished(id: String, createdAt: Long, roster: List<Triple<String, String, Int>>) {
        db.matchDao().insertMatch(MatchEntity(id, "2026-10-13", clock.zone.id, createdAt, createdAt,
            status = "FINISHED", finishedAt = createdAt, dealerParticipantId = "$id-0", resultRevision = 1))
        roster.forEachIndexed { index, (playerId, name, total) ->
            db.matchDao().insertParticipant(MatchParticipantEntity("$id-$index", id, playerId, name, index, 1, createdAt, total))
        }
    }

    @Test fun concurrentNamesAndRetriesReuseDeterministicCanonicalPlayer() = runBlocking {
        // Earliest creation wins; lexical id breaks a creation-time tie.
        db.playerDao().upsert(PlayerEntity("z-old", "MAX", "max", 1, 1))
        db.playerDao().upsert(PlayerEntity("a-new", "Max", "max", 2, 2))
        db.playerDao().upsert(PlayerEntity("a-old", "max", "max", 1, 1))
        val commands = listOf(" Max ", "MAX", "max", "mAx").mapIndexed { index, name ->
            CreateMatch("canonical-$index", listOf(CreateMatchPlayer("max", displayName = name),
                CreateMatchPlayer("other", displayName = "Other $index")))
        }
        val ids = coroutineScope { commands.map { command -> async(Dispatchers.Default) {
            RoomMatchRepository(db, clock).createMatch(command)
        } }.awaitAll() }
        ids.forEachIndexed { index, id ->
            assertEquals("a-old", snapshot(id).participants.first().playerId.value)
            assertEquals(commands[index].players.first().displayName.trim(), snapshot(id).participants.first().displayName)
            assertEquals(id, RoomMatchRepository(db, clock).createMatch(commands[index]))
        }
        assertEquals(7, db.playerDao().players().size)
        rejected { repo.createMatch(commands.first().copy(players = commands.first().players.reversed())) }
        rejected { repo.createMatch(commands.first().copy(players = commands.first().players.map {
            if (it.draftId == "max") it.copy(displayName = "MAX") else it
        })) }
    }

    @Test fun concurrentNewNamesResolveOnceAndLateJoinReplaysAfterCanonicalNameChanges() = runBlocking {
        val ids = coroutineScope { (1..4).map { index -> async(Dispatchers.Default) {
            repo.createMatch(CreateMatch("new-$index", listOf(CreateMatchPlayer("a", displayName = if (index % 2 == 0) "Ana" else "ANA"),
                CreateMatchPlayer("b", displayName = "Other $index"))))
        } }.awaitAll() }
        assertEquals(1, ids.map { snapshot(it).participants.first().playerId }.distinct().size)
        assertEquals(5, db.playerDao().players().size)
        val target = create()
        val command = AddPlayerToMatch(target, "canonical-late", " Ana ")
        val joined = repo.addPlayerToMatch(command)
        val before = snapshot(target)
        assertEquals(snapshot(ids.first()).participants.first().playerId, before.participants.last().playerId)
        players.upsert(UpsertPlayer(before.participants.last().playerId, "Renamed"))
        assertEquals(joined, RoomMatchRepository(db, clock).addPlayerToMatch(command))
        assertEquals(before, snapshot(target))
        rejected { repo.addPlayerToMatch(command.copy(displayName = "ANA")) }
        rejected { repo.addPlayerToMatch(command.copy(operationToken = "another", displayName = "ANA")) }
    }

    @Test fun nameEditsResolveNewIdentityAndDuplicateNamesAreRejectedAtomically() = runBlocking {
        val old = players.upsert(UpsertPlayer(displayName = "Julia"))
        val other = players.upsert(UpsertPlayer(displayName = "Max"))
        val first = repo.createMatch(CreateMatch("before-rename", listOf(CreateMatchPlayer("j", old, "Julia"), CreateMatchPlayer("m", other, "Max"))))
        val before = snapshot(first)
        val renamed = repo.createMatch(CreateMatch("after-rename", listOf(CreateMatchPlayer("j", old, "Julia2"), CreateMatchPlayer("m", other, "MAX"))))
        assertNotEquals(old, snapshot(renamed).participants.first().playerId)
        assertEquals("Julia", db.playerDao().get(old.value)!!.displayName)
        assertEquals(before, snapshot(first))
        val allPlayers = db.playerDao().players()
        rejected { repo.createMatch(CreateMatch("duplicate-names", listOf(CreateMatchPlayer("a", displayName = " Max "), CreateMatchPlayer("b", displayName = "MAX")))) }
        assertEquals(allPlayers, db.playerDao().players())
        assertEquals(2, db.matchDao().matches().size)
    }

    @Test fun legacyIdLateJoinResolvesCanonicalAndRejectsAnotherIdWithSameName() = runBlocking {
        db.playerDao().upsert(PlayerEntity("late-first", "Ana", "ana", 1, 1))
        db.playerDao().upsert(PlayerEntity("late-second", "ANA", "ana", 2, 2))
        val id = create()
        repo.addParticipant(AddParticipant(id, PlayerId("late-second"), 1))
        val joined = snapshot(id)
        assertEquals(PlayerId("late-first"), joined.participants[1].playerId)
        assertEquals("ANA", joined.participants[1].displayName)
        rejected { repo.addParticipant(AddParticipant(id, PlayerId("late-second"), 3)) }
        assertEquals(joined, snapshot(id))
    }

    @Test fun legacyCreationReceiptReplaysEvenIfOriginalRosterHasDuplicateNames() = runBlocking {
        db.withTransaction {
            db.playerDao().upsert(PlayerEntity("same-a", "Same", "same", 1, 1))
            db.playerDao().upsert(PlayerEntity("same-b", "Same", "same", 1, 1))
            seedFinished("legacy", 1, listOf(Triple("same-a", "Same", 0), Triple("same-b", "Same", 101)))
            val row = db.matchDao().get("legacy")!!
            // SHA-256 of opening dealer "a" plus the length-prefixed v1 player payload.
            db.matchDao().updateMatch(row.copy(creationToken = "legacy-retry",
                creationFingerprint = "f8280b30a1bcf54e3f8bfac5127da22d5cb8f7b18f8d821267fe93c0a829d5af"))
        }
        val before = snapshot(MatchId("legacy"))
        val command = CreateMatch("legacy-retry", listOf(CreateMatchPlayer("a", displayName = "Same"), CreateMatchPlayer("b", displayName = "Same")))
        assertEquals(MatchId("legacy"), repo.createMatch(command))
        assertEquals(before, snapshot(MatchId("legacy")))
        rejected { repo.createMatch(command.copy(players = command.players.reversed())) }
        rejected { repo.createMatch(command.copy(creationToken = "new-duplicate")) }
        assertEquals(1, db.matchDao().matches().size)
    }

    @Test fun namedLateJoinAfterLegacyRenameUsesAnotherTechnicalIdAndRetriesDurably() = runBlocking {
        val id = create()
        val originalCommand = AddPlayerToMatch(id, "original-ana", "Ana")
        val originalParticipant = repo.addPlayerToMatch(originalCommand)
        val original = snapshot(id)
        val canonicalId = original.participants.last().playerId
        players.upsert(UpsertPlayer(canonicalId, "Renamed"))
        clock.time = clock.time.plusSeconds(1)
        val command = AddPlayerToMatch(id, "renamed-join", " Renamed ")

        val joinedIds = coroutineScope { List(4) { async(Dispatchers.Default) {
            RoomMatchRepository(db, clock).addPlayerToMatch(command)
        } }.awaitAll() }

        val joined = snapshot(id)
        assertEquals(1, joinedIds.distinct().size)
        assertEquals(original.participants, joined.participants.take(3))
        assertEquals("Renamed", joined.participants.last().displayName)
        assertNotEquals(canonicalId, joined.participants.last().playerId)
        assertEquals(4, db.playerDao().players().size)
        assertEquals(originalParticipant, repo.addPlayerToMatch(originalCommand))
        assertEquals(joinedIds.first(), RoomMatchRepository(db, clock).addPlayerToMatch(command))
        rejected { repo.addPlayerToMatch(command.copy(displayName = "RENAMED")) }
        rejected { repo.addPlayerToMatch(command.copy(operationToken = "duplicate-name", displayName = "RENAMED")) }
        assertEquals(joined, snapshot(id))
        val another = create()
        repo.addPlayerToMatch(AddPlayerToMatch(another, "ordinary-reuse", "Renamed"))
        assertEquals(canonicalId, snapshot(another).participants.last().playerId)
        assertEquals(4, db.playerDao().players().size)
    }

    @Test fun legacyIdLateJoinAfterRenamePreservesSnapshotAndRejectsDuplicateNormalizedName() = runBlocking {
        val id = create()
        repo.addPlayerToMatch(AddPlayerToMatch(id, "original-ana", "Ana"))
        val original = snapshot(id)
        val canonicalId = original.participants.last().playerId
        players.upsert(UpsertPlayer(canonicalId, "Renamed"))
        clock.time = clock.time.plusSeconds(1)

        repo.addParticipant(AddParticipant(id, canonicalId, 3))

        val joined = snapshot(id)
        assertEquals(original.participants, joined.participants.take(3))
        assertEquals("Renamed", joined.participants.last().displayName)
        assertNotEquals(canonicalId, joined.participants.last().playerId)
        assertEquals(4, db.playerDao().players().size)
        rejected { repo.addParticipant(AddParticipant(id, canonicalId, 4)) }
        rejected { repo.addPlayerToMatch(AddPlayerToMatch(id, "duplicate-name", " RENAMED ")) }
        assertEquals(joined, snapshot(id))
        val another = create()
        repo.addParticipant(AddParticipant(another, canonicalId, 2))
        assertEquals(canonicalId, snapshot(another).participants.last().playerId)
        assertEquals(4, db.playerDao().players().size)
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
                    CreateMatchPlayer("new-b", displayName = "Other"),
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
                CreateMatchPlayer("equal-b", displayName = "Other"),
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
        assertEquals(listOf("Same", "Other"), snapshot(first).participants.map { it.displayName })
    }

    @Test fun createPersistsSelectedOpeningHandWithoutChangingRosterOrder() = runBlocking {
        val command = CreateMatch(
            creationToken = "selected-opening-hand",
            players = listOf(
                CreateMatchPlayer("ana", displayName = "Ana"),
                CreateMatchPlayer("bruno", displayName = "Bruno"),
                CreateMatchPlayer("carla", displayName = "Carla"),
            ),
            initialDealerDraftId = "bruno",
        )

        val id = repo.createMatch(command)
        val state = snapshot(id)

        assertEquals(listOf("Ana", "Bruno", "Carla"), state.participants.map { it.displayName })
        assertEquals(state.participants[1].id, state.dealerParticipantId)
        rejected { repo.createMatch(command.copy(initialDealerDraftId = "carla")) }
        rejected { repo.createMatch(command.copy(creationToken = "invalid-hand", initialDealerDraftId = "missing")) }
    }

    @Test fun createReuseAfterLegacyPlayerRenameResolvesSnapshotNameWithoutChangingHistory() = runBlocking {
        val a = players.upsert(UpsertPlayer(displayName = " Ana "))
        val b = players.upsert(UpsertPlayer(displayName = "Bia"))
        assertNotEquals(a, b)
        val id = repo.createMatch(CreateMatch("rename-first", listOf(
            CreateMatchPlayer("a", a, "Ana"), CreateMatchPlayer("b", b, "Bia"),
        )))
        players.upsert(UpsertPlayer(a, "Bea"))
        val saved = snapshot(id)
        assertEquals(listOf("Ana", "Bia"), saved.participants.map { it.displayName })
        assertEquals(saved.participants.first().id, saved.dealerParticipantId)
        assertTrue(saved.rounds.isEmpty())
        val reused = repo.createMatch(CreateMatch("rename-second", saved.participants.reversed().mapIndexed { index, participant ->
            CreateMatchPlayer("reused-$index", participant.playerId, participant.displayName)
        }))
        assertEquals(b, snapshot(reused).participants.first().playerId)
        assertNotEquals(a, snapshot(reused).participants.last().playerId)
        assertEquals(listOf("Bia", "Ana"), snapshot(reused).participants.map { it.displayName })
        assertEquals(3, players.observePlayers().first().size)
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

    @Test fun namedLateJoinRollsBackGlobalPlayerWhenParticipantInsertFails() = runBlocking {
        val id = create()
        val before = snapshot(id)
        val globalPlayers = db.playerDao().players()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_named_join BEFORE INSERT ON participants BEGIN SELECT RAISE(ABORT, 'join failure after player write'); END")
        try {
            repo.addPlayerToMatch(AddPlayerToMatch(id, "late-failure", "Dora"))
            fail("Expected SQLite failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(globalPlayers, db.playerDao().players())
        assertEquals(before, snapshot(id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_named_join")
        repo.addPlayerToMatch(AddPlayerToMatch(id, "late-failure", "Dora"))
        assertEquals(3, db.playerDao().players().size)
        assertEquals(3, snapshot(id).participants.size)
    }

    @Test fun namedLateJoinCancellationBeforeCommitRollsBackBothWrites() = runBlocking {
        val inFlight = java.util.concurrent.atomic.AtomicReference<Job?>()
        val intercepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancellableDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PontinhoDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ ->
                if (sql.startsWith("INSERT", ignoreCase = true) && sql.contains("`participants`")) {
                    inFlight.get()?.let { job ->
                        intercepted.set(true)
                        job.cancel(CancellationException("Cancel after global player insert, before participant insert"))
                    }
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        try {
            val cancellableRepo = RoomMatchRepository(cancellableDb, clock)
            val id = cancellableRepo.createMatch(CreateMatch("cancel-real-transaction", listOf(
                CreateMatchPlayer("a", displayName = "Ana"), CreateMatchPlayer("b", displayName = "Bruno"))))
            val before = cancellableRepo.observeMatch(id).first()
            val globalPlayers = cancellableDb.playerDao().players()
            val command = AddPlayerToMatch(id, "late-cancel", "Dora")
            val operation = async(start = CoroutineStart.LAZY) { cancellableRepo.addPlayerToMatch(command) }
            inFlight.set(operation)
            operation.start()
            try { operation.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
            inFlight.set(null)
            assertTrue("Cancellation must intercept the actual participation INSERT", intercepted.get())
            assertEquals(globalPlayers, cancellableDb.playerDao().players())
            assertEquals(before, cancellableRepo.observeMatch(id).first())
            cancellableRepo.addPlayerToMatch(command)
            assertEquals(3, cancellableDb.playerDao().players().size)
            assertEquals(3, cancellableRepo.observeMatch(id).first()!!.participants.size)
        } finally { cancellableDb.close() }
    }

    @Test fun namedLateJoinRetryIsDurableConcurrentAndIndependentOfLaterRoundState() = runBlocking {
        val id = create()
        submit(id, start(id), 0, 10)
        val command = AddPlayerToMatch(id, "late-retry", " Dora ")
        val identities = coroutineScope {
            List(4) { async(Dispatchers.Default) { repo.addPlayerToMatch(command) } }.awaitAll()
        }
        assertEquals(1, identities.distinct().size)
        val joined = snapshot(id)
        assertEquals(3, joined.participants.size)
        assertEquals(3, db.playerDao().players().size)
        assertEquals("Dora", joined.participants.last().displayName)
        assertEquals(2, joined.participants.last().eligibleFromRoundOrdinal)
        assertFalse(identities.first() in joined.rounds[0].draft.eligibleParticipants)
        repo.reorderParticipants(ReorderParticipants(id, joined.participants.map { it.id }.reversed()))
        val later = start(id); submit(id, later, 0, 3)
        val beforeRetry = snapshot(id)
        assertEquals(identities.first(), RoomMatchRepository(db, clock).addPlayerToMatch(command.copy(displayName = "Dora")))
        assertEquals(beforeRetry, snapshot(id))
        rejected { repo.addPlayerToMatch(command.copy(displayName = "Changed")) }
        rejected { repo.addPlayerToMatch(command.copy(operationToken = "different-token")) }
        assertEquals(3, db.playerDao().players().size)
        assertEquals(beforeRetry, snapshot(id))
        submit(id, later, 1, 101)
        val finished = snapshot(id)
        assertTrue(finished.isFinished)
        assertEquals(identities.first(), RoomMatchRepository(db, clock).addPlayerToMatch(command))
        rejected { repo.addPlayerToMatch(command.copy(operationToken = "new-after-finish")) }
        assertEquals(finished, snapshot(id))
        assertEquals(3, db.playerDao().players().size)
    }

    @Test fun rosterMutationsRejectHistoricalCorrectionAndPausedDraftWithoutChangingHistory() = runBlocking {
        val id = create(3); val first = start(id)
        submit(id, first, 0, 10); submit(id, first, 1, 20)
        val roster = snapshot(id).participants
        val late = players.upsert(UpsertPlayer(displayName = "Late"))
        val second = start(id); submit(id, second, 0, 7)
        repo.removeRoundEntry(RemoveRoundEntry(id, first, roster[2].id))
        val correction = snapshot(id)
        assertEquals(first, correction.openRound!!.id)
        assertEquals(listOf(second), correction.pausedRounds.map { it.id })
        rejected { repo.addParticipant(AddParticipant(id, late, 3)) }
        rejected { repo.reorderParticipants(ReorderParticipants(id, roster.map { it.id }.reversed())) }
        assertEquals(correction, snapshot(id))
        assertFalse(db.roundDao().scores(first.value).any { it.participantId == roster[2].id.value })
        designate(DesignateWinner(id, first, roster[2].id))
        val resumed = snapshot(id)
        assertEquals(second, resumed.openRound!!.id)
        rejected { repo.addParticipant(AddParticipant(id, late, 3)) }
        assertEquals(resumed, snapshot(id))
        submit(id, second, 1, 3)
        repo.addParticipant(AddParticipant(id, late, 3))
        val after = snapshot(id)
        assertEquals(3, after.participants.last().eligibleFromRoundOrdinal)
        assertTrue(after.rounds.all { after.participants.last().id !in it.draft.eligibleParticipants })
        assertEquals(0, after.totals[after.participants.last().id])
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
        val prepared = repo.prepareRound(PrepareRound(id, indicator))
        assertEquals(2, prepared.ordinal)
        assertEquals(1, snapshot(id).rounds.size)
    }

    @Test fun dayProjectionUsesStoredDateNewestFirstCompletedOnlyTieAggregationAndStableOrdering() = runBlocking {
        clock.time = Instant.parse("2026-10-14T02:00:00Z")
        val complete = repo.createMatch(CreateMatch("history-complete", listOf(
            CreateMatchPlayer("same-a", displayName = "Alex"),
            CreateMatchPlayer("same-b", displayName = "Bella"),
            CreateMatchPlayer("carla", displayName = "Carla"),
            CreateMatchPlayer("nina", displayName = "Nina"),
        )))
        val completeRows = db.matchDao().participants(complete.value)
        listOf(48, 48, 112, 77).forEachIndexed { index, total ->
            db.matchDao().updateParticipant(completeRows[index].copy(total = total))
        }
        val completeEntity = db.matchDao().get(complete.value)!!
        db.matchDao().updateMatch(completeEntity.copy(status = "FINISHED", finishedAt = clock.millis(), resultRevision = 1))

        clock.time = Instant.parse("2026-10-14T02:30:00Z")
        val active = repo.createMatch(CreateMatch("history-active", listOf(
            CreateMatchPlayer("active-a", displayName = "Dora"),
            CreateMatchPlayer("active-b", displayName = "Eva"),
        )))
        db.matchDao().participants(active.value).forEach {
            db.matchDao().updateParticipant(it.copy(total = 999))
        }

        clock.time = Instant.parse("2026-10-14T01:30:00Z")
        val olderComplete = repo.createMatch(CreateMatch("history-older-complete", completeRows.mapIndexed { index, row ->
            CreateMatchPlayer("older-$index", PlayerId(row.playerId), row.nameSnapshot)
        }))
        val olderRows = db.matchDao().participants(olderComplete.value)
        listOf(10, 10, 101, 91).forEachIndexed { index, total ->
            db.matchDao().updateParticipant(olderRows[index].copy(total = total))
        }
        val olderEntity = db.matchDao().get(olderComplete.value)!!
        db.matchDao().updateMatch(olderEntity.copy(status = "FINISHED", finishedAt = clock.millis(), resultRevision = 1))

        clock.zoneId = ZoneId.of("UTC")
        clock.time = Instant.parse("2026-10-15T12:00:00Z")
        val day = repo.observeDay(LocalDate.parse("2026-10-13")).first()

        assertEquals(listOf(active, complete, olderComplete), day.matches.map { it.id })
        assertEquals(listOf(999, 999), day.matches.first().players.map { it.total })
        assertTrue(day.matches.first().players.none { it.isWinner || it.isHighestPenalty })
        assertEquals(
            listOf(PlayerNameKey("alex"), PlayerNameKey("bella"), PlayerNameKey("carla"), PlayerNameKey("nina")),
            day.wins.map { it.playerKey },
        )
        assertEquals(listOf(2, 2, 0, 0), day.wins.map { it.value })
        assertEquals(listOf(213, 168, 58, 58), day.penalties.map { it.value })
        assertEquals(PlayerNameKey("carla"), day.penalties.first().playerKey)
        assertEquals("America/Sao_Paulo", day.matches[1].timeZoneId)
        assertTrue(repo.observeDay(LocalDate.parse("2026-10-14")).first().matches.isEmpty())
    }

    @Test fun acknowledgedResultAndCorrectionRevisionsRemainDurableAcrossRepositoryRecreation() = runBlocking {
        val id = create()
        val round = start(id)
        submit(id, round, 0, 101)
        val firstResult = snapshot(id)
        assertTrue(firstResult.hasPendingResults)

        repo.acknowledgeResults(id, firstResult.resultRevision)
        repo = RoomMatchRepository(db, clock)
        var state = snapshot(id)
        assertFalse(state.hasPendingResults)
        assertEquals(firstResult.resultRevision, state.acknowledgedResultRevision)

        repo.updateExplicitScore(UpdateExplicitScore(id, round, state.participants[0].id, 100))
        repo = RoomMatchRepository(db, clock)
        state = snapshot(id)
        assertFalse(state.isFinished)
        assertNull(state.outcome)
        assertFalse(state.hasPendingResults)

        repo.updateExplicitScore(UpdateExplicitScore(id, round, state.participants[0].id, 101))
        repo = RoomMatchRepository(db, clock)
        state = snapshot(id)
        assertTrue(state.isFinished)
        assertTrue(state.hasPendingResults)
        assertTrue(state.resultRevision > firstResult.resultRevision)
        assertEquals(0L, state.acknowledgedResultRevision)
    }

    @Test fun lifetimeStatisticsReactToCorrectionAndDeletion() = runBlocking {
        clock.time = Instant.parse("2026-10-13T12:00:00Z")
        val older = create(3)
        val olderFirst = start(older)
        submit(older, olderFirst, 0, 20)
        submit(older, olderFirst, 1, 0)
        val olderLast = start(older)
        submit(older, olderLast, 0, 101)
        submit(older, olderLast, 1, 0)
        // Final totals: 121, 0, 0. Both players with zero points win.

        clock.time = Instant.parse("2026-10-14T12:00:00Z")
        val newer = create(3)
        val newerFirst = start(newer)
        submit(newer, newerFirst, 0, 0)
        submit(newer, newerFirst, 1, 10)
        val newerLast = start(newer)
        submit(newer, newerLast, 0, 101)
        submit(newer, newerLast, 1, 0)
        // Final totals: 101, 10, 0. Player 3 is the sole winner.
        assertNotEquals(snapshot(older).localDate, snapshot(newer).localDate)

        clock.time = Instant.parse("2026-10-15T12:00:00Z")
        val active = create(3)
        val activeRound = start(active)
        submit(active, activeRound, 0, 90)
        submit(active, activeRound, 1, 80)
        assertFalse(snapshot(active).isFinished)

        val emissions = Channel<List<LifetimePlayerStatistics>>(Channel.UNLIMITED)
        val collection = launch { repo.observeLifetimeStatistics().collect { emissions.send(it) } }
        suspend fun nextRows() = withTimeout(5_000) { emissions.receive() }
        try {
            assertEquals(listOf(
                LifetimePlayerStatistics(PlayerNameKey("player 1"), "Player 1", 2, 0, 222L),
                LifetimePlayerStatistics(PlayerNameKey("player 2"), "Player 2", 2, 1, 10L),
                LifetimePlayerStatistics(PlayerNameKey("player 3"), "Player 3", 2, 2, 0L),
            ), nextRows())

            val olderRoster = snapshot(older).participants
            repo.updateExplicitScore(UpdateExplicitScore(older, olderFirst, olderRoster[1].id, 5))
            assertEquals(setOf(olderRoster[2].id), snapshot(older).outcome!!.winners)
            assertEquals(listOf(
                LifetimePlayerStatistics(PlayerNameKey("player 1"), "Player 1", 2, 0, 222L),
                LifetimePlayerStatistics(PlayerNameKey("player 2"), "Player 2", 2, 0, 15L),
                LifetimePlayerStatistics(PlayerNameKey("player 3"), "Player 3", 2, 2, 0L),
            ), nextRows())

            repo.deleteMatch(newer)
            assertEquals(listOf(
                LifetimePlayerStatistics(PlayerNameKey("player 1"), "Player 1", 1, 0, 121L),
                LifetimePlayerStatistics(PlayerNameKey("player 2"), "Player 2", 1, 0, 5L),
                LifetimePlayerStatistics(PlayerNameKey("player 3"), "Player 3", 1, 1, 0L),
            ), nextRows())
        } finally {
            collection.cancelAndJoin()
            emissions.close()
        }
    }

    private class TestClock(var time: Instant, var zoneId: ZoneId) : Clock() {
        override fun instant() = time
        override fun getZone() = zoneId
        override fun withZone(zone: ZoneId): Clock = TestClock(time, zone)
    }
}
