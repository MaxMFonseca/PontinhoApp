package com.maxmfonseca.pontinho.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.settings.AppPreferences
import com.maxmfonseca.pontinho.settings.LanguagePreference
import com.maxmfonseca.pontinho.settings.ThemePreference

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    onLanguage: () -> Unit,
    onLearn: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("settings_root"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SettingsHeader(R.string.route_settings, onBack) }
        item { SettingsDestination(R.string.route_appearance, R.string.settings_appearance_summary, onAppearance) }
        item { SettingsDestination(R.string.route_language, R.string.settings_language_summary, onLanguage) }
        item { SettingsDestination(R.string.route_learn, R.string.settings_learn_summary, onLearn) }
        item { SettingsDestination(R.string.route_about, R.string.settings_about_summary, onAbout) }
    }
}

@Composable
fun AppearanceScreen(
    preferences: AppPreferences,
    onBack: () -> Unit,
    onThemeSelected: (ThemePreference) -> Unit,
    onReducedMotionSelected: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("appearance_root").selectableGroup(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SettingsHeader(R.string.route_appearance, onBack) }
        item { SectionTitle(R.string.appearance_theme_title) }
        item { ChoiceRow(R.string.appearance_theme_system, preferences.theme == ThemePreference.SYSTEM) { onThemeSelected(ThemePreference.SYSTEM) } }
        item { ChoiceRow(R.string.appearance_theme_light, preferences.theme == ThemePreference.LIGHT) { onThemeSelected(ThemePreference.LIGHT) } }
        item { ChoiceRow(R.string.appearance_theme_dark, preferences.theme == ThemePreference.DARK) { onThemeSelected(ThemePreference.DARK) } }
        item { SectionTitle(R.string.appearance_motion_title) }
        item { ChoiceRow(R.string.appearance_motion_system, preferences.reducedMotion == null) { onReducedMotionSelected(null) } }
        item { ChoiceRow(R.string.appearance_motion_reduce, preferences.reducedMotion == true) { onReducedMotionSelected(true) } }
        item { ChoiceRow(R.string.appearance_motion_full, preferences.reducedMotion == false) { onReducedMotionSelected(false) } }
    }
}

@Composable
fun LanguageScreen(
    selected: LanguagePreference,
    onBack: () -> Unit,
    onLanguageSelected: (LanguagePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("language_root").selectableGroup(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SettingsHeader(R.string.route_language, onBack) }
        item { ChoiceRow(R.string.language_device, selected == LanguagePreference.SYSTEM, "language_system") {
            onLanguageSelected(LanguagePreference.SYSTEM)
        } }
        item { ChoiceRow(R.string.language_portuguese, selected == LanguagePreference.PORTUGUESE_BRAZIL) {
            onLanguageSelected(LanguagePreference.PORTUGUESE_BRAZIL)
        } }
        item { ChoiceRow(R.string.language_english, selected == LanguagePreference.ENGLISH_UNITED_STATES) {
            onLanguageSelected(LanguagePreference.ENGLISH_UNITED_STATES)
        } }
        item { Text(stringResource(R.string.language_fallback), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun SettingsHeader(@StringRes title: Int, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.match_back)) }
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun SettingsDestination(@StringRes title: Int, @StringRes summary: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ChoiceRow(@StringRes label: Int, selected: Boolean, tag: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}
