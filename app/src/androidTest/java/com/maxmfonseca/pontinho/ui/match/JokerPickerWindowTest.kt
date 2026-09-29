package com.maxmfonseca.pontinho.ui.match

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.CardColor
import com.maxmfonseca.pontinho.domain.model.Rank
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.model.Statement
import java.io.FileInputStream
import java.util.Locale

/** Real Android windows, not a density override outside the Dialog's separate composition. */
@RunWith(Parameterized::class)
class JokerPickerWindowTest(private val landscape: Boolean, private val fontScale: Float) {
    private val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext

    @get:Rule val rules: TestRule = RuleChain.outerRule(ActualWindowRule()).around(compose)

    @Test fun allOptionsPreviewAndActionsFitWithoutScrollingIncludingSaveFailure() {
        var language by mutableStateOf("pt-BR")
        var theme by mutableStateOf(ThemePreference.LIGHT)
        var rank by mutableStateOf<Rank?>(null)
        var color by mutableStateOf<CardColor?>(null)
        var error by mutableStateOf(false)
        var confirmations = 0
        var cancellations = 0
        compose.setContent {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources) {
                PontinhoTheme(theme) {
                    JokerPicker(rank, color, false, error, { rank = it }, { color = it },
                        { confirmations++; error = true }, { cancellations++ })
                }
            }
        }
        for (locale in listOf("pt-BR", "en-US")) for (mode in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) {
            compose.runOnIdle { language = locale; theme = mode; rank = null; color = null; error = false }
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            })
            val scenario = "${if (landscape) "landscape" else "portrait"}-$fontScale-$locale-${mode.name.lowercase()}"
            val confirm = compose.onNodeWithText(context.getString(R.string.confirm))
            val cancel = compose.onNodeWithText(context.getString(R.string.cancel))
            fun checkAndCapture(state: String) {
                val label = "$scenario-$state"
                val controls = (Rank.entries.map { "indicator_rank_${it.name}" } +
                    CardColor.entries.map { "indicator_color_${it.name}" }).map { compose.onNodeWithTag(it) } + listOf(cancel, confirm)
                controls.forEach { control ->
                    control.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                    assertFullyVisible(control.fetchSemanticsNode(), label)
                }
                val bounds = controls.map { it.fetchSemanticsNode().touchBoundsInRoot }
                for (i in bounds.indices) for (j in i + 1 until bounds.size) {
                    assertFalse("Overlapping targets at $label: ${bounds[i]} / ${bounds[j]}", bounds[i].overlaps(bounds[j]))
                }
                listOf(context.getString(R.string.cancel), context.getString(R.string.confirm)).forEach { text ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNode(hasText(text) and !hasClickAction(), true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertEquals("One-word action must stay intact at $label: $text", 1, layouts.single().lineCount)
                    val layout = layouts.single()
                    assertTextFits(layout, "$label: $text")
                }
                compose.onNodeWithTag("joker_preview").assertIsDisplayed().also {
                    assertFullyVisible(it.fetchSemanticsNode(), label)
                }
                val title = compose.onNodeWithText(context.getString(R.string.indicator_title))
                title.assertIsDisplayed()
                assertFullyVisible(title.fetchSemanticsNode(), label)
                if (error) {
                    val message = compose.onNodeWithText(context.getString(R.string.match_save_error))
                    message.assertIsDisplayed()
                    assertFullyVisible(message.fetchSemanticsNode(), label)
                    val layouts = mutableListOf<TextLayoutResult>()
                    message.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTextFits(layouts.single(), "$label: save error")
                }
                compose.onAllNodes(hasScrollAction() and hasAnyAncestor(hasTestTag("joker_dialog")), true).assertCountEquals(0)
                shell("screencap -p /sdcard/Download/final-picker-$label.png")
            }
            confirm.assertIsNotEnabled()
            checkAndCapture("empty")
            for (option in Rank.entries.map { "indicator_rank_${it.name}" } + CardColor.entries.map { "indicator_color_${it.name}" }) {
                compose.onNodeWithTag(option).performTouchInput { click() }.assertIsSelected()
            }
            // Nine gives two-digit tens; use the longer color name in each supported locale.
            compose.onNodeWithTag("indicator_rank_NINE").performTouchInput { click() }.assertIsSelected()
            val longestColor = if (locale == "pt-BR") CardColor.RED else CardColor.BLACK
            compose.onNodeWithTag("indicator_color_${longestColor.name}").performTouchInput { click() }.assertIsSelected()
            confirm.assertIsEnabled()
            checkAndCapture("selected")
            val previousConfirmations = confirmations
            confirm.performTouchInput { click() }
            compose.runOnIdle { assertEquals(previousConfirmations + 1, confirmations) }
            checkAndCapture("error")
            val previousCancellations = cancellations
            cancel.performTouchInput { click() }
            compose.runOnIdle { assertEquals(previousCancellations + 1, cancellations) }
        }
    }

    private fun assertFullyVisible(node: SemanticsNode, scenario: String) {
        val actual = node.layoutInfo.coordinates.size
        assertEquals("Clipped width at $scenario: ${node.config}", actual.width.toFloat(), node.boundsInRoot.width, 1f)
        assertEquals("Clipped height at $scenario: ${node.config}", actual.height.toFloat(), node.boundsInRoot.height, 1f)
    }

    private fun assertTextFits(layout: TextLayoutResult, scenario: String) {
        // Compare rendered lines, not the paragraph's wider measurement constraint.
        for (line in 0 until layout.lineCount) {
            assertTrue("Text left edge clipped at $scenario", layout.getLineLeft(line) >= -1f)
            assertTrue("Text right edge clipped at $scenario: ${layout.getLineRight(line)} > ${layout.size.width}",
                layout.getLineRight(line) <= layout.size.width + 1f)
            assertFalse("Ellipsized text at $scenario", layout.isLineEllipsized(line))
        }
        assertTrue("Text bottom clipped at $scenario", layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f)
        assertEquals("Text omitted at $scenario", layout.layoutInput.text.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return try { FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()) }.trim() }
        finally { descriptor.close() }
    }

    private inner class ActualWindowRule : TestRule {
        override fun apply(statement: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val originalConfiguration = Configuration(base.resources.configuration)
                val oldSize = Regex("Override size: (\\S+)").find(shell("wm size"))?.groupValues?.get(1) ?: "reset"
                val oldDensity = Regex("Override density: (\\S+)").find(shell("wm density"))?.groupValues?.get(1) ?: "reset"
                val oldFont = shell("settings get system font_scale")
                val oldRotation = shell("settings get system user_rotation")
                val oldAutoRotation = shell("settings get system accelerometer_rotation")
                try {
                    shell("settings put system accelerometer_rotation 0")
                    shell("settings put system user_rotation 0")
                    shell("wm density 320")
                    shell("wm size ${if (landscape) "1136x640" else "640x1136"}")
                    shell("settings put system font_scale $fontScale")
                    val deadline = SystemClock.uptimeMillis() + 10_000
                    while (SystemClock.uptimeMillis() < deadline) {
                        val config = base.resources.configuration
                        if (config.fontScale == fontScale && config.densityDpi == 320 &&
                            config.screenWidthDp == (if (landscape) 568 else 320) &&
                            (config.orientation == Configuration.ORIENTATION_LANDSCAPE) == landscape) break
                        SystemClock.sleep(50)
                    }
                    assertEquals(fontScale, base.resources.configuration.fontScale, 0.001f)
                    assertEquals(if (landscape) 568 else 320, base.resources.configuration.screenWidthDp)
                    assertEquals(landscape, base.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    statement.evaluate()
                } finally {
                    shell("settings put system font_scale $oldFont")
                    shell("wm size $oldSize")
                    shell("wm density $oldDensity")
                    shell("settings put system user_rotation $oldRotation")
                    shell("settings put system accelerometer_rotation $oldAutoRotation")
                    val deadline = SystemClock.uptimeMillis() + 10_000
                    while (SystemClock.uptimeMillis() < deadline) {
                        val restored = base.resources.configuration
                        if (restored.fontScale == originalConfiguration.fontScale &&
                            restored.densityDpi == originalConfiguration.densityDpi &&
                            restored.screenWidthDp == originalConfiguration.screenWidthDp) break
                        SystemClock.sleep(50)
                    }
                }
            }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "landscape={0},font={1}")
        fun parameters() = listOf(true, false).flatMap { landscape -> listOf(2f, 1.5f, 1f).map { arrayOf<Any>(landscape, it) } }
    }
}
