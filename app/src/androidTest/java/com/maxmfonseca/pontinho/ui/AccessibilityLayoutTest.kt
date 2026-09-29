package com.maxmfonseca.pontinho.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.ParticipantId
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.components.PlayerRoundHistory
import com.maxmfonseca.pontinho.ui.components.RoundStatusKind
import com.maxmfonseca.pontinho.ui.home.HomeScreen
import com.maxmfonseca.pontinho.ui.home.HomeUiState
import com.maxmfonseca.pontinho.ui.match.MatchPlayer
import com.maxmfonseca.pontinho.ui.match.MatchScreen
import com.maxmfonseca.pontinho.ui.match.MatchUiState
import com.maxmfonseca.pontinho.ui.setup.DraftPlayer
import com.maxmfonseca.pontinho.ui.setup.MatchSetupScreen
import com.maxmfonseca.pontinho.ui.setup.MatchSetupUiState
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream
import java.util.Locale

class AccessibilityLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val baseContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun decorativeHomeGlyphsAreSilentAndSettingsActionIsAnnouncedOnce() {
        val settingsLabel = baseContext.getString(R.string.home_settings)
        compose.setContent {
            PontinhoTheme {
                HomeScreen(HomeUiState(isLoading = false), {}, {}, {}, onSettings = {})
            }
        }

        listOf("⚙", "＋", "▤", "♣ ♥").forEach { glyph ->
            compose.onNodeWithText(glyph, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onAllNodesWithContentDescription(settingsLabel, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasContentDescription(settingsLabel)).assertIsDisplayed()
    }

    @Test fun decorativeDateGlyphAndChevronAreSilent() {
        compose.setContent {
            PontinhoTheme {
                HomeScreen(
                    HomeUiState(
                        isLoading = false,
                        dateCards = listOf(
                            com.maxmfonseca.pontinho.ui.home.HomeDateCard(
                                java.time.LocalDate.now(), 1, 0, listOf("Ana"),
                            ),
                        ),
                    ),
                    {}, {}, {},
                )
            }
        }

        compose.onNodeWithText("▦", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("›", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun decorativeSetupGlyphsAreSilentAndActionsAreAnnouncedOnce() {
        val state = MatchSetupUiState(
            players = listOf(DraftPlayer("ana", null, "Ana")),
            latestMatchPlayers = listOf(DraftPlayer("bruno", null, "Bruno")),
        )
        val back = baseContext.getString(R.string.match_back)
        val edit = baseContext.getString(R.string.player_edit, "Ana")
        val remove = baseContext.getString(R.string.setup_remove_player, "Ana")
        compose.setContent { PontinhoTheme { setup(state) } }

        listOf("←", "ⓘ", "＋", "⇄", "✎", "×").forEach { glyph ->
            compose.onNodeWithText(glyph, useUnmergedTree = true).assertDoesNotExist()
        }
        listOf(back, edit, remove).forEach { label ->
            compose.onAllNodesWithContentDescription(label, useUnmergedTree = true).assertCountEquals(1)
        }
    }

    @Test fun largeRosterAndLongHistoryKeepFixedIdentityActionsAndStatusesReachable() {
        val longName = mapOf(
            "pt-BR" to "Alexandre Maximiliano de Albuquerque e Silva",
            "en-US" to "Alexandria Maximiliana of the Longest Localized Label",
        )
        var selectedLanguage by mutableStateOf("pt-BR")
        var selectedFontScale by mutableFloatStateOf(1f)
        var selectedTheme by mutableStateOf(ThemePreference.LIGHT)
        var selectedReducedMotion by mutableStateOf(false)
        compose.setContent {
            val localized = localizedContext(selectedLanguage)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalDensity provides Density(
                    density = baseContext.resources.displayMetrics.density,
                    fontScale = selectedFontScale,
                ),
            ) {
                PontinhoTheme(themePreference = selectedTheme, reducedMotion = selectedReducedMotion) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                            MatchScreen(stressFixture(longName.getValue(selectedLanguage)), {}, {}, {}, {}, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
        for (languageTag in longName.keys) {
            for (fontScale in listOf(1f, 1.5f, 2f)) {
                for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) {
                    for (reducedMotion in listOf(false, true)) {
                        val localized = localizedContext(languageTag)
                        compose.runOnIdle {
                            selectedLanguage = languageTag
                            selectedFontScale = fontScale
                            selectedTheme = theme
                            selectedReducedMotion = reducedMotion
                        }

                        compose.onNodeWithTag("match_players").performScrollToIndex(0)
                        val scenario = "$languageTag/$fontScale/$theme/reduced=$reducedMotion"
                        assertStressPlayer(0, scenario)
                        val history = compose.onNode(
                            hasTestTag("player_round_history") and
                                hasAnyAncestor(hasTestTag("match_player_p0")),
                        )
                        history.assert(hasScrollAction()).performTouchInput { swipeLeft() }
                        assertTrue(
                            "20-round history must move at $languageTag/$fontScale/$theme/$reducedMotion",
                            history.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f,
                        )

                        compose.onNodeWithTag("match_players").performScrollToIndex(11)
                        assertStressPlayer(11, scenario)
                        compose.onNodeWithTag("match_players").performScrollToIndex(12)
                        compose.onNodeWithTag("match_add_player").assertIsDisplayed()

                        RoundStatusKind.entries.forEach { status ->
                            val description = localized.getString(status.description)
                            assertTrue(
                                "At least one ${status.name} status must expose its localized description",
                                compose.onAllNodesWithContentDescription(description, useUnmergedTree = true)
                                    .fetchSemanticsNodes().isNotEmpty(),
                            )
                        }
                        if (fontScale == 2f && theme == ThemePreference.DARK && reducedMotion) {
                            captureDeviceScreenshot(
                                "task10-layout-${languageTag.lowercase()}-font-2-dark-reduced.png",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun largeTextKeepsPlayerNameAndTotalAsDistinctReadableIdentityContent() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseContext.resources.displayMetrics.density,
                    fontScale = 2f,
                ),
            ) {
                PontinhoTheme(themePreference = ThemePreference.DARK) {
                    Surface(Modifier.fillMaxSize()) {
                        MatchScreen(stressFixture("Alexandre Maximiliano de Albuquerque e Silva"),
                            {}, {}, {}, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }

        val row = hasAnyAncestor(hasTestTag("match_player_p0"))
        val name = compose.onNode(hasTestTag("player_name") and row, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val total = compose.onNode(hasTestTag("player_total") and row, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val minimumReadableNameWidth = 100.dp.value * baseContext.resources.displayMetrics.density
        assertTrue("The player name needs readable space at 200% font", name.width >= minimumReadableNameWidth)
        assertFalse("Name and total must not occupy overlapping pixels", name.overlaps(total))
        assertTrue("At 200% font the total must move below the name instead of squeezing it",
            total.top >= name.bottom)
    }

    private fun assertStressPlayer(index: Int, scenario: String) {
        val row = compose.onNodeWithTag("match_player_p$index").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val identity = compose.onNode(
            hasTestTag("player_identity") and hasAnyAncestor(hasTestTag("match_player_p$index")),
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val action = compose.onNode(
            hasTestTag("player_action") and hasAnyAncestor(hasTestTag("match_player_p$index")),
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val name = compose.onNode(
            hasTestTag("player_name") and hasAnyAncestor(hasTestTag("match_player_p$index")),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val total = compose.onNode(
            hasTestTag("player_total") and hasAnyAncestor(hasTestTag("match_player_p$index")),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue("Identity must remain inside its player row", identity.left >= row.left && identity.right <= row.right)
        assertTrue("Action must remain inside its player row", action.left >= row.left && action.right <= row.right)
        assertFalse("Fixed identity and action must not overlap", identity.overlaps(action))
        assertTrue(
            "Player name must retain readable width for player $index at $scenario; actual=${name.width}",
            name.width >= 100.dp.value * baseContext.resources.displayMetrics.density,
        )
        assertFalse("Player name and total must not overlap", name.overlaps(total))
    }

    private fun stressFixture(name: String) = MatchUiState(
        isLoading = false,
        roundNumber = 21,
        canManagePlayers = true,
        players = (0 until 12).map { player ->
            MatchPlayer(
                id = ParticipantId("p$player"),
                name = "$name ${player + 1}",
                total = player * 1234567,
                isDealer = player == 7,
                history = (1..20).map { round ->
                    val status = RoundStatusKind.entries[(player + round) % RoundStatusKind.entries.size]
                    PlayerRoundHistory(round, status, if (status == RoundStatusKind.NOT_PARTICIPATING || status == RoundStatusKind.EMPTY) null else player + round)
                },
                canAddScore = true,
            )
        },
    )

    private fun localizedContext(languageTag: String): android.content.Context {
        val configuration = Configuration(baseContext.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(languageTag)))
        return baseContext.createConfigurationContext(configuration)
    }

    private fun captureDeviceScreenshot(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val command = instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Download/$fileName",
        )
        FileInputStream(command.fileDescriptor).use { it.readBytes() }
        command.close()
    }

    @androidx.compose.runtime.Composable
    private fun setup(state: MatchSetupUiState) = MatchSetupScreen(
        state = state,
        onBack = {}, onAddPlayer = {}, onEditPlayer = {}, onRemovePlayer = {}, onMovePlayer = { _, _ -> },
        onUseLastMatch = {}, onDismissReplace = {}, onConfirmReplace = {}, onDialogNameChange = {},
        onDismissName = {}, onConfirmName = {}, onCreateMatch = {},
    )
}
