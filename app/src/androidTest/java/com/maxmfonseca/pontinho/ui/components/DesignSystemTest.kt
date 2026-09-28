package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.layout.Column
import android.content.res.Configuration
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class DesignSystemTest {
    @get:Rule val compose = createComposeRule()

    @Test fun portugueseLightControlsHaveSingleLocalizedActionsAtDoubleFontScale() =
        checkControls("pt-BR", ThemePreference.LIGHT, 2f)

    @Test fun englishDarkControlsHaveSingleLocalizedActionsAtDoubleFontScale() =
        checkControls("en-US", ThemePreference.DARK, 2f)

    @Test fun englishLightControlsWorkAtDefaultFontScale() =
        checkControls("en-US", ThemePreference.LIGHT, 1f)

    @Test fun portugueseDarkControlsWorkAtOneAndHalfFontScale() =
        checkControls("pt-BR", ThemePreference.DARK, 1.5f)

    private fun checkControls(locale: String, theme: ThemePreference, fontScale: Float) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale))
        })
        var backs = 0
        var settings = 0
        var selections = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources,
                LocalDensity provides Density(density.density, fontScale)) {
                PontinhoTheme(theme) {
                    Column(Modifier.width(320.dp)) {
                        PontinhoTopBar(R.string.route_settings, onBack = { backs++ },
                            onSettings = { settings++ })
                        PontinhoInfoBanner(R.string.settings_learn_summary)
                        PontinhoSettingCard(R.string.route_appearance, subtitle = R.string.settings_appearance_summary,
                            onClick = { selections++ }, modifier = Modifier.testTag("setting"))
                        PontinhoSettingCard(R.string.route_language, enabled = false,
                            onClick = { selections++ }, modifier = Modifier.testTag("disabled"))
                    }
                }
            }
        }
        listOf(R.string.match_back, R.string.home_settings).forEach { resource ->
            val label = context.getString(resource)
            compose.onAllNodesWithContentDescription(label, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithContentDescription(label).assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp).performClick()
        }
        compose.onNodeWithTag("setting").assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("disabled").assertIsNotEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.settings_learn_summary)).assertExists()
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(4)
        val card = compose.onNodeWithTag("setting").fetchSemanticsNode().boundsInRoot
        val subtitle = compose.onNodeWithText(context.getString(R.string.settings_appearance_summary),
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Supporting text must fit inside its card", card.contains(subtitle.topLeft) && card.contains(subtitle.bottomRight))
        val rawGlyphs = setOf("⚙", "✎", "×", "⠿", "←", "›")
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("text") {
            it.config.contains(SemanticsProperties.Text)
        }, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            assertTrue(node.config[SemanticsProperties.Text].none { it.text in rawGlyphs })
        }
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals(1, settings)
            assertEquals(1, selections)
        }
    }

    @Test fun lightAndDarkTextRolesAreReadableAndCardsStayNeutral() {
        val schemes = mutableListOf<ColorScheme>()
        compose.setContent {
            Column {
                listOf(ThemePreference.LIGHT, ThemePreference.DARK).forEach { preference ->
                    PontinhoTheme(preference) {
                        val colors = MaterialTheme.colorScheme
                        SideEffect { schemes.add(colors) }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertTrue(schemes.size >= 2)
            schemes.forEach { colors ->
                listOf(
                    colors.onPrimary to colors.primary,
                    colors.onPrimaryContainer to colors.primaryContainer,
                    colors.onSecondaryContainer to colors.secondaryContainer,
                    colors.onTertiaryContainer to colors.tertiaryContainer,
                    colors.onError to colors.error,
                    colors.onErrorContainer to colors.errorContainer,
                    colors.onSurface to colors.surface,
                    colors.onSurfaceVariant to colors.surfaceVariant,
                    colors.onBackground to colors.background,
                ).forEach { (foreground, background) ->
                    assertTrue("Text contrast must be at least 4.5:1 ($foreground on $background)",
                        contrast(foreground, background) >= 4.5f)
                }
                assertTrue("Neutral card surfaces must be distinct from information surfaces",
                    colors.surfaceVariant != colors.tertiaryContainer)
                assertTrue("Raised surfaces must be distinguishable from the background",
                    colors.surfaceContainer != colors.background)
            }
        }
    }
}

private fun contrast(a: Color, b: Color): Float {
    val first = a.luminance()
    val second = b.luminance()
    return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
}
