package com.maxmfonseca.pontinho.domain.rules

import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.Indicator
import com.maxmfonseca.pontinho.domain.model.Rank

object CardValueCalculator {
    fun value(card: Card, indicator: Indicator): Int = when (card) {
        Card.PrintedJoker -> 20
        is Card.Suited -> when {
            card.rank == indicator.rank.next() && card.suit.color == indicator.color -> 20
            card.rank == Rank.ACE -> 15
            card.rank.canonicalValue <= 10 -> card.rank.canonicalValue
            else -> 10
        }
    }
}
