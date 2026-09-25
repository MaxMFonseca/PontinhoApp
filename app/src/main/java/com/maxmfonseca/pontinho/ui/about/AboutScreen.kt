package com.maxmfonseca.pontinho.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.settings.SettingsHeader

@Composable
fun AboutScreen(versionName: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("about_root"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsHeader(R.string.route_about, onBack)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
        Text(stringResource(R.string.about_version, versionName))
        Text(stringResource(R.string.about_purpose), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.about_support), style = MaterialTheme.typography.titleMedium)
        Text(
            text = "maxmfonseca12@gmail.com",
            modifier = Modifier.fillMaxWidth().testTag("about_support_email"),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
