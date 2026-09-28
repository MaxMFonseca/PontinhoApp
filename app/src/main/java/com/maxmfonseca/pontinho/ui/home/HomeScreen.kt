package com.maxmfonseca.pontinho.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun HomeScreen(
    state: HomeUiState,
    onNewMatch: () -> Unit,
    onLearn: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit = {},
) {
    LazyColumn(modifier = modifier.fillMaxSize().safeDrawingPadding().testTag("home_root"),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PontinhoTopBar(R.string.app_name, onSettings = onSettings) }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onNewMatch, shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp).testTag("home_new_match")) {
                    Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.home_new_match), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("home_new_match_label"))
                }
                TextButton(onClick = onLearn,
                    modifier = Modifier.align(Alignment.CenterHorizontally).sizeIn(minHeight = 48.dp).testTag("home_learn")) {
                    Icon(Icons.AutoMirrored.Filled.List, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.home_learn), modifier = Modifier.testTag("home_learn_label"))
                }
            }
        }
        item {
            Text(stringResource(R.string.home_matches_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() })
        }
        if (!state.isLoading && state.dateCards.isEmpty()) {
            item {
                Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EmptyCardsIllustration()
                        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.home_empty_body), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            itemsIndexed(state.dateCards, key = { _, card -> card.localDate.toString() }) { index, card ->
                HomeDayCard(card, { onOpenDay(card.localDate) }, Modifier.padding(horizontal = 16.dp).testTag("home_day_$index"))
            }
        }
    }
}

@Composable
private fun EmptyCardsIllustration() {
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(64.dp, 56.dp).clearAndSetSemantics {}) {
        val card = Size(34.dp.toPx(), 44.dp.toPx())
        val corner = CornerRadius(6.dp.toPx())
        drawRoundRect(surface, Offset(4.dp.toPx(), 2.dp.toPx()), card, corner)
        drawRoundRect(outline, Offset(20.dp.toPx(), 10.dp.toPx()), card, corner, style = Stroke(1.dp.toPx()))
        drawCircle(outline, 3.dp.toPx(), Offset(37.dp.toPx(), 32.dp.toPx()))
    }
}

@Composable
private fun HomeDayCard(card: HomeDateCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DateRange, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (card.localDate == LocalDate.now()) stringResource(R.string.home_today)
                        else card.localDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                        style = MaterialTheme.typography.titleMedium)
                }
                if (card.playerNames.isNotEmpty()) Text(card.playerNames.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                Text(pluralStringResource(R.plurals.home_match_count, card.matchCount, card.matchCount),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (card.activeMatchCount > 0) Text(
                    pluralStringResource(R.plurals.home_active_match_count, card.activeMatchCount, card.activeMatchCount),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(24.dp))
        }
    }
}
