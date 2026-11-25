package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Phone-size baseline PNGs of the current inline table, the no-regression anchor for the table sheet work. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class TableBaselineRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(content: String, prefs: TableDisplayPrefs = TableDisplayPrefs(numericRightAlign = false)) = rule.setContent {
        MaterialTheme {
            Surface {
                Box(Modifier.width(360.dp)) { TableBlock(content = content, onStartEditing = {}, prefs = prefs) }
            }
        }
    }

    @Test
    fun baseline_should_WrapALongCell_OnAPhone() {
        show(LONG_CELL_TABLE)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/table-baseline-wrap.png")
    }

    @Test
    fun baseline_should_ScrollALongCell_WhenOverflowIsScroll() {
        show(LONG_CELL_TABLE, TableDisplayPrefs(numericRightAlign = false, overflow = TableOverflow.SCROLL))
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/table-baseline-scroll.png")
    }

    @Test
    fun baseline_should_ShowAlignmentAndInlineMarkdown() {
        show(
            """
            | Left | Centre | Right |
            |:---|:---:|---:|
            | **bold** | [the docs](https://example.com/docs) | 1.08 |
            | a | bb | 249.4 |
            """.trimIndent(),
        )
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/table-baseline-align.png")
    }

    companion object {
        private val LONG_CELL_TABLE = """
            | Item | Notes | Qty |
            |---|---|---|
            | Controller | Cabinet with redundant power supplies and a spare output module (60ch) | 2 |
            | Link | [the docs](https://example.com/docs) | 6 |
        """.trimIndent()
    }
}
