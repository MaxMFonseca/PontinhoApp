package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var inputPlaced by remember { mutableStateOf(false) }
    val parsedScore = value.toIntOrNull()?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.score_dialog_title, playerName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.score_round_indicator, roundNumber, indicatorLabel))
                OutlinedTextField(
                    value = value,
                    onValueChange = { candidate ->
                        if (candidate.all(Char::isDigit)) onValueChange(candidate)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    onCamera?.let {
                        OutlinedButton(onClick = it) { Text(stringResource(R.string.camera)) }
                    }
                    OutlinedButton(onClick = onWentOut, enabled = wentOutEnabled) {
                        Text(stringResource(R.string.went_out))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(requireNotNull(parsedScore)) }, enabled = parsedScore != null) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    LaunchedEffect(inputPlaced) {
        if (inputPlaced) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}
