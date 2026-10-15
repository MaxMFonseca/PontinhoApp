package com.maxmfonseca.pontinho.ui.about

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar

@Composable
fun PrivacyLicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .testTag("privacy_licenses_root"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PontinhoTopBar(R.string.route_privacy_licenses, onBack = onBack)
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DisclosureSection(R.string.privacy_local_data_title, R.string.privacy_local_data_body)
            DisclosureSection(R.string.privacy_photos_title, R.string.privacy_photos_body)
            DisclosureSection(R.string.privacy_network_title, R.string.privacy_network_body)
            DisclosureSection(R.string.privacy_camera_title, R.string.privacy_camera_body)
            DisclosureSection(R.string.privacy_app_license_title, R.string.privacy_app_license_body)
            DisclosureSection(R.string.privacy_third_party_title, R.string.privacy_third_party_body)
            DisclosureSection(R.string.privacy_model_title, R.string.privacy_model_body)
            DisclosureSection(R.string.privacy_source_title, R.string.legal_source_code_url)
        }
    }
}

@Composable
private fun DisclosureSection(titleResource: Int, bodyResource: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(titleResource), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(bodyResource),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
