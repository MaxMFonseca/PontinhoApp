package com.maxmfonseca.pontinho.domain.model

enum class CardColor {
    RED,
    BLACK,
}

enum class Suit(val color: CardColor) {
    CLUBS(CardColor.BLACK),
    DIAMONDS(CardColor.RED),
    HEARTS(CardColor.RED),
    SPADES(CardColor.BLACK),
}

enum class Rank(val canonicalValue: Int) {
    ACE(1),
    TWO(2),
    THREE(3),
    FOUR(4),
    FIVE(5),
    SIX(6),
    SEVEN(7),
    EIGHT(8),
    NINE(9),
    TEN(10),
    JACK(11),
    QUEEN(12),
    KING(13),
    ;

    fun next(): Rank = if (this == KING) ACE else entries[ordinal + 1]
    fun previous(): Rank = if (this == ACE) KING else entries[ordinal - 1]
}

sealed interface Card {
    data class Suited(val rank: Rank, val suit: Suit) : Card

    data object PrintedJoker : Card
}

data class Indicator(
    val rank: Rank,
    val color: CardColor,
)
