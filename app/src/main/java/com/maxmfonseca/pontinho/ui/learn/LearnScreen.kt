package com.maxmfonseca.pontinho.ui.learn

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R

@Composable
fun LearnScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("learn_root"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.headlineMedium) }
            Text(
                stringResource(R.string.route_learn),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Rule(R.string.learn_objective_title, R.string.learn_penalties)
        Rule(R.string.learn_order_title, R.string.learn_hand_rotation)
        Rule(R.string.learn_wildcard_title, R.string.learn_wildcards)
        CardExamples()
        Rule(R.string.learn_values_title, R.string.learn_card_values)
        Rule(R.string.learn_round_title, R.string.learn_round_completion)
        Rule(R.string.learn_finish_title, R.string.learn_match_end)
        Rule(R.string.learn_late_title, R.string.learn_late_players)
    }
}

@Composable private fun Rule(@StringRes title: Int, @StringRes text: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun CardExamples() {
    Row(
        modifier = Modifier.testTag("learn_card_examples"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardExample(
            "A♥",
            R.string.learn_example_ace_hearts_description,
            MaterialTheme.colorScheme.error,
            "learn_example_ace",
        )
        CardExample(
            "2♦",
            R.string.learn_example_two_diamonds_description,
            MaterialTheme.colorScheme.error,
            "learn_example_two",
        )
        CardExample(
            "K♠",
            R.string.learn_example_king_spades_description,
            MaterialTheme.colorScheme.onSurface,
            "learn_example_king",
        )
        CardExample(
            "★ 20",
            R.string.learn_example_printed_joker_description,
            MaterialTheme.colorScheme.primary,
            "learn_example_joker",
        )
    }
}

@Composable
private fun CardExample(
    text: String,
    @StringRes description: Int,
    color: androidx.compose.ui.graphics.Color,
    tag: String,
) {
    val spokenDescription = stringResource(description)
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = color,
        modifier = Modifier.clearAndSetSemantics { contentDescription = spokenDescription }.testTag(tag),
    )
}
