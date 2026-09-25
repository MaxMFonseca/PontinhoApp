package com.maxmfonseca.pontinho.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
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
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_root"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                )
                val settingsDescription = stringResource(R.string.home_settings)
                TextButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = settingsDescription }) {
                    Text(
                        "⚙",
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
        item {
            Button(
                onClick = onNewMatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text("＋", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
                Text(stringResource(R.string.home_new_match), style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            OutlinedButton(
                onClick = onLearn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text("▤", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
                Text(stringResource(R.string.home_learn), style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Text(
                text = stringResource(R.string.home_matches_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        if (!state.isLoading && state.dateCards.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("♣ ♥", style = MaterialTheme.typography.displayMedium, modifier = Modifier.clearAndSetSemantics { })
                    Text(
                        text = stringResource(R.string.home_empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.home_empty_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onNewMatch) { Text(stringResource(R.string.home_create_match)) }
                }
            }
        } else {
            itemsIndexed(state.dateCards, key = { _, card -> card.localDate.toString() }) { index, card ->
                HomeDayCard(
                    card = card,
                    onClick = { onOpenDay(card.localDate) },
                    modifier = Modifier.testTag("home_day_$index"),
                )
            }
        }
    }
}

@Composable
private fun HomeDayCard(
    card: HomeDateCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("▦", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.clearAndSetSemantics { })
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (card.localDate == LocalDate.now()) {
                        stringResource(R.string.home_today)
                    } else {
                        card.localDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    pluralStringResource(R.plurals.home_match_count, card.matchCount, card.matchCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (card.playerNames.isNotEmpty()) Text(card.playerNames.joinToString(" · "))
                if (card.activeMatchCount > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.home_active_match_count,
                            card.activeMatchCount,
                            card.activeMatchCount,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text("›", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}
