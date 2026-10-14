package com.maxmfonseca.pontinho.ui.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.recognition.RecognitionWarning
import com.maxmfonseca.pontinho.ui.components.CardFace
import com.maxmfonseca.pontinho.ui.components.shortRankLabel
import com.maxmfonseca.pontinho.ui.components.suitGlyph

data class ScannerActions(
    val onCapture: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRetake: () -> Unit = {},
    val onAdd: (Card.Suited) -> Unit = {},
    val onReplace: (String, Card.Suited) -> Unit = { _, _ -> },
    val onRemove: (String) -> Unit = {},
)

@Composable
fun ScannerScreen(
    state: ScannerUiState,
    indicator: Indicator? = null,
    recognitionAvailable: Boolean = true,
    cameraPermissionDenied: Boolean = false,
    cameraError: Boolean = false,
    captureEnabled: Boolean = true,
    actions: ScannerActions = ScannerActions(),
    recognitionExperimental: Boolean = false,
    cameraPreview: @Composable () -> Unit = {},
) {
    var editing by remember { mutableStateOf<ReviewCard?>(null) }
    var adding by remember { mutableStateOf(false) }
    val saving = state.phase == ScannerPhase.Saving || state.saved

    if (state.phase == ScannerPhase.Capture) {
        CaptureSurface(
            recognitionAvailable = recognitionAvailable,
            cameraPermissionDenied = cameraPermissionDenied,
            cameraError = cameraError,
            captureEnabled = captureEnabled && !state.awaitingInput && recognitionAvailable,
            onCapture = actions.onCapture,
            onCancel = actions.onCancel,
            cameraPreview = cameraPreview,
        )
        return
    }
    if (state.phase == ScannerPhase.Processing) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("scanner_root"),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.testTag("scanner_progress"))
        }
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(16.dp).testTag("scanner_root"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.scanner_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        if (!recognitionAvailable) {
            Notice(R.string.scanner_unavailable, "scanner_unavailable")
        } else {
            if (recognitionExperimental) Notice(R.string.scanner_experimental, "scanner_experimental")
            when (state.phase) {
                else -> {
                    if (state.phase == ScannerPhase.Error) {
                        val message = state.message ?: if (state.recovery == ScannerRecovery.Save)
                            ScannerMessage.SaveFailed else ScannerMessage.RecognitionFailed
                        Notice(message.stringResourceId(), "scanner_error")
                        Action(R.string.scanner_retry, "scanner_retry", true, actions.onRetry)
                    }
                    if (state.phase != ScannerPhase.Error || state.recovery == ScannerRecovery.Save) {
                        Text(stringResource(R.string.scanner_review_help))
                        if (state.cards.isEmpty()) Notice(R.string.scanner_empty, "scanner_empty")
                        state.cards.forEachIndexed { index, card ->
                            key(card.id) {
                                CardReview(card, index + 1, indicator, state.canEdit,
                                    onEdit = { editing = card }, onRemove = { actions.onRemove(card.id) })
                            }
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.scanner_total_label), Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium)
                                Text(state.total.toString(), style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.testTag("scanner_total")
                                        .semantics { liveRegion = LiveRegionMode.Polite })
                            }
                        }
                        if (state.canEdit) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { adding = true }, modifier = Modifier.weight(1f)
                                    .heightIn(min = 48.dp).testTag("scanner_add")) {
                                    Icon(Icons.Filled.Add, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.scanner_add))
                                }
                                OutlinedButton(onClick = actions.onRetake, modifier = Modifier.weight(1f)
                                    .heightIn(min = 48.dp).testTag("scanner_retake")) {
                                    Icon(Icons.Filled.Refresh, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.scanner_retake))
                                }
                            }
                        }
                        if (state.phase == ScannerPhase.Saving) {
                            CircularProgressIndicator(Modifier.testTag("scanner_progress"))
                            Notice(R.string.scanner_saving, "scanner_saving")
                        }
                        Button(onClick = actions.onConfirm, enabled = state.canConfirm && !saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("scanner_confirm")) {
                            Text(stringResource(R.string.scanner_confirm))
                        }
                    }
                }
            }
        }
        RecognitionDiagnosticsPanel(state)
        Action(R.string.cancel, "scanner_cancel", !saving, actions.onCancel)
    }
    if (state.canEdit && (adding || editing != null)) {
        CardEditor(editing?.card, onDismiss = { adding = false; editing = null }, onConfirm = { card ->
            editing?.let { actions.onReplace(it.id, card) } ?: actions.onAdd(card)
            adding = false; editing = null
        })
    }
}

@Composable
private fun CaptureSurface(
    recognitionAvailable: Boolean,
    cameraPermissionDenied: Boolean,
    cameraError: Boolean,
    captureEnabled: Boolean,
    onCapture: () -> Unit,
    onCancel: () -> Unit,
    cameraPreview: @Composable () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).testTag("scanner_root"),
    ) {
        Box(Modifier.fillMaxSize().testTag("scanner_camera_preview")) { cameraPreview() }
        if (!recognitionAvailable || cameraPermissionDenied || cameraError) {
            val (message, tag) = when {
                !recognitionAvailable -> R.string.scanner_unavailable to "scanner_unavailable"
                cameraPermissionDenied -> R.string.scanner_permission_denied to "scanner_permission_denied"
                else -> R.string.scanner_camera_error to "scanner_camera_error"
            }
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(message), Modifier.padding(16.dp).testTag(tag),
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
        FilledIconButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp)
                .size(48.dp).testTag("scanner_camera_cancel"),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
            ),
        ) {
            Icon(Icons.Filled.Close, stringResource(R.string.cancel))
        }
        FilledIconButton(
            onClick = onCapture,
            enabled = captureEnabled,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp)
                .size(72.dp).testTag("scanner_capture"),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_camera), stringResource(R.string.scanner_capture), Modifier.size(32.dp))
        }
    }
}

internal fun ScannerMessage.stringResourceId(): Int = when (this) {
    ScannerMessage.CaptureGuidance -> R.string.scanner_capture_help
    ScannerMessage.NoCards -> R.string.scanner_no_cards
    ScannerMessage.TooManyCards -> R.string.scanner_too_many_cards
    ScannerMessage.IncompleteCards -> R.string.scanner_incomplete_cards
    ScannerMessage.ImageTooLarge -> R.string.scanner_image_too_large
    ScannerMessage.Unavailable -> R.string.scanner_unavailable
    ScannerMessage.RecognitionFailed -> R.string.scanner_recognition_error
    ScannerMessage.SaveFailed -> R.string.scanner_save_error
    ScannerMessage.SaveInterrupted -> R.string.scanner_save_interrupted
    ScannerMessage.ImageExpired -> R.string.scanner_image_expired
}

@Composable
private fun CardReview(
    card: ReviewCard,
    position: Int,
    indicator: Indicator?,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val name = stringResource(R.string.scanner_card_name, rankName(card.card.rank), suitName(card.card.suit))
    val description = stringResource(R.string.scanner_card_description, position, name)
    val isJoker = indicator != null && card.card.rank == indicator.rank.next() && card.card.suit.color == indicator.color
    Card(Modifier.fillMaxWidth().testTag("scanner_card_${card.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("scanner_card_description_${card.id}")) {
                val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f
                val identity: @Composable RowScope.() -> Unit = {
                    CardFace(card.card, description, Modifier.width(52.dp))
                    if (isJoker) Surface(color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.testTag("scanner_joker_badge_${card.id}")) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.scanner_joker), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                val controls: @Composable RowScope.() -> Unit = {
                    val editDescription = stringResource(R.string.scanner_edit_description, position, name)
                    IconButton(onClick = onEdit, modifier = Modifier.size(48.dp).testTag("scanner_edit_${card.id}")
                        .semantics { contentDescription = editDescription }) {
                        Icon(Icons.Filled.Edit, null)
                    }
                    val removeDescription = stringResource(R.string.scanner_remove_description, position, name)
                    IconButton(onClick = onRemove, modifier = Modifier.size(48.dp).testTag("scanner_remove_${card.id}")
                        .semantics { contentDescription = removeDescription }) {
                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
                if (stacked) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp), content = identity)
                        if (canEdit) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically, content = controls)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        identity()
                        Spacer(Modifier.weight(1f))
                        if (canEdit) controls()
                    }
                }
            }
            if (card.warnings.isNotEmpty()) {
                Notice(if (RecognitionWarning.LOW_CONFIDENCE in card.warnings) R.string.scanner_low_confidence
                    else R.string.scanner_check_card, "scanner_warning_${card.id}")
            }
            if (card.correctedByUser) Text(stringResource(R.string.scanner_corrected))
        }
    }
}

@Composable
private fun Action(label: Int, tag: String, enabled: Boolean, onClick: () -> Unit, description: String? = null) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .semantics { if (description != null) contentDescription = description }) {
        Text(stringResource(label))
    }
}

@Composable
private fun Notice(label: Int, tag: String) {
    Text(stringResource(label), modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardEditor(initial: Card.Suited?, onDismiss: () -> Unit, onConfirm: (Card.Suited) -> Unit) {
    var rank by remember(initial) { mutableStateOf(initial?.rank) }
    var suit by remember(initial) { mutableStateOf(initial?.suit) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (initial == null) R.string.scanner_add else R.string.scanner_edit),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.scanner_rank))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Rank.entries.forEach { option ->
                        val name = rankName(option)
                        FilterChip(selected = rank == option, onClick = { rank = option },
                            label = { Text(shortRankLabel(option)) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("scanner_rank_${option.name}").semantics { contentDescription = name })
                    }
                }
                Text(stringResource(R.string.scanner_suit))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Suit.entries.forEach { option ->
                        val name = suitName(option)
                        FilterChip(selected = suit == option, onClick = { suit = option },
                            label = {
                                Text(
                                    suitGlyph(option),
                                    color = if (option.color == CardColor.RED) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("scanner_suit_${option.name}")
                                .semantics { contentDescription = name })
                    }
                }
                Action(R.string.confirm, "scanner_editor_confirm", rank != null && suit != null,
                    { onConfirm(Card.Suited(requireNotNull(rank), requireNotNull(suit))) })
                Action(R.string.cancel, "scanner_editor_cancel", true, onDismiss)
            }
        }
    }
}

@Composable
private fun rankName(rank: Rank): String = when (rank) {
    Rank.ACE -> stringResource(R.string.scanner_ace)
    Rank.JACK -> stringResource(R.string.scanner_jack)
    Rank.QUEEN -> stringResource(R.string.scanner_queen)
    Rank.KING -> stringResource(R.string.scanner_king)
    else -> rank.canonicalValue.toString()
}

@Composable
private fun suitName(suit: Suit): String = stringResource(when (suit) {
    Suit.CLUBS -> R.string.scanner_clubs
    Suit.DIAMONDS -> R.string.scanner_diamonds
    Suit.HEARTS -> R.string.scanner_hearts
    Suit.SPADES -> R.string.scanner_spades
})
