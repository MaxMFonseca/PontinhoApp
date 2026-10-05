package com.maxmfonseca.pontinho.ui.learn

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar

@Composable
fun LearnScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).testTag("learn_root"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PontinhoTopBar(R.string.route_learn, onBack = onBack)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Rule(R.string.learn_objective_title, R.string.learn_penalties)
            Rule(R.string.learn_order_title, R.string.learn_hand_rotation)
            Rule(R.string.learn_wildcard_title, R.string.learn_wildcards)
            CardExamples()
            Rule(R.string.learn_scanner_title, R.string.learn_scanner_cards)
            Rule(R.string.learn_values_title, R.string.learn_card_values)
            Rule(R.string.learn_round_title, R.string.learn_round_completion)
            Rule(R.string.learn_finish_title, R.string.learn_match_end)
            Rule(R.string.learn_late_title, R.string.learn_late_players)
            Rule(R.string.learn_corrections_title, R.string.score_correction_help)
        }
    }
}

@Composable private fun Rule(@StringRes title: Int, @StringRes text: Int) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable private fun CardExamples() {
    FlowRow(
        modifier = Modifier.testTag("learn_card_examples"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardExample(
            "A♥\uFE0E",
            R.string.learn_example_ace_hearts_description,
            MaterialTheme.colorScheme.error,
            "learn_example_ace",
        )
        CardExample(
            "2♦\uFE0E",
            R.string.learn_example_two_diamonds_description,
            MaterialTheme.colorScheme.error,
            "learn_example_two",
        )
        CardExample(
            "K♠\uFE0E",
            R.string.learn_example_king_spades_description,
            MaterialTheme.colorScheme.onSurface,
            "learn_example_king",
        )
        CardExample(
            "Q♣\uFE0E 20",
            R.string.learn_example_chosen_wildcard_description,
            MaterialTheme.colorScheme.onSurface,
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
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clearAndSetSemantics { contentDescription = spokenDescription }.testTag(tag)) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            color = color,
            modifier = Modifier.padding(12.dp).clearAndSetSemantics {},
        )
    }
}
