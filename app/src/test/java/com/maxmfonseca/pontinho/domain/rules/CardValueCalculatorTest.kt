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
    fun `all fifty two standard cards keep their ordinary rank value when not wild`() {
        Rank.entries.forEach { rank ->
            Suit.entries.forEach { suit ->
                val card = Card.Suited(rank, suit).requireSuited()

                assertEquals(
                    "${rank.name} of ${suit.name}",
                    ordinaryValue(rank),
                    CardValueCalculator.value(
                        card = card,
                        indicator = Indicator(rank, CardColor.RED),
                    ),
                )
            }
        }
    }

    @Test
    fun `both suits matching each indicator color are wild for every derived rank`() {
        Rank.entries.forEach { indicatorRank ->
            CardColor.entries.forEach { indicatorColor ->
                Suit.entries.filter { it.color == indicatorColor }.forEach { suit ->
                    assertEquals(
                        "${indicatorRank.next().name} of ${suit.name}",
                        20,
                        CardValueCalculator.value(
                            card = Card.Suited(indicatorRank.next(), suit),
                            indicator = Indicator(indicatorRank, indicatorColor),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `king indicators wrap the derived wild rank to ace for both colors`() {
        assertEquals(
            20,
            CardValueCalculator.value(
                Card.Suited(Rank.ACE, Suit.HEARTS),
                Indicator(Rank.KING, CardColor.RED),
            ),
        )
        assertEquals(
            20,
            CardValueCalculator.value(
                Card.Suited(Rank.ACE, Suit.SPADES),
                Indicator(Rank.KING, CardColor.BLACK),
            ),
        )
    }

    @Test
    fun `derived rank in the opposite color keeps its ordinary value`() {
        Rank.entries.forEach { indicatorRank ->
            CardColor.entries.forEach { indicatorColor ->
                val derivedRank = indicatorRank.next()
                Suit.entries.filter { it.color != indicatorColor }.forEach { oppositeSuit ->
                    assertEquals(
                        "${derivedRank.name} of ${oppositeSuit.name}",
                        ordinaryValue(derivedRank),
                        CardValueCalculator.value(
                            card = Card.Suited(derivedRank, oppositeSuit),
                            indicator = Indicator(indicatorRank, indicatorColor),
                        ),
                    )
                }
            }
        }
    }

    private fun ordinaryValue(rank: Rank): Int = when (rank) {
        Rank.ACE -> 15
        Rank.TWO -> 2
        Rank.THREE -> 3
        Rank.FOUR -> 4
        Rank.FIVE -> 5
        Rank.SIX -> 6
        Rank.SEVEN -> 7
        Rank.EIGHT -> 8
        Rank.NINE -> 9
        Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING -> 10
    }

    private fun Card.requireSuited(): Card.Suited = when (this) {
        is Card.Suited -> this
    }
}
