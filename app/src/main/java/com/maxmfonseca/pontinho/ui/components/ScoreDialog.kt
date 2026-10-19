package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R

@Composable
fun ScoreDialog(
    playerName: String,
    roundNumber: Int,
    indicatorLabel: String?,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    onWentOut: () -> Unit,
    onCamera: (() -> Unit)? = null,
    wentOutEnabled: Boolean = true,
    enabled: Boolean = true,
    hasSaveError: Boolean = false,
    isCorrection: Boolean = false,
    readOnly: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var inputPlaced by remember { mutableStateOf(false) }
    val parsedScore = value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toIntOrNull()
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(stringResource(R.string.score_dialog_title, playerName), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (indicatorLabel == null) stringResource(R.string.score_round, roundNumber)
                    else stringResource(R.string.score_round_indicator, roundNumber, indicatorLabel))
                val input: @Composable (Modifier) -> Unit = { modifier ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        readOnly = readOnly,
                        isError = value.isNotEmpty() && parsedScore == null,
                        modifier = modifier
                            .focusRequester(focusRequester)
                            .onGloballyPositioned { inputPlaced = true }
                            .testTag("score_input"),
                        label = { Text(stringResource(R.string.score_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }
                val confirm: @Composable (Modifier) -> Unit = { modifier ->
                    Button(onClick = { parsedScore?.let(onConfirm) }, enabled = enabled && !readOnly && parsedScore != null,
                        modifier = modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.confirm))
                    }
                }
                input(Modifier.fillMaxWidth())
                confirm(Modifier.fillMaxWidth())
                onCamera?.takeUnless { readOnly }?.let {
                    OutlinedButton(onClick = it, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("score_camera")) {
                        Icon(painterResource(R.drawable.ic_camera), contentDescription = null)
                        Text(stringResource(R.string.camera))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!isCorrection) OutlinedButton(onClick = onWentOut, enabled = enabled && wentOutEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.went_out))
                    }
                    if (isCorrection && onDelete != null) {
                        val deleteLabel = stringResource(R.string.score_delete)
                        OutlinedButton(onClick = onDelete, enabled = enabled,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { contentDescription = deleteLabel }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                            Text(deleteLabel)
                        }
                    }
                    TextButton(onClick = onDismiss, enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                if (isCorrection) Text(stringResource(R.string.score_correction_help))
                if (readOnly) Text(stringResource(R.string.score_winner_read_only))
                if (value.isNotEmpty() && parsedScore == null) Text(stringResource(R.string.score_invalid), color = MaterialTheme.colorScheme.error)
                if (hasSaveError) Text(stringResource(R.string.match_save_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
    LaunchedEffect(inputPlaced, readOnly) {
        if (inputPlaced && !readOnly) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else if (readOnly) keyboard?.hide()
    }
}
