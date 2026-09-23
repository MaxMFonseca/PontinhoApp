package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
    indicatorLabel: String,
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
        title = { Text(stringResource(R.string.score_dialog_title, playerName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.score_round_indicator, roundNumber, indicatorLabel))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        readOnly = readOnly,
                        isError = value.isNotEmpty() && parsedScore == null,
                        modifier = Modifier
                            .weight(1f)
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
                    Button(onClick = { parsedScore?.let(onConfirm) }, enabled = enabled && !readOnly && parsedScore != null) {
                        Text(stringResource(R.string.confirm))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    onCamera?.let {
                        OutlinedButton(onClick = it) { Text(stringResource(R.string.camera)) }
                    }
                    if (!isCorrection) OutlinedButton(onClick = onWentOut, enabled = enabled && wentOutEnabled) {
                        Text(stringResource(R.string.went_out))
                    }
                    if (isCorrection && onDelete != null) {
                        val deleteLabel = stringResource(R.string.score_delete)
                        OutlinedButton(onClick = onDelete, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = deleteLabel }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                            Text(deleteLabel)
                        }
                    }
                }
                if (isCorrection) Text(stringResource(R.string.score_correction_help))
                if (readOnly) Text(stringResource(R.string.score_winner_read_only))
                if (value.isNotEmpty() && parsedScore == null) Text(stringResource(R.string.score_invalid))
                if (hasSaveError) Text(stringResource(R.string.match_save_error))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) { Text(stringResource(R.string.cancel)) }
        },
    )
    LaunchedEffect(inputPlaced, readOnly) {
        if (inputPlaced && !readOnly) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else if (readOnly) keyboard?.hide()
    }
}
