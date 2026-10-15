package com.maxmfonseca.pontinho.ui.learn

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar

private data class RuleContent(@StringRes val title: Int, @StringRes val body: Int)

private val rules = listOf(
    RuleContent(R.string.learn_rule_01_title, R.string.learn_rule_01_body),
    RuleContent(R.string.learn_rule_02_title, R.string.learn_rule_02_body),
    RuleContent(R.string.learn_rule_03_title, R.string.learn_rule_03_body),
    RuleContent(R.string.learn_rule_04_title, R.string.learn_rule_04_body),
    RuleContent(R.string.learn_rule_05_title, R.string.learn_rule_05_body),
    RuleContent(R.string.learn_rule_06_title, R.string.learn_rule_06_body),
    RuleContent(R.string.learn_rule_07_title, R.string.learn_rule_07_body),
    RuleContent(R.string.learn_rule_08_title, R.string.learn_rule_08_body),
    RuleContent(R.string.learn_rule_09_title, R.string.learn_rule_09_body),
    RuleContent(R.string.learn_rule_10_title, R.string.learn_rule_10_body),
    RuleContent(R.string.learn_rule_11_title, R.string.learn_rule_11_body),
    RuleContent(R.string.learn_rule_12_title, R.string.learn_rule_12_body),
    RuleContent(R.string.learn_rule_13_title, R.string.learn_rule_13_body),
    RuleContent(R.string.learn_rule_14_title, R.string.learn_rule_14_body),
    RuleContent(R.string.learn_rule_15_title, R.string.learn_rule_15_body),
    RuleContent(R.string.learn_rule_16_title, R.string.learn_rule_16_body),
    RuleContent(R.string.learn_rule_17_title, R.string.learn_rule_17_body),
    RuleContent(R.string.learn_rule_18_title, R.string.learn_rule_18_body),
    RuleContent(R.string.learn_rule_19_title, R.string.learn_rule_19_body),
    RuleContent(R.string.learn_rule_20_title, R.string.learn_rule_20_body),
)

@Composable
fun LearnScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .testTag("learn_root"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PontinhoTopBar(R.string.route_learn, onBack = onBack)
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.learn_rules_heading),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            rules.forEachIndexed { index, rule ->
                RuleSection(rule = rule, number = index + 1)
            }
        }
    }
}

@Composable
private fun RuleSection(rule: RuleContent, number: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("learn_rule_section"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(rule.title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SuitColoredText(stringResource(rule.body))
            when (number) {
                18 -> {
                    ScoreTable()
                    SuitColoredText(stringResource(R.string.learn_rule_18_after_table))
                }
                20 -> {
                    FinalScoreTable()
                    SuitColoredText(stringResource(R.string.learn_rule_20_after_table))
                }
            }
        }
    }
}

@Composable
private fun SuitColoredText(text: String) {
    val red = MaterialTheme.colorScheme.error
    val annotated = buildAnnotatedString {
        text.forEach { character ->
            if (character == '♥' || character == '♦') {
                withStyle(SpanStyle(color = red)) { append(character) }
            } else {
                append(character)
            }
        }
    }
    Text(text = annotated, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun ScoreTable() {
    RulesTable(
        headers = R.string.learn_score_table_headers,
        rows = listOf(
            R.string.learn_score_table_number_cards,
            R.string.learn_score_table_jack,
            R.string.learn_score_table_queen,
            R.string.learn_score_table_king,
            R.string.learn_score_table_ace,
            R.string.learn_score_table_wildcard,
        ),
        modifier = Modifier.testTag("learn_score_table"),
    )
}

@Composable
private fun FinalScoreTable() {
    RulesTable(
        headers = R.string.learn_final_table_headers,
        rows = listOf(
            R.string.learn_final_table_joao,
            R.string.learn_final_table_maria,
            R.string.learn_final_table_pedro,
            R.string.learn_final_table_ana,
        ),
    )
}

@Composable
private fun RulesTable(
    @StringRes headers: Int,
    rows: List<Int>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TableRow(stringResource(headers), MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        rows.forEach { row -> TableRow(stringResource(row), MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun TableRow(text: String, color: Color) {
    val cells = text.split('|', limit = 2)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(cells.first(), modifier = Modifier.weight(1f), color = color)
        Text(cells.getOrElse(1) { "" }, color = color)
    }
}
