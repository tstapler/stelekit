// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.ui.screens.ConsoleScreen
import dev.stapler.stelekit.wiring.FakeSettings
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 3.2f: the `sql` result grid on the console screen: row announcements, the row-cap footer, and the
 * Export CSV / EXPLAIN chips at 48dp. Gradle-only (Robolectric): not registered in `AllAndroidUnitTests`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class ConsoleResultGridTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var session: ConsoleSession? = null

    @After
    fun tearDown() {
        session?.close()
    }

    private class TableProbe(private val table: ResultTable) : ConsoleCommand {
        override val name = "probe"
        override val summary = "probe"
        override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
            out.table(table)
            return Unit.right()
        }
    }

    private fun showTable(table: ResultTable) {
        val settings = FakeSettings().also { it.putBoolean(DeveloperMode.KEY, true) }
        val s = ConsoleSession(DeveloperMode(settings), ConsoleRegistry(listOf(TableProbe(table))), batchMillis = 5)
        session = s
        composeRule.setContent { ConsoleScreen(s) }
        s.submit("probe")
        composeRule.waitUntil(WAIT_MS) { s.scrollback.lines.value.any { it.table != null } }
    }

    private val sample = ResultTable(
        columns = listOf("name", "updated_at"),
        rows = listOf(listOf("Alpha", "10"), listOf("Beta", "20"), listOf(null, "30")),
        truncatedAtRows = 3,
        explainSql = "select name, updated_at from pages",
    )

    @Test
    fun grid_should_AnnounceEachRowAsOneNodeWithPositionAndValues() {
        showTable(sample)
        composeRule.onNodeWithTag("console-result-grid").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Columns 2: name, updated_at").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Row 2 of 3, name: Beta, updated_at: 20").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Row 3 of 3, name: empty, updated_at: 30").assertIsDisplayed()
    }

    @Test
    fun grid_should_ShowRowCapFooter_AndKeepBothChipsAtLeast48dp() {
        showTable(sample)
        composeRule.onNodeWithText("Showing first 3 rows").assertIsDisplayed()
        composeRule.onNodeWithTag("console-export-csv").assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(MIN_TARGET)
        composeRule.onNodeWithTag("console-explain-chip").assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(MIN_TARGET)
    }

    @Test
    fun explainChip_should_PutTheQuotedExplainStatementOnTheCommandLine() {
        showTable(sample)
        composeRule.onNodeWithTag("console-explain-chip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("console-input").assertTextContains("sql \"EXPLAIN QUERY PLAN select name", substring = true)
    }

    @Test
    fun grid_should_OmitExplainChipAndFooter_When_QueryFinishedWithinLimits() {
        showTable(sample.copy(truncatedAtRows = null, explainSql = null))
        composeRule.onNodeWithTag("console-export-csv").assertIsDisplayed()
        composeRule.onNodeWithTag("console-explain-chip").assertDoesNotExist()
        composeRule.onNodeWithTag("console-row-cap-footer").assertDoesNotExist()
    }


    private companion object {
        val MIN_TARGET = 48.dp
        const val WAIT_MS = 5_000L
    }
}
