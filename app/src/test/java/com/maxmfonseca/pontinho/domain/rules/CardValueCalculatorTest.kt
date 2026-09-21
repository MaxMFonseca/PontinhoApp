package com.maxmfonseca.pontinho.domain.rules

import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.CardColor
import com.maxmfonseca.pontinho.domain.model.Indicator
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Test

class CardValueCalculatorTest {
    @Test
    fun `ace is worth fifteen when it is not wild`() {
        val value = CardValueCalculator.value(
            card = Card.Suited(Rank.ACE, Suit.HEARTS),
            indicator = Indicator(Rank.FIVE, CardColor.RED),
        )

        assertEquals(15, value)
    }

    @Test
    fun `numeric ranks two through ten have their face values`() {
        val expectedValues = listOf(
            Rank.TWO to 2,
            Rank.THREE to 3,
            Rank.FOUR to 4,
            Rank.FIVE to 5,
            Rank.SIX to 6,
            Rank.SEVEN to 7,
            Rank.EIGHT to 8,
            Rank.NINE to 9,
            Rank.TEN to 10,
        )

        expectedValues.forEach { (rank, expected) ->
            assertEquals(
                expected,
                CardValueCalculator.value(
                    card = Card.Suited(rank, Suit.CLUBS),
                    indicator = Indicator(Rank.KING, CardColor.RED),
                ),
            )
        }
    }

    @Test
    fun `face cards are worth ten`() {
        assertEquals(10, ordinaryValue(Rank.JACK))
        assertEquals(10, ordinaryValue(Rank.QUEEN))
        assertEquals(10, ordinaryValue(Rank.KING))
    }

    @Test
    fun `printed joker is worth twenty`() {
        assertEquals(
            20,
            CardValueCalculator.value(
                Card.PrintedJoker,
                Indicator(Rank.SEVEN, CardColor.BLACK),
            ),
        )
    }

    @Test
    fun `same-color card one rank above indicator is worth twenty`() {
        assertEquals(
            20,
            CardValueCalculator.value(
                Card.Suited(Rank.TWO, Suit.DIAMONDS),
                Indicator(Rank.ACE, CardColor.RED),
            ),
        )
    }

    @Test
    fun `opposite-color card one rank above indicator keeps ordinary value`() {
        assertEquals(
            2,
            CardValueCalculator.value(
                Card.Suited(Rank.TWO, Suit.CLUBS),
                Indicator(Rank.ACE, CardColor.RED),
            ),
        )
    }

    @Test
    fun `king indicator wraps the wild rank to ace`() {
        assertEquals(
            20,
            CardValueCalculator.value(
                Card.Suited(Rank.ACE, Suit.SPADES),
                Indicator(Rank.KING, CardColor.BLACK),
            ),
        )
        assertEquals(
            15,
            CardValueCalculator.value(
                Card.Suited(Rank.ACE, Suit.HEARTS),
                Indicator(Rank.KING, CardColor.BLACK),
            ),
        )
    }

    private fun ordinaryValue(rank: Rank): Int = CardValueCalculator.value(
        Card.Suited(rank, Suit.CLUBS),
        Indicator(Rank.ACE, CardColor.RED),
    )
}
