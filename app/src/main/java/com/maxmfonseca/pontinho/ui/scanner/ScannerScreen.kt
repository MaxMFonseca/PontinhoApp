package com.maxmfonseca.pontinho.ui.scanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.recognition.RecognitionWarning
import com.maxmfonseca.pontinho.ui.match.rankLabel

data class ScannerActions(
    val onCapture: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onManual: () -> Unit = {},
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
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(16.dp).testTag("scanner_root"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.scanner_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        if (!recognitionAvailable) {
            Notice(R.string.scanner_unavailable, "scanner_unavailable")
        } else {
            if (recognitionExperimental) Notice(R.string.scanner_experimental, "scanner_experimental")
            when (state.phase) {
                ScannerPhase.Capture -> {
                    Notice(ScannerMessage.CaptureGuidance.stringResourceId(), "scanner_capture_help")
                    if (cameraPermissionDenied) Notice(R.string.scanner_permission_denied, "scanner_permission_denied")
                    if (cameraError) Notice(R.string.scanner_camera_error, "scanner_camera_error")
                    state.message?.let { Notice(it.stringResourceId(), "scanner_image_expired") }
                    cameraPreview()
                    Action(R.string.scanner_capture, "scanner_capture", captureEnabled && !state.awaitingInput, actions.onCapture)
                    Action(R.string.scanner_import, "scanner_import", !state.awaitingInput, actions.onImport)
                }
                ScannerPhase.Processing -> {
                    CircularProgressIndicator(Modifier.testTag("scanner_progress"))
                    Notice(R.string.scanner_processing, "scanner_processing")
                }
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
                                CardReview(card, index + 1, state.canEdit,
                                    onEdit = { editing = card }, onRemove = { actions.onRemove(card.id) })
                            }
                        }
                        Text(stringResource(R.string.scanner_total, state.total),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.testTag("scanner_total").semantics { liveRegion = LiveRegionMode.Polite })
                        if (state.canEdit) {
                            Action(R.string.scanner_add, "scanner_add", true, { adding = true })
                            Action(R.string.scanner_retake, "scanner_retake", true, actions.onRetake)
                        }
                        if (state.phase == ScannerPhase.Saving) {
                            CircularProgressIndicator(Modifier.testTag("scanner_progress"))
                            Notice(R.string.scanner_saving, "scanner_saving")
                        }
                        Action(R.string.scanner_confirm, "scanner_confirm", state.canConfirm && !saving, actions.onConfirm)
                    }
                }
            }
        }
        RecognitionDiagnosticsPanel(state)
        Action(R.string.scanner_manual, "scanner_manual", !saving, actions.onManual)
        Action(R.string.cancel, "scanner_cancel", !saving, actions.onCancel)
    }
    if (state.canEdit && (adding || editing != null)) {
        CardEditor(editing?.card, onDismiss = { adding = false; editing = null }, onConfirm = { card ->
            editing?.let { actions.onReplace(it.id, card) } ?: actions.onAdd(card)
            adding = false; editing = null
        })
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
private fun CardReview(card: ReviewCard, position: Int, canEdit: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    val name = stringResource(R.string.scanner_card_name, rankName(card.card.rank), suitName(card.card.suit))
    val description = stringResource(R.string.scanner_card_description, position, name)
    OutlinedCard(Modifier.fillMaxWidth().testTag("scanner_card_${card.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$position. $name", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("scanner_card_description_${card.id}")
                    .clearAndSetSemantics { contentDescription = description })
            if (card.warnings.isNotEmpty()) {
                Notice(if (RecognitionWarning.LOW_CONFIDENCE in card.warnings) R.string.scanner_low_confidence
                    else R.string.scanner_check_card, "scanner_warning_${card.id}")
            }
            if (card.correctedByUser) Text(stringResource(R.string.scanner_corrected))
            if (canEdit) {
                Action(R.string.scanner_edit, "scanner_edit_${card.id}", true, onEdit,
                    stringResource(R.string.scanner_edit_description, position, name))
                Action(R.string.scanner_remove, "scanner_remove_${card.id}", true, onRemove,
                    stringResource(R.string.scanner_remove_description, position, name))
            }
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
                            label = { Text(rankLabel(option)) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("scanner_rank_${option.name}").semantics { contentDescription = name })
                    }
                }
                Text(stringResource(R.string.scanner_suit))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Suit.entries.forEach { option ->
                        FilterChip(selected = suit == option, onClick = { suit = option },
                            label = { Text(suitName(option)) }, modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("scanner_suit_${option.name}"))
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
