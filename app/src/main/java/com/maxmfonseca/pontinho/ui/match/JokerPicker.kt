package com.maxmfonseca.pontinho.ui.match

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.ui.components.shortRankLabel
import com.maxmfonseca.pontinho.ui.components.JokerCard

@Composable
fun JokerPicker(
    selectedRank: Rank?, selectedColor: CardColor?, isSaving: Boolean, hasError: Boolean,
    onSelectRank: (Rank) -> Unit, onSelectColor: (CardColor) -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
    canRemove: Boolean = false, onRemove: () -> Unit = {},
) {
    val joker = selectedRank?.let { rank -> selectedColor?.let { rank to it } }
    Dialog(onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // A short window needs the available width: two rank rows and one row of controls.
            val shortWindow = maxHeight < 480.dp && maxWidth >= 480.dp
            val largeFont = LocalDensity.current.fontScale >= 1.5f
            val compactSpacing = shortWindow || (maxHeight < 600.dp && largeFont)
            val previewWidth = jokerCardWidth()
            val title: @Composable (Modifier) -> Unit = { modifier ->
                Text(stringResource(R.string.indicator_title), style = MaterialTheme.typography.titleSmall,
                    modifier = modifier.semantics { heading() })
            }
            val preview: @Composable (Modifier) -> Unit = { modifier ->
                val help = stringResource(R.string.indicator_select_help)
                val description = joker?.let { stringResource(R.string.joker_preview_card, shortRankLabel(it.first), colorLabel(it.second)) } ?: help
                Box(modifier.testTag("joker_preview").semantics(mergeDescendants = true) {},
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (joker == null) Text(help, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = description })
                    else JokerCard(joker.first, joker.second, description, Modifier.width(previewWidth))
                }
            }
            val colors: @Composable RowScope.() -> Unit = {
                CardColor.entries.forEach { color ->
                    PickerOption(colorLabel(color), selectedColor == color, !isSaving,
                        { onSelectColor(color) }, Modifier.weight(1f).testTag("indicator_color_${color.name}"))
                }
            }
            val cancelAction: @Composable (Modifier) -> Unit = { modifier ->
                TextButton(onClick = onDismiss, enabled = !isSaving,
                    modifier = modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            }
            val confirmAction: @Composable (Modifier) -> Unit = { modifier ->
                Button(onClick = onConfirm, enabled = joker != null && !isSaving,
                    modifier = modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp)) {
                    Text(stringResource(R.string.confirm))
                }
            }
            val removeAction: @Composable (Modifier) -> Unit = { modifier ->
                if (canRemove) TextButton(onClick = onRemove, enabled = !isSaving,
                    modifier = modifier.heightIn(min = 48.dp).testTag("joker_remove"), contentPadding = PaddingValues(4.dp)) {
                    Text(stringResource(R.string.joker_remove))
                }
            }
            Surface(modifier = Modifier.widthIn(max = if (shortWindow) 640.dp else 400.dp).fillMaxWidth()
                .testTag("joker_dialog"), shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
              Column(Modifier.padding(if (compactSpacing) 8.dp else 16.dp),
                  verticalArrangement = Arrangement.spacedBy(if (compactSpacing) 4.dp else 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    title(Modifier.weight(1f))
                    if (!shortWindow || joker == null) preview(if (joker == null) Modifier.weight(1f) else Modifier)
                    if (shortWindow && canRemove) removeAction(Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                  Column(Modifier.weight(1f).testTag("joker_options"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val columns = if (shortWindow) 7 else 5
                    Rank.entries.chunked(columns).forEach { ranks ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ranks.forEach { rank ->
                                PickerOption(shortRankLabel(rank), selectedRank == rank, !isSaving,
                                    { onSelectRank(rank) }, Modifier.weight(1f).testTag("indicator_rank_${rank.name}"))
                            }
                            repeat(columns - ranks.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                  }
                  if (shortWindow && joker != null) preview(Modifier)
                }
                if (shortWindow) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        colors()
                        cancelAction(Modifier.weight(1.2f))
                        confirmAction(Modifier.weight(1.2f))
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = colors)
                }
                if (hasError) Text(stringResource(R.string.match_save_error), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (!shortWindow) {
                    removeAction(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cancelAction(Modifier.weight(1f))
                        confirmAction(Modifier.weight(1.2f))
                    }
                }
              }
            }
        }
    }
}

@Composable private fun PickerOption(label: String, selected: Boolean, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = 48.dp).semantics {
            this.selected = selected
            role = Role.RadioButton
            contentDescription = label
        }) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable internal fun colorLabel(color: CardColor) = stringResource(
    if (color == CardColor.RED) R.string.indicator_red else R.string.indicator_black)

@Composable internal fun wildcardPreview(indicator: Indicator): String {
    val rank = shortRankLabel(indicator.rank.next())
    return jokerPreview(rank, indicator.color)
}

@Composable private fun jokerPreview(rank: String, color: CardColor): String = stringResource(
    R.string.indicator_preview,
    if (color == CardColor.RED) "$rank♥\uFE0E" else "$rank♠\uFE0E",
    if (color == CardColor.RED) "$rank♦\uFE0E" else "$rank♣\uFE0E",
)
