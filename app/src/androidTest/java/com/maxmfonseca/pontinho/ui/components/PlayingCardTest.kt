package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.CardColor
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayingCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completeDeckExposesUniqueFacesWithRankSuitColorAndDescription() {
        val red = Color(0xFFB53D32).toArgb()
        val dark = Color(0xFF14233B).toArgb()
        compose.setContent {
            PontinhoTheme(themePreference = ThemePreference.LIGHT) {
                Column {
                    Rank.entries.forEach { rank ->
                        Suit.entries.forEach { suit ->
                            val card = Card.Suited(rank, suit)
                            CardFace(
                                card = card,
                                contentDescription = "${rank.name} of ${suit.name}",
                                modifier = Modifier.width(48.dp),
                            )
                        }
                    }
                }
            }
        }

        val expectedRanks = mapOf(
            Rank.ACE to "A",
            Rank.TWO to "2",
            Rank.THREE to "3",
            Rank.FOUR to "4",
            Rank.FIVE to "5",
            Rank.SIX to "6",
            Rank.SEVEN to "7",
            Rank.EIGHT to "8",
            Rank.NINE to "9",
            Rank.TEN to "10",
            Rank.JACK to "J",
            Rank.QUEEN to "Q",
            Rank.KING to "K",
        )
        val expectedSuits = mapOf(
            Suit.CLUBS to "♣",
            Suit.DIAMONDS to "♦",
            Suit.HEARTS to "♥",
            Suit.SPADES to "♠",
        )

        Rank.entries.forEach { rank ->
            Suit.entries.forEach { suit ->
                val tag = "card_face_${rank.name}_${suit.name}"
                compose.onAllNodesWithTag(tag).assertCountEquals(1)
                compose.onNodeWithTag(tag)
                    .assertContentDescriptionEquals("${rank.name} of ${suit.name}")
                    .assert(SemanticsMatcher.expectValue(CardRankLabelKey, expectedRanks.getValue(rank)))
                    .assert(SemanticsMatcher.expectValue(CardSuitGlyphKey, expectedSuits.getValue(suit)))
                    .assert(SemanticsMatcher.expectValue(
                        CardForegroundColorKey,
                        if (suit.color == CardColor.RED) red else dark,
                    ))
            }
        }
    }

    @Test
    fun jokerShowsOnlyItsShortRankInTheRequestedColor() {
        val red = Color(0xFFB53D32).toArgb()
        compose.setContent {
            JokerCard(
                rank = Rank.QUEEN,
                color = CardColor.RED,
                contentDescription = "red queen joker",
                modifier = Modifier.width(48.dp),
            )
        }

        compose.onNodeWithTag("joker_card_QUEEN_RED")
            .assertContentDescriptionEquals("red queen joker")
            .assert(SemanticsMatcher.expectValue(CardRankLabelKey, "Q"))
            .assert(SemanticsMatcher("Joker has no suit semantics") {
                CardSuitGlyphKey !in it.config
            })
            .assert(SemanticsMatcher.expectValue(CardForegroundColorKey, red))
        listOf("♣", "♦", "♥", "♠").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test
    fun emptyCardHasNoRankOrSuitGlyph() {
        compose.setContent {
            EmptyCard(contentDescription = "no joker selected", modifier = Modifier.width(48.dp))
        }

        compose.onNodeWithTag("empty_card")
            .assertContentDescriptionEquals("no joker selected")
            .assert(SemanticsMatcher("Empty card has no rank semantics") {
                CardRankLabelKey !in it.config
            })
            .assert(SemanticsMatcher("Empty card has no suit semantics") {
                CardSuitGlyphKey !in it.config
            })
        listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K",
            "♣", "♦", "♥", "♠").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test
    fun darkThemeRedCardKeepsNormalTextContrastAgainstItsWhiteSurface() {
        compose.setContent {
            PontinhoTheme(themePreference = ThemePreference.DARK) {
                CardFace(
                    Card.Suited(Rank.ACE, Suit.HEARTS),
                    contentDescription = "ace of hearts",
                    modifier = Modifier.width(48.dp),
                )
            }
        }

        val actualArgb = compose.onNodeWithTag("card_face_ACE_HEARTS")
            .fetchSemanticsNode().config[CardForegroundColorKey]
        assertEquals(Color(0xFFB53D32).toArgb(), actualArgb)
        assertTrue(
            "Card red must meet 4.5:1 normal-text contrast on white",
            contrastRatio(Color(actualArgb), Color.White) >= 4.5,
        )
    }

    @Test
    fun cardFaceExposesOneDescriptionWithoutSemanticTextChildren() {
        compose.setContent {
            CardFace(
                Card.Suited(Rank.QUEEN, Suit.HEARTS),
                contentDescription = "queen of hearts",
                modifier = Modifier.width(48.dp),
            )
        }

        val face = compose.onNodeWithTag("card_face_QUEEN_HEARTS")
            .assertContentDescriptionEquals("queen of hearts")
            .fetchSemanticsNode()
        assertTrue("Rank and suit must not announce separately", face.children.isEmpty())
        compose.onNodeWithText("Q").assertDoesNotExist()
        compose.onNodeWithText("♥").assertDoesNotExist()
    }
}

private fun contrastRatio(first: Color, second: Color): Double {
    val lighter = maxOf(first.relativeLuminance(), second.relativeLuminance())
    val darker = minOf(first.relativeLuminance(), second.relativeLuminance())
    return (lighter + 0.05) / (darker + 0.05)
}

private fun Color.relativeLuminance(): Double =
    0.2126 * red.linearized() + 0.7152 * green.linearized() + 0.0722 * blue.linearized()

private fun Float.linearized(): Double = if (this <= 0.04045f) {
    toDouble() / 12.92
} else {
    Math.pow((toDouble() + 0.055) / 1.055, 2.4)
}
