package com.maxmfonseca.pontinho.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.settings.AppPreferences
import com.maxmfonseca.pontinho.settings.LanguagePreference
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.components.PontinhoSettingCard
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    onLanguage: () -> Unit,
    onLearn: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPage(R.string.route_settings, "settings_root", onBack, modifier) {
        PontinhoSettingCard(R.string.route_appearance, subtitle = R.string.settings_appearance_summary,
            icon = Icons.Filled.Settings, onClick = onAppearance)
        PontinhoSettingCard(R.string.route_language, subtitle = R.string.settings_language_summary,
            icon = Icons.Filled.Menu, onClick = onLanguage)
        PontinhoSettingCard(R.string.route_learn, subtitle = R.string.settings_learn_summary,
            icon = ImageVector.vectorResource(R.drawable.ic_book), onClick = onLearn)
        PontinhoSettingCard(R.string.route_about, subtitle = R.string.settings_about_summary,
            icon = Icons.Filled.Info, onClick = onAbout)
    }
}

@Composable
fun AppearanceScreen(
    preferences: AppPreferences,
    onBack: () -> Unit,
    onThemeSelected: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPage(R.string.route_appearance, "appearance_root", onBack, modifier) {
        Text(stringResource(R.string.appearance_theme_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceCard(R.string.appearance_theme_system, preferences.theme == ThemePreference.SYSTEM) { onThemeSelected(ThemePreference.SYSTEM) }
            ChoiceCard(R.string.appearance_theme_light, preferences.theme == ThemePreference.LIGHT) { onThemeSelected(ThemePreference.LIGHT) }
            ChoiceCard(R.string.appearance_theme_dark, preferences.theme == ThemePreference.DARK) { onThemeSelected(ThemePreference.DARK) }
        }
    }
}

@Composable
fun LanguageScreen(
    selected: LanguagePreference,
    onBack: () -> Unit,
    onLanguageSelected: (LanguagePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPage(R.string.route_language, "language_root", onBack, modifier) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceCard(R.string.language_device, selected == LanguagePreference.SYSTEM, "language_system") {
                onLanguageSelected(LanguagePreference.SYSTEM)
            }
            ChoiceCard(R.string.language_portuguese, selected == LanguagePreference.PORTUGUESE_BRAZIL) {
                onLanguageSelected(LanguagePreference.PORTUGUESE_BRAZIL)
            }
            ChoiceCard(R.string.language_english, selected == LanguagePreference.ENGLISH_UNITED_STATES) {
                onLanguageSelected(LanguagePreference.ENGLISH_UNITED_STATES)
            }
        }
        Text(stringResource(R.string.language_fallback), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsPage(@StringRes title: Int, tag: String, onBack: () -> Unit, modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PontinhoTopBar(title, onBack = onBack)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun ChoiceCard(@StringRes label: Int, selected: Boolean, tag: String? = null, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth()
        .then(if (tag == null) Modifier else Modifier.testTag(tag))
        .clip(MaterialTheme.shapes.large)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.sizeIn(minHeight = 64.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected, onClick = null)
            Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
    }
}
