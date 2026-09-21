package com.maxmfonseca.pontinho.domain.rules

import com.maxmfonseca.pontinho.domain.model.ParticipantId
import com.maxmfonseca.pontinho.domain.model.RoundDraft
import com.maxmfonseca.pontinho.domain.model.RoundEntryOrigin
import com.maxmfonseca.pontinho.domain.model.WinnerDesignationOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundEngineTest {
    private val ana = ParticipantId("ana")
    private val bia = ParticipantId("bia")
    private val caio = ParticipantId("caio")
    private val latePlayer = ParticipantId("late-player")

    @Test
    fun `explicit numeric zero remains an explicit losing submission`() {
        val transition = RoundEngine.submitScore(round(ana, bia), ana, 0).applied()

        assertTrue(transition.state.isComplete)
        assertEquals(0, transition.state.entries.getValue(ana).points)
        assertEquals(RoundEntryOrigin.EXPLICIT, transition.state.entries.getValue(ana).origin)
        assertEquals(RoundEntryOrigin.AUTOMATIC_WINNER, transition.state.entries.getValue(bia).origin)
    }

    @Test
    fun `manual winner designation alone leaves the round open`() {
        val designated = RoundEngine.designateWinner(round(ana, bia, caio), ana)

        assertFalse(designated.isComplete)
        assertEquals(ana, designated.designatedWinner)
        assertEquals(WinnerDesignationOrigin.MANUAL, designated.winner?.origin)
        assertTrue(designated.entries.isEmpty())
    }

    @Test
    fun `manual winner closes only after every other eligible player submits`() {
        val designated = RoundEngine.designateWinner(round(ana, bia, caio), ana)

        val afterBia = RoundEngine.submitScore(designated, bia, 12).applied()
        assertFalse(afterBia.state.isComplete)
        assertFalse(afterBia.state.entries.containsKey(ana))

        val afterCaio = RoundEngine.submitScore(afterBia.state, caio, 4).applied()
        assertTrue(afterCaio.state.isComplete)
        assertEquals(0, afterCaio.state.entries.getValue(ana).points)
        assertEquals(RoundEntryOrigin.AUTOMATIC_WINNER, afterCaio.state.entries.getValue(ana).origin)
    }

    @Test
    fun `n minus one explicit scores infer the sole missing eligible winner`() {
        val afterAna = RoundEngine.submitScore(round(ana, bia, caio), ana, 9).applied()
        val afterBia = RoundEngine.submitScore(afterAna.state, bia, 14).applied()

        assertTrue(afterBia.state.isComplete)
        assertEquals(caio, afterBia.state.designatedWinner)
        assertEquals(WinnerDesignationOrigin.INFERRED, afterBia.state.winner?.origin)
        assertEquals(0, afterBia.state.entries.getValue(caio).points)
    }

    @Test
    fun `a completed round has exactly one automatic zero`() {
        val afterAna = RoundEngine.submitScore(round(ana, bia, caio), ana, 0).applied()
        val completed = RoundEngine.submitScore(afterAna.state, bia, 0).applied().state

        assertEquals(3, completed.entries.size)
        assertEquals(
            1,
            completed.entries.values.count { it.origin == RoundEntryOrigin.AUTOMATIC_WINNER },
        )
        assertEquals(
            2,
            completed.entries.values.count { it.origin == RoundEntryOrigin.EXPLICIT },
        )
    }

    @Test
    fun `deleting inferred winner zero reopens without immediate re-inference`() {
        val afterAna = RoundEngine.submitScore(round(ana, bia, caio), ana, 8).applied()
        val completed = RoundEngine.submitScore(afterAna.state, bia, 13).applied().state

        val reopened = RoundEngine.removeEntry(completed, caio).applied()

        assertFalse(reopened.state.isComplete)
        assertNull(reopened.state.designatedWinner)
        assertEquals(setOf(ana, bia), reopened.state.entries.keys)
        assertTrue(reopened.state.entries.values.none { it.origin == RoundEntryOrigin.AUTOMATIC_WINNER })
        assertTrue(reopened.events.any { it is RoundEvent.RoundReopened })
    }

    @Test
    fun `deleting an explicit scorer from inferred completion removes automatic zero without re-inference`() {
        val afterAna = RoundEngine.submitScore(round(ana, bia, caio), ana, 8).applied()
        val completed = RoundEngine.submitScore(afterAna.state, bia, 13).applied().state

        val reopened = RoundEngine.removeEntry(completed, ana).applied()

        assertFalse(reopened.state.isComplete)
        assertNull(reopened.state.designatedWinner)
        assertEquals(setOf(bia), reopened.state.entries.keys)
        assertTrue(reopened.state.entries.values.none { it.origin == RoundEntryOrigin.AUTOMATIC_WINNER })
        assertTrue(reopened.events.none { it is RoundEvent.WinnerDesignated })
    }

    @Test
    fun `deleting an explicit scorer from manual completion removes automatic zero and retains designation`() {
        val designated = RoundEngine.designateWinner(round(ana, bia, caio), ana)
        val afterBia = RoundEngine.submitScore(designated, bia, 12).applied()
        val completed = RoundEngine.submitScore(afterBia.state, caio, 4).applied().state

        val reopened = RoundEngine.removeEntry(completed, bia).applied()

        assertFalse(reopened.state.isComplete)
        assertEquals(ana, reopened.state.designatedWinner)
        assertEquals(WinnerDesignationOrigin.MANUAL, reopened.state.winner?.origin)
        assertEquals(setOf(caio), reopened.state.entries.keys)
        assertTrue(reopened.state.entries.values.none { it.origin == RoundEntryOrigin.AUTOMATIC_WINNER })
        assertTrue(reopened.events.none { it is RoundEvent.WinnerDesignated })
    }

    @Test
    fun `players outside the round eligible set do not count toward closure`() {
        val state = round(ana, bia)

        val completed = RoundEngine.submitScore(state, ana, 6).applied().state

        assertTrue(completed.isComplete)
        assertEquals(bia, completed.designatedWinner)
        assertFalse(completed.entries.containsKey(latePlayer))
    }

    @Test
    fun `negative score is rejected with typed failure and unchanged state`() {
        val initial = round(ana, bia, caio)

        val rejected = RoundEngine.submitScore(initial, ana, -1).rejected()

        assertSame(initial, rejected.state)
        assertEquals(RoundFailure.NegativePoints(-1), rejected.failure)
    }

    @Test
    fun `maximum representable integer score is accepted`() {
        val initial = round(ana, bia, caio)

        val applied = RoundEngine.submitScore(initial, ana, Int.MAX_VALUE).applied()

        assertEquals(Int.MAX_VALUE, applied.state.entries.getValue(ana).points)
    }

    @Test
    fun `duplicate explicit submission is rejected with typed failure`() {
        val first = RoundEngine.submitScore(round(ana, bia, caio), ana, 3).applied()

        val duplicate = RoundEngine.submitScore(first.state, ana, 5).rejected()

        assertSame(first.state, duplicate.state)
        assertEquals(RoundFailure.DuplicateExplicitSubmission(ana), duplicate.failure)
    }

    @Test
    fun `duplicate explicit submission remains specifically typed after round completion`() {
        val completed = RoundEngine.submitScore(round(ana, bia), ana, 3).applied().state

        val duplicate = RoundEngine.submitScore(completed, ana, 5).rejected()

        assertSame(completed, duplicate.state)
        assertEquals(RoundFailure.DuplicateExplicitSubmission(ana), duplicate.failure)
    }

    @Test
    fun `ineligible submission is rejected with participant failure`() {
        val initial = round(ana, bia, caio)

        val rejected = RoundEngine.submitScore(initial, latePlayer, 7).rejected()

        assertSame(initial, rejected.state)
        assertEquals(RoundFailure.ParticipantNotEligible(latePlayer), rejected.failure)
    }

    @Test
    fun `designated winner cannot submit an ordinary score`() {
        val designated = RoundEngine.designateWinner(round(ana, bia, caio), ana)

        val rejected = RoundEngine.submitScore(designated, ana, 7).rejected()

        assertSame(designated, rejected.state)
        assertEquals(RoundFailure.DesignatedWinnerCannotSubmit(ana), rejected.failure)
    }

    @Test
    fun `ordinary submission after completion is rejected as completed round`() {
        val completed = RoundEngine.submitScore(round(ana, bia), ana, 7).applied().state

        val rejected = RoundEngine.submitScore(completed, bia, 0).rejected()

        assertSame(completed, rejected.state)
        assertEquals(RoundFailure.RoundAlreadyComplete, rejected.failure)
    }

    @Test
    fun `repeated winner designation throws typed winner failure`() {
        val designated = RoundEngine.designateWinner(round(ana, bia, caio), ana)

        val exception = assertThrows(RoundRuleException::class.java) {
            RoundEngine.designateWinner(designated, bia)
        }

        assertEquals(RoundFailure.WinnerAlreadyDesignated, exception.failure)
    }

    @Test
    fun `ineligible winner designation throws participant failure`() {
        val exception = assertThrows(RoundRuleException::class.java) {
            RoundEngine.designateWinner(round(ana, bia, caio), latePlayer)
        }

        assertEquals(RoundFailure.ParticipantNotEligible(latePlayer), exception.failure)
    }

    @Test
    fun `removing a missing entry is rejected with typed failure`() {
        val initial = round(ana, bia, caio)

        val rejected = RoundEngine.removeEntry(initial, ana).rejected()

        assertSame(initial, rejected.state)
        assertEquals(RoundFailure.EntryNotFound(ana), rejected.failure)
    }

    @Test
    fun `successful score transition reports submission and completion events`() {
        val transition = RoundEngine.submitScore(round(ana, bia), ana, 11).applied()

        assertEquals(
            listOf(
                RoundEvent.ScoreSubmitted(ana, 11),
                RoundEvent.WinnerDesignated(bia, WinnerDesignationOrigin.INFERRED),
                RoundEvent.RoundCompleted(bia),
            ),
            transition.events,
        )
    }

    private fun round(
        first: ParticipantId,
        second: ParticipantId,
        third: ParticipantId? = null,
    ) = RoundDraft(
        eligibleParticipants = listOfNotNull(first, second, third),
    )

    private fun RoundTransition.applied(): RoundTransition.Applied {
        assertTrue("expected applied transition but was $this", this is RoundTransition.Applied)
        return this as RoundTransition.Applied
    }

    private fun RoundTransition.rejected(): RoundTransition.Rejected {
        assertTrue("expected rejected transition but was $this", this is RoundTransition.Rejected)
        return this as RoundTransition.Rejected
    }
}
