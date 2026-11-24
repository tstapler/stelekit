package dev.stapler.stelekit.ui.components

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import kotlin.test.assertEquals
import kotlin.time.Clock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot and semantic tests for [TableBlock].
 *
 * The screenshots guard against the class of bug where `.background()` is used without
 * an explicit `surface` background on the outer container, or where `Text` cells lack an
 * explicit `color` — both of which cause unreadable text when the theme is switched.
 *
 * To record new golden images run:
 *   ./gradlew jvmTest -Proborazzi.test.record=true
 */
class TableBlockScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(themeMode: StelekitThemeMode) {
        composeTestRule.setContent {
            StelekitTheme(themeMode = themeMode) {
                Surface {
                    TableBlock(
                        content = TABLE_CONTENT,
                        onStartEditing = {}
                    )
                }
            }
        }
    }

    // ---- Semantic tests (no golden required) ------------------------------------

    @Test
    fun `header cells are displayed in light theme`() {
        render(StelekitThemeMode.LIGHT)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Scenario").assertIsDisplayed()
        composeTestRule.onNodeWithText("Prefer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reason").assertIsDisplayed()
    }

    @Test
    fun `header cells are displayed in dark theme`() {
        render(StelekitThemeMode.DARK)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Scenario").assertIsDisplayed()
        composeTestRule.onNodeWithText("Prefer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reason").assertIsDisplayed()
    }

    @Test
    fun `body cells are displayed`() {
        render(StelekitThemeMode.LIGHT)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Collection").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sequence").assertIsDisplayed()
    }

    // ---- Links in cells ----------------------------------------------------------

    private fun renderWith(handler: UriHandler, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) { StelekitTheme { Surface { content() } } }
        }
    }

    @Test
    fun `tapping a url cell opens it and a handler that throws is swallowed`() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
                if (opened.size == 2) error("platform cannot open this")
            }
        }
        renderWith(handler) { TableBlock(content = LINK_TABLE, onStartEditing = {}) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("https://example.org/spec", useUnmergedTree = true).performTouchInput { click(Offset(8f, 8f)) }
        composeTestRule.onNodeWithText("https://example.org/spec", useUnmergedTree = true).performTouchInput { click(Offset(8f, 8f)) }
        assertEquals(listOf("https://example.org/spec", "https://example.org/spec"), opened)
    }

    @Test
    fun `a headerless table block still opens a tapped url`() {
        val opened = mutableListOf<String>()
        renderWith(object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
            TableBlock(content = "https://example.org/only-line", onStartEditing = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("https://example.org/only-line", useUnmergedTree = true)
            .performTouchInput { click(Offset(8f, 8f)) }
        assertEquals(listOf("https://example.org/only-line"), opened)
    }

    @Test
    fun `a table block rendered by BlockItem opens the url tapped in a cell`() {
        val opened = mutableListOf<String>()
        val block = Block(
            uuid = BlockUuid("t1"), pageUuid = PageUuid("p1"), content = LINK_TABLE, position = "a0",
            createdAt = Clock.System.now(), updatedAt = Clock.System.now(), blockType = BlockType.Table,
        )
        renderWith(object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
            BlockItem(
                block = block, isEditing = false, onStartEditing = {}, onStopEditing = {},
                onContentChange = { _, _ -> }, onLinkClick = {}, onNewBlock = {}, onSplitBlock = { _, _ -> },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("https://example.org/spec", useUnmergedTree = true).performTouchInput { click(Offset(8f, 8f)) }
        assertEquals(listOf("https://example.org/spec"), opened)
    }

    // ---- Screenshot tests (require golden recording on first run) ---------------

    @Test
    fun tableBlock_light() {
        render(StelekitThemeMode.LIGHT)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/table_block_light.png")
    }

    @Test
    fun tableBlock_dark() {
        render(StelekitThemeMode.DARK)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/table_block_dark.png")
    }

    companion object {
        private val LINK_TABLE = """
            | Name | Link |
            |---|---|
            | Spec | https://example.org/spec |
        """.trimIndent()

        private val TABLE_CONTENT = """
            | Scenario | Prefer | Reason |
            |---|---|---|
            | Small collection | Collection | Inline functions |
            | Large collection | Sequence | Avoids allocations |
        """.trimIndent()
    }
}
