package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.maxmfonseca.pontinho.R

@Composable
fun PlayerNameDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    supportingText: String? = null,
    enabled: Boolean = true,
    confirmEnabled: Boolean = true,
    duplicateNameError: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var inputPlaced by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(stringResource(R.string.player_name_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onGloballyPositioned { inputPlaced = true }
                        .testTag("player_name_input"),
                    label = { Text(stringResource(R.string.player_name_label)) },
                    singleLine = true,
                    isError = duplicateNameError,
                    supportingText = if (duplicateNameError) {
                        { Text(stringResource(R.string.player_name_duplicate_error)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                supportingText?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = enabled && confirmEnabled) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) { Text(stringResource(R.string.cancel)) }
        },
    )
    LaunchedEffect(inputPlaced) {
        if (inputPlaced) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}
