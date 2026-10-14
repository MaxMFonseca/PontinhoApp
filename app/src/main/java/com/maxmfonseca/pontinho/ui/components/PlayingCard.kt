package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.domain.model.Card
import com.maxmfonseca.pontinho.domain.model.CardColor
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.domain.model.Suit
import com.maxmfonseca.pontinho.ui.theme.CoralStrong

internal val CardForegroundColorKey = SemanticsPropertyKey<Int>("CardForegroundColor")
internal val CardRankLabelKey = SemanticsPropertyKey<String>("CardRankLabel")
internal val CardSuitGlyphKey = SemanticsPropertyKey<String>("CardSuitGlyph")
private var SemanticsPropertyReceiver.cardForegroundColor by CardForegroundColorKey
private var SemanticsPropertyReceiver.cardRankLabel by CardRankLabelKey
private var SemanticsPropertyReceiver.cardSuitGlyph by CardSuitGlyphKey

@Composable
fun CardFace(
    card: Card.Suited,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val tag = "card_face_${card.rank.name}_${card.suit.name}"
    val foreground = cardForeground(card.suit.color)
    val rankLabel = shortRankLabel(card.rank)
    val suitGlyph = suitGlyph(card.suit)
    PlayingCardSurface(tag, contentDescription, foreground, rankLabel, suitGlyph, modifier) {
        Text(
            text = rankLabel,
            color = foreground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.TopStart).testTag("${tag}_rank"),
        )
        Text(
            text = suitGlyph,
            color = foreground,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.Center).testTag("${tag}_suit"),
        )
    }
}

@Composable
fun JokerCard(
    rank: Rank,
    color: CardColor,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val tag = "joker_card_${rank.name}_${color.name}"
    val foreground = cardForeground(color)
    val rankLabel = shortRankLabel(rank)
    PlayingCardSurface(tag, contentDescription, foreground, rankLabel, null, modifier) {
        Text(
            text = rankLabel,
            color = foreground,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.Center).testTag("${tag}_rank"),
        )
    }
}

@Composable
fun EmptyCard(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    PlayingCardSurface("empty_card", contentDescription, Color.Black, "?", null, modifier) {
        Text(
            text = "?",
            color = Color.Black,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.Center).testTag("empty_card_question"),
        )
    }
}

fun shortRankLabel(rank: Rank): String = when (rank) {
    Rank.ACE -> "A"
    Rank.JACK -> "J"
    Rank.QUEEN -> "Q"
    Rank.KING -> "K"
    else -> rank.canonicalValue.toString()
}

@Composable
private fun PlayingCardSurface(
    tag: String,
    contentDescription: String,
    foreground: Color?,
    rankLabel: String?,
    suitGlyph: String?,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .aspectRatio(5f / 7f)
            .testTag(tag)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                foreground?.let { cardForegroundColor = it.toArgb() }
                rankLabel?.let { cardRankLabel = it }
                suitGlyph?.let { cardSuitGlyph = it }
            },
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(Modifier.fillMaxSize().padding(6.dp), content = content)
    }
}

@Composable
private fun cardForeground(color: CardColor): Color = when (color) {
    CardColor.RED -> CoralStrong
    CardColor.BLACK -> MaterialTheme.colorScheme.onPrimaryFixed
}

fun suitGlyph(suit: Suit): String = when (suit) {
    Suit.CLUBS -> "♣"
    Suit.DIAMONDS -> "♦"
    Suit.HEARTS -> "♥"
    Suit.SPADES -> "♠"
}
