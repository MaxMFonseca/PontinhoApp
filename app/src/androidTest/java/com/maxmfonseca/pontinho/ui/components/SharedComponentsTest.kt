package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.theme.Coral
import com.maxmfonseca.pontinho.ui.theme.Emerald
import com.maxmfonseca.pontinho.ui.theme.EmeraldLight
import com.maxmfonseca.pontinho.ui.theme.Navy
import com.maxmfonseca.pontinho.ui.theme.NavySurface
import com.maxmfonseca.pontinho.ui.theme.OffWhite
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import com.maxmfonseca.pontinho.ui.theme.SkyBlue
import com.maxmfonseca.pontinho.ui.theme.WarmYellow
import com.maxmfonseca.pontinho.ui.theme.WarmSurface
import com.maxmfonseca.pontinho.ui.theme.WarmSurfaceLow
import com.maxmfonseca.pontinho.ui.theme.WarmSurfaceHigh
import com.maxmfonseca.pontinho.ui.theme.NavySurfaceHigh
import com.maxmfonseca.pontinho.ui.theme.NeutralText
import com.maxmfonseca.pontinho.ui.theme.NeutralTextLight
import com.maxmfonseca.pontinho.ui.theme.NeutralOutline
import com.maxmfonseca.pontinho.ui.theme.NeutralOutlineDark
import com.maxmfonseca.pontinho.ui.theme.EmeraldContainer
import com.maxmfonseca.pontinho.ui.theme.EmeraldContainerDark
import com.maxmfonseca.pontinho.ui.theme.YellowContainer
import com.maxmfonseca.pontinho.ui.theme.YellowContainerDark
import com.maxmfonseca.pontinho.ui.theme.BlueContainer
import com.maxmfonseca.pontinho.ui.theme.BlueContainerDark
import com.maxmfonseca.pontinho.ui.theme.CoralStrong
import com.maxmfonseca.pontinho.ui.theme.CoralContainer
import com.maxmfonseca.pontinho.ui.theme.CoralContainerDark
import com.maxmfonseca.pontinho.ui.theme.Zinc400
import com.maxmfonseca.pontinho.ui.theme.Zinc50
import com.maxmfonseca.pontinho.ui.theme.Zinc700
import com.maxmfonseca.pontinho.ui.theme.Zinc800
import com.maxmfonseca.pontinho.ui.theme.Zinc900
import com.maxmfonseca.pontinho.ui.theme.Zinc950
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SharedComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun roundStatusesExposeSymbolsAndLocalizedDescriptions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                RoundStatus(RoundStatusKind.WINNER)
                RoundStatus(RoundStatusKind.LOSS)
                RoundStatus(RoundStatusKind.PENDING)
                RoundStatus(RoundStatusKind.NOT_PARTICIPATING)
            }
        }

        listOf(R.string.round_status_winner, R.string.round_status_loss, R.string.round_status_pending,
            R.string.round_status_not_participating).forEach {
            composeRule.onNodeWithContentDescription(context.getString(it)).assertIsDisplayed()
        }
    }

    @Test
    fun pontinhoThemesExposeOnlyApprovedPaletteColors() {
        var lightColors = emptyList<Int>()
        var darkColors = emptyList<Int>()
        composeRule.setContent {
            Column {
                PontinhoTheme(themePreference = ThemePreference.LIGHT) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect { lightColors = scheme.visibleRoleColors() }
                    RoundStatus(RoundStatusKind.PENDING)
                }
                PontinhoTheme(themePreference = ThemePreference.DARK) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect { darkColors = scheme.visibleRoleColors() }
                    RoundStatus(RoundStatusKind.NOT_PARTICIPATING)
                }
            }
        }

        val approved = setOf(
            Emerald,
            EmeraldLight,
            WarmYellow,
            Coral,
            SkyBlue,
            OffWhite,
            Navy,
            NavySurface,
            WarmSurface, WarmSurfaceLow, WarmSurfaceHigh, NavySurfaceHigh,
            NeutralText, NeutralTextLight, NeutralOutline, NeutralOutlineDark,
            EmeraldContainer, EmeraldContainerDark, YellowContainer, YellowContainerDark,
            BlueContainer, BlueContainerDark, CoralStrong, CoralContainer, CoralContainerDark,
            Zinc50, Zinc400, Zinc700, Zinc800, Zinc900, Zinc950,
        ).mapTo(mutableSetOf()) { it.toArgb() }
        composeRule.runOnIdle {
            assertTrue("Light scheme leaked colors: ${lightColors.filterNot(approved::contains)}", lightColors.all(approved::contains))
            assertTrue("Dark scheme leaked colors: ${darkColors.filterNot(approved::contains)}", darkColors.all(approved::contains))
        }
    }

    @Test
    fun darkThemeUsesNeutralZincSurfacesAndText() {
        var colors = emptyList<Int>()
        composeRule.setContent {
            PontinhoTheme(themePreference = ThemePreference.DARK) {
                val scheme = MaterialTheme.colorScheme
                SideEffect {
                    colors = listOf(
                        scheme.background,
                        scheme.surface,
                        scheme.surfaceVariant,
                        scheme.outline,
                        scheme.onSurfaceVariant,
                        scheme.onSurface,
                    ).map { it.toArgb() }
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(
                listOf(0xFF09090B, 0xFF18181B, 0xFF27272A, 0xFF3F3F46, 0xFFA1A1AA, 0xFFFAFAFA)
                    .map { it.toInt() },
                colors,
            )
        }
    }

    @Test
    fun pendingRoundStatusRendersTheConfiguredPaletteSurfaceVariant() {
        var lightExpected = 0
        var darkExpected = 0
        composeRule.setContent {
            Column {
                PontinhoTheme(themePreference = ThemePreference.LIGHT) {
                    val expected = MaterialTheme.colorScheme.surfaceVariant.toArgb()
                    SideEffect { lightExpected = expected }
                    RoundStatus(RoundStatusKind.PENDING, Modifier.testTag("light_pending_status"))
                }
                PontinhoTheme(themePreference = ThemePreference.DARK) {
                    val expected = MaterialTheme.colorScheme.surfaceVariant.toArgb()
                    SideEffect { darkExpected = expected }
                    RoundStatus(RoundStatusKind.PENDING, Modifier.testTag("dark_pending_status"))
                }
            }
        }

        composeRule.onNodeWithTag("light_pending_status").assert(
            SemanticsMatcher.expectValue(RoundStatusContainerColorKey, lightExpected),
        )
        composeRule.onNodeWithTag("dark_pending_status").assert(
            SemanticsMatcher.expectValue(RoundStatusContainerColorKey, darkExpected),
        )
    }

    @Test
    fun playerIdentityAndActionStayOutsideScrollableRoundHistory() {
        composeRule.setContent {
            PlayerCard(
                playerName = "Ana",
                total = 42,
                isDealer = true,
                history = (1..16).map { PlayerRoundHistory(it, RoundStatusKind.LOSS, it * 3) },
                onAddScore = {},
            )
        }

        composeRule.onNodeWithTag("player_identity").assertIsDisplayed()
        composeRule.onNodeWithText("Ana").assertIsDisplayed()
        composeRule.onNodeWithTag("player_round_history").assert(hasScrollAction())
        composeRule.onNodeWithTag("player_action").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun dealerHandSitsImmediatelyAfterNameAndTotalStaysAtTheTrailingSide() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            Box(Modifier.width(360.dp)) {
                PlayerCard(
                    playerName = "Ana",
                    total = 42,
                    isDealer = true,
                    history = emptyList(),
                    onAddScore = {},
                    modifier = Modifier.testTag("dealer_player_card"),
                )
            }
        }

        val name = composeRule.onNodeWithTag("player_name").fetchSemanticsNode().boundsInRoot
        val hand = composeRule.onNodeWithTag("dealer_hand").assertIsDisplayed()
            .assertContentDescriptionEquals(context.getString(R.string.deals_cards))
            .fetchSemanticsNode().boundsInRoot
        val total = composeRule.onNodeWithTag("player_total").fetchSemanticsNode().boundsInRoot
        val action = composeRule.onNodeWithTag("player_action").fetchSemanticsNode().boundsInRoot

        assertTrue("Dealer hand must follow the name: $name vs $hand", name.right <= hand.left)
        val maximumGap = with(composeRule.density) { 8.dp.toPx() } + 1f
        assertTrue("Dealer hand should stay visually attached to the name", hand.left - name.right <= maximumGap)
        assertTrue("Total must be pushed to the trailing side after the hand", hand.right < total.left)
        assertTrue("Score action remains reachable after the trailing total", total.right <= action.left)
    }

    @Test
    fun longPlayerNameCannotOverlapReservedTotalAtLargeFontScale() {
        val longName = "Alexandre Maximiliano de Albuquerque e Silva"
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 2f),
            ) {
                Box(Modifier.width(360.dp)) {
                    PlayerCard(
                        playerName = longName,
                        total = 99999,
                        isDealer = false,
                        history = listOf(PlayerRoundHistory(1, RoundStatusKind.PENDING, 88)),
                        onAddScore = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("player_name").assertIsDisplayed().assertTextEquals(longName)
        composeRule.onNodeWithTag("player_total").assertIsDisplayed().assertTextEquals("99999")
        composeRule.onNodeWithTag("player_round_history").assertIsDisplayed().assert(hasScrollAction())
        composeRule.onNodeWithTag("player_action").assertIsDisplayed().assertHasClickAction()
        val nameBounds = composeRule.onNodeWithTag("player_name").fetchSemanticsNode().boundsInRoot
        val totalNode = composeRule.onNodeWithTag("player_total").fetchSemanticsNode()
        val totalBounds = totalNode.boundsInRoot
        val nameLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val totalLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        assertTrue(
            composeRule.onNodeWithTag("player_name").fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action?.invoke(nameLayouts) == true,
        )
        assertTrue(
            totalNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(totalLayouts) == true,
        )
        val nameLayout = nameLayouts.single()
        val totalLayout = totalLayouts.single()
        assertFalse("Name and total overlap: $nameBounds vs $totalBounds", nameBounds.overlaps(totalBounds))
        assertTrue("Name and total should remain on the same identity line", kotlin.math.abs(totalBounds.center.y - nameBounds.center.y) <= totalBounds.height)
        // hasVisualOverflow also reports intentional maxLines ellipsis as height
        // overflow. Check the actual laid-out dimensions while requiring ellipsis.
        assertEquals("Long names remain one line", 1, nameLayout.lineCount)
        assertTrue("Long names must end in an ellipsis", nameLayout.isLineEllipsized(0))
        assertFalse("Player name exceeded its measured width", nameLayout.didOverflowWidth)
        assertTrue("Name glyph layout must fit its measured height",
            nameLayout.multiParagraph.height <= nameLayout.size.height)
        assertTrue("Name layout must fit its visible node", nameLayout.size.height <= nameBounds.height)
        assertFalse("Player total was ellipsized", totalLayout.isLineEllipsized(0))
        assertFalse(
            "Player total overflowed its measured width: layout=${totalLayout.size}, node=$totalBounds",
            totalLayout.didOverflowWidth,
        )
        assertFalse(
            "Player total overflowed visually: layout=${totalLayout.size}, node=$totalBounds",
            totalLayout.hasVisualOverflow,
        )
        assertTrue(
            "Total layout exceeds its node bounds: ${totalLayout.size} vs $totalBounds",
            totalLayout.size.width <= totalBounds.width,
        )
    }

    @Test
    fun playerNameDialogRequestsFocusOnEntry() {
        composeRule.setContent {
            PlayerNameDialog(value = "", onValueChange = {}, onConfirm = {}, onDismiss = {})
        }

        composeRule.onNodeWithTag("player_name_input").assertIsFocused()
    }

    @Test
    fun scoreDialogRequestsFocusAndAcceptsExplicitZero() {
        var confirmed: Int? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            ScoreDialog(
                playerName = "Ana",
                roundNumber = 2,
                indicatorLabel = "A vermelho",
                value = "0",
                onValueChange = {},
                onConfirm = { confirmed = it },
                onDismiss = {},
                onWentOut = {},
            )
        }

        composeRule.onNodeWithTag("score_input").assertIsFocused()
        composeRule.onNodeWithText(context.getString(R.string.confirm)).performClick()
        composeRule.runOnIdle { assertEquals(0, confirmed) }
    }
}

private fun ColorScheme.visibleRoleColors(): List<Int> = listOf(
    primary,
    onPrimary,
    primaryContainer,
    onPrimaryContainer,
    inversePrimary,
    secondary,
    onSecondary,
    secondaryContainer,
    onSecondaryContainer,
    tertiary,
    onTertiary,
    tertiaryContainer,
    onTertiaryContainer,
    background,
    onBackground,
    surface,
    onSurface,
    surfaceVariant,
    onSurfaceVariant,
    surfaceTint,
    inverseSurface,
    inverseOnSurface,
    error,
    onError,
    errorContainer,
    onErrorContainer,
    outline,
    outlineVariant,
    scrim,
    surfaceBright,
    surfaceDim,
    surfaceContainer,
    surfaceContainerHigh,
    surfaceContainerHighest,
    surfaceContainerLow,
    surfaceContainerLowest,
    primaryFixed,
    primaryFixedDim,
    onPrimaryFixed,
    onPrimaryFixedVariant,
    secondaryFixed,
    secondaryFixedDim,
    onSecondaryFixed,
    onSecondaryFixedVariant,
    tertiaryFixed,
    tertiaryFixedDim,
    onTertiaryFixed,
    onTertiaryFixedVariant,
).map { it.toArgb() }
