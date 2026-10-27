package com.maxmfonseca.pontinho.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar

@Composable
fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
    onLearn: () -> Unit,
    experimentalCardScanningEnabled: Boolean,
    onExperimentalCardScanningEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onPrivacyLicenses: () -> Unit = {},
) {
    var showCameraEducation by rememberSaveable { mutableStateOf(false) }
    if (showCameraEducation) {
        CameraEducationScreen(
            experimentalCardScanningEnabled = experimentalCardScanningEnabled,
            onExperimentalCardScanningEnabledChange = onExperimentalCardScanningEnabledChange,
            onBack = { showCameraEducation = false },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).testTag("about_root"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PontinhoTopBar(R.string.route_about, onBack = onBack)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(80.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.about_version, versionName), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val purpose = stringResource(R.string.about_purpose)
                    val learnPhrase = stringResource(R.string.route_learn)
                    Text(buildAnnotatedString {
                        append(purpose)
                        val linkStart = purpose.lastIndexOf(learnPhrase)
                        if (linkStart >= 0) {
                            addLink(LinkAnnotation.Clickable(
                                tag = "learn",
                                styles = TextLinkStyles(SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                )),
                                linkInteractionListener = { onLearn() },
                            ), linkStart, linkStart + learnPhrase.length)
                        }
                    }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            TextButton(
                onClick = onPrivacyLicenses,
                modifier = Modifier.fillMaxWidth().testTag("about_privacy_licenses"),
            ) {
                Text(stringResource(R.string.about_privacy_licenses))
            }
            TextButton(
                onClick = { showCameraEducation = true },
                modifier = Modifier.fillMaxWidth().testTag("about_camera_education"),
            ) {
                Text(stringResource(R.string.about_camera_education))
            }
            if (booleanResource(R.bool.allow_experimental_card_model)) {
                Text(
                    stringResource(R.string.about_card_model_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.about_support), style = MaterialTheme.typography.titleMedium)
            Text(
                text = "maxmfonseca12@gmail.com",
                modifier = Modifier.fillMaxWidth().testTag("about_support_email"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun CameraEducationScreen(
    experimentalCardScanningEnabled: Boolean,
    onExperimentalCardScanningEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .testTag("camera_education_root"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PontinhoTopBar(R.string.camera_education_title, onBack = onBack)
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EducationCard(R.string.camera_education_offline)
            EducationCard(R.string.camera_education_capture)
            EducationCard(R.string.camera_education_review)
            EducationCard(R.string.camera_education_manual)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = stringResource(R.string.camera_education_experimental),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.camera_education_enable), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.camera_education_enable_supporting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = experimentalCardScanningEnabled,
                        onCheckedChange = onExperimentalCardScanningEnabledChange,
                        modifier = Modifier.testTag("camera_experimental_toggle"),
                    )
                }
            }
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().testTag("camera_education_done"),
            ) {
                Text(stringResource(R.string.camera_education_done))
            }
        }
    }
}

@Composable
private fun EducationCard(textResource: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = stringResource(textResource),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
