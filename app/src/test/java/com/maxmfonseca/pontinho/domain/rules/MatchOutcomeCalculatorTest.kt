package com.maxmfonseca.pontinho.domain.rules

import com.maxmfonseca.pontinho.domain.model.ParticipantId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchOutcomeCalculatorTest {
    private val ana = ParticipantId("ana")
    private val bia = ParticipantId("bia")
    private val caio = ParticipantId("caio")
    private val dora = ParticipantId("dora")

    @Test
    fun `exactly one hundred keeps match active`() {
        assertNull(MatchOutcomeCalculator.evaluate(mapOf(ana to 100, bia to 42)))
    }

    @Test
    fun `one hundred and one completes match`() {
        val outcome = MatchOutcomeCalculator.evaluate(mapOf(ana to 101, bia to 42))

        assertEquals(setOf(bia), outcome?.winners)
        assertEquals(setOf(ana), outcome?.highestScorers)
        assertEquals(42, outcome?.minimumTotal)
        assertEquals(101, outcome?.maximumTotal)
    }

    @Test
    fun `tied minimum totals share the win`() {
        val outcome = MatchOutcomeCalculator.evaluate(
            mapOf(ana to 38, bia to 101, caio to 38, dora to 76),
        )

        assertEquals(setOf(ana, caio), outcome?.winners)
        assertEquals(38, outcome?.minimumTotal)
    }

    @Test
    fun `tied maximum penalties share highest score`() {
        val outcome = MatchOutcomeCalculator.evaluate(
            mapOf(ana to 18, bia to 104, caio to 72, dora to 104),
        )

        assertEquals(setOf(bia, dora), outcome?.highestScorers)
        assertEquals(104, outcome?.maximumTotal)
    }

    @Test
    fun `checked accumulation spans rounds from exactly one hundred to completion`() {
        val afterFirstRound = MatchOutcomeCalculator.addRound(
            totals = mapOf(ana to 0, bia to 0),
            roundScores = mapOf(ana to 100, bia to 40),
        ).applied()
        assertNull(MatchOutcomeCalculator.evaluate(afterFirstRound.totals))

        val afterSecondRound = MatchOutcomeCalculator.addRound(
            totals = afterFirstRound.totals,
            roundScores = mapOf(ana to 1, bia to 20),
        ).applied()

        assertEquals(mapOf(ana to 101, bia to 60), afterSecondRound.totals)
        assertEquals(setOf(bia), MatchOutcomeCalculator.evaluate(afterSecondRound.totals)?.winners)
    }

    @Test
    fun `checked accumulation rejects multi-round integer overflow without wrapping`() {
        val afterFirstRound = MatchOutcomeCalculator.addRound(
            totals = mapOf(ana to 0, bia to 0),
            roundScores = mapOf(ana to 100, bia to 0),
        ).applied()

        val rejected = MatchOutcomeCalculator.addRound(
            totals = afterFirstRound.totals,
            roundScores = mapOf(ana to Int.MAX_VALUE, bia to 0),
        ).rejected()

        assertSame(afterFirstRound.totals, rejected.totals)
        assertEquals(
            MatchTotalFailure.TotalOverflow(ana, currentTotal = 100, roundPoints = Int.MAX_VALUE),
            rejected.failure,
        )
    }

    @Test
    fun `checked accumulation rejects negative existing totals`() {
        assertThrows(IllegalArgumentException::class.java) {
            MatchOutcomeCalculator.addRound(
                totals = mapOf(ana to -1),
                roundScores = mapOf(ana to 2),
            )
        }
    }

    @Test
    fun `checked accumulation rejects negative round scores`() {
        assertThrows(IllegalArgumentException::class.java) {
            MatchOutcomeCalculator.addRound(
                totals = mapOf(ana to 1),
                roundScores = mapOf(ana to -1),
            )
        }
    }

    @Test
    fun `outcome evaluation rejects a negative wrapped total`() {
        assertThrows(IllegalArgumentException::class.java) {
            MatchOutcomeCalculator.evaluate(mapOf(ana to -2_147_483_549, bia to 101))
        }
    }

    private fun MatchTotalsTransition.applied(): MatchTotalsTransition.Applied {
        assertTrue("expected applied totals but was $this", this is MatchTotalsTransition.Applied)
        return this as MatchTotalsTransition.Applied
    }

    private fun MatchTotalsTransition.rejected(): MatchTotalsTransition.Rejected {
        assertTrue("expected rejected totals but was $this", this is MatchTotalsTransition.Rejected)
        return this as MatchTotalsTransition.Rejected
    }
}
