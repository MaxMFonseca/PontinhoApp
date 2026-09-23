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
        Rule(R.string.learn_penalties)
        Rule(R.string.learn_round_completion)
        Rule(R.string.learn_match_end)
        Rule(R.string.learn_hand_rotation)
        Rule(R.string.learn_wildcards)
        Rule(R.string.learn_card_values)
        Rule(R.string.learn_late_players)
    }
}

@Composable private fun Rule(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}
