package com.maxmfonseca.pontinho.ui.scanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R

@Composable
internal fun RecognitionDiagnosticsPanel(state: ScannerUiState) {
    val diagnostics = state.diagnostics ?: return
    if (state.phase != ScannerPhase.Review && state.phase != ScannerPhase.Error) return
    val json = diagnostics.toJson()
    var expanded by remember(diagnostics) { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("scanner_diagnostics")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.testTag("scanner_diagnostics_toggle"),
            ) {
                Text(stringResource(R.string.scanner_diagnostics))
            }
            if (expanded) {
                Text(json, modifier = Modifier.testTag("scanner_diagnostics_json"))
                OutlinedButton(
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("recognition diagnostics", json))
                    },
                    modifier = Modifier.fillMaxWidth().testTag("scanner_diagnostics_copy"),
                ) {
                    Text(stringResource(R.string.scanner_diagnostics_copy))
                }
            }
        }
    }
}
