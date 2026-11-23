package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.components.settings.TABLE_WRAP_LABEL
import dev.stapler.stelekit.ui.components.settings.TableSettingsRows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Table layout on a 360dp phone: shared columns, wrapping, GFM alignment, inline markdown, settings. Gradle-only (Compose host). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableBlockLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private val phoneWidth = 360.dp

    private fun show(content: String, prefs: TableDisplayPrefs = TableDisplayPrefs(numericRightAlign = false)) = rule.setContent {
        MaterialTheme {
            Surface {
                Box(Modifier.width(phoneWidth)) { TableBlock(content = content, onStartEditing = {}, prefs = prefs) }
            }
        }
    }

    /** Right edge ignoring the scroll viewport's clip (boundsInRoot is clipped, so it can never exceed the viewport). */
    private fun unclippedRight(text: String): Float =
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().let { it.positionInRoot.x + it.size.width }

    private fun bounds(text: String): Rect = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun layout(text: String): Pair<SemanticsNode, TextLayoutResult> {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return node to out.single()
    }

    private fun inkRight(text: String): Float = layout(text).let { (n, l) -> n.boundsInRoot.left + l.getLineRight(0) }
    private fun inkCenter(text: String): Float = layout(text).let { (n, l) -> n.boundsInRoot.left + (l.getLineLeft(0) + l.getLineRight(0)) / 2 }

    @Test
    fun columnsShareOneBoundaryAcrossEveryRow() {
        show(PHONE_TABLE)
        rule.waitForIdle()
        for (column in listOf("Qty" to listOf("140 ft", "6", "20"), "W each" to listOf("1.08", "8", "2.5"))) {
            val header = bounds(column.first)
            column.second.forEach { cell ->
                val b = bounds(cell)
                assertEquals(header.left, b.left, 0.5f, "'$cell' must start where '${column.first}' starts")
                assertEquals(header.width, b.width, 0.5f, "'$cell' must be as wide as '${column.first}'")
            }
        }
    }

    @Test
    fun tableFitsThePhoneWidthByWrappingInsteadOfClipping() {
        show(WIDE_TABLE)
        rule.waitForIdle()
        val limit = with(rule.density) { phoneWidth.toPx() }
        assertTrue(unclippedRight("Total") <= limit + 0.5f, "last column right=${unclippedRight("Total")} exceeds $limit")
        // A token with no whitespace (a number) must never be split mid-token.
        assertEquals(1, layout("249.4").second.lineCount, "249.4 must stay on one line")
        // The long cell must have wrapped onto several lines rather than stretched the table.
        assertTrue(layout("Controller cabinet with redundant power supplies and a spare output module").second.lineCount > 1)
    }

    @Test
    fun inlineMarkdownInCellsIsRenderedNotShownRaw() {
        show(PHONE_TABLE)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("**DC total**", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("DC total", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("249.4", useUnmergedTree = true).assertExists()
    }

    @Test
    fun gfmAlignmentMarkersAlignCellText() {
        show("| L | C | R |\n| :--- | :---: | ---: |\n| a | bb | 1 |\n| aaaaaa | bbbbbbbbbb | 1234567 |")
        rule.waitForIdle()
        assertEquals(inkRight("1"), inkRight("1234567"), 1f)
        assertEquals(inkCenter("bb"), inkCenter("bbbbbbbbbb"), 1f)
        assertEquals(bounds("a").left, bounds("aaaaaa").left, 0.5f)
    }

    @Test
    fun unmarkedNumericColumnRightAlignsWhenPreferenceIsOn() {
        show(PHONE_TABLE, TableDisplayPrefs(numericRightAlign = true))
        rule.waitForIdle()
        assertEquals(inkRight("1.08"), inkRight("2.5"), 1f)
        assertTrue(abs(inkRight("8") - inkRight("2.5")) <= 1f)
    }

    @Test
    fun scrollModeKeepsNaturalColumnWidthInsteadOfWrapping() {
        val long = "| A | B |\n|---|---|\n| ${"word ".repeat(12).trim()} | x |"
        show(long, TableDisplayPrefs(overflow = TableOverflow.SCROLL))
        rule.waitForIdle()
        val limit = with(rule.density) { phoneWidth.toPx() }
        // boundsInRoot is clipped to the scroll viewport, so measure the unclipped position + size.
        val node = rule.onNodeWithText("x", useUnmergedTree = true).fetchSemanticsNode()
        val unclippedRight = node.positionInRoot.x + node.size.width
        assertTrue(unclippedRight > limit, "scrolling table extends past the phone width (right=$unclippedRight, limit=$limit)")
    }

    @Test
    fun settingsToggleWritesThroughToThePreferenceHolder() {
        val holder = TablePreferences(null)
        rule.setContent { MaterialTheme { Column { TableSettingsRows(holder) } } }
        rule.onNodeWithText(TABLE_WRAP_LABEL, useUnmergedTree = true).performClick()
        assertEquals(TableOverflow.SCROLL, holder.prefs.overflow)
    }

    companion object {
        private val WIDE_TABLE = """
            | Item | Total |
            |---|---|
            | Controller cabinet with redundant power supplies and a spare output module | 249.4 |
        """.trimIndent()

        private val PHONE_TABLE = """
            | Load | Qty | W each | W total |
            |---|---|---|---|
            | Pixel track (RGBW) | 140 ft | 1.08 | 151.2 |
            | Uplight fixtures | 6 | 8 | 48 |
            | Path/stair fixtures | 20 | 2.5 | 50 |
            | **DC total** | | | **249.4** |
        """.trimIndent()
    }
}
