// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.merge.MergePropertyKeys
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.screens.copy.ConflictReviewContent
import dev.stapler.stelekit.ui.screens.copy.ConflictReviewState
import dev.stapler.stelekit.ui.screens.copy.ConflictReviewViewModel
import dev.stapler.stelekit.ui.screens.copy.ConflictRow
import dev.stapler.stelekit.ui.screens.copy.ConflictActionError
import dev.stapler.stelekit.ui.screens.copy.ConflictAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class ConflictReviewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val epoch = Instant.fromEpochMilliseconds(0)
    private val rowDescription = "Conflict on page Roadmap: original 'Draft v1', copied 'Draft v2' from Personal"

    private fun row(n: Int = 1, page: String = "Roadmap", original: String? = "Draft v1", copied: String = "Draft v2") = ConflictRow(
        block = Block(
            uuid = BlockUuid("00000000-0000-0000-0000-%012x".format(n)),
            pageUuid = PageUuid("00000000-0000-0000-0001-%012x".format(n)),
            content = copied,
            position = "a0",
            createdAt = epoch,
            updatedAt = epoch,
            properties = mapOf(MergePropertyKeys.CONFLICT to "true"),
        ),
        pageName = page,
        originalText = original,
        sourceGraph = "Personal",
    )

    private class Calls {
        val resolved = mutableListOf<String>()
        val removeRequested = mutableListOf<String>()
        val opened = mutableListOf<PageUuid>()
        var confirmed = 0
        var cancelled = 0
        var retryLoad = 0
        var retryAction = 0
        var closed = 0
    }

    private fun show(state: ConflictReviewState, calls: Calls = Calls(), content: @Composable (@Composable () -> Unit) -> Unit = { it() }): Calls {
        composeTestRule.setContent {
            MaterialTheme {
                content {
                    ConflictReviewContent(
                        state = state,
                        onResolve = { calls.resolved += it },
                        onRequestRemove = { calls.removeRequested += it },
                        onConfirmRemove = { calls.confirmed++ },
                        onCancelRemove = { calls.cancelled++ },
                        onOpenPage = { calls.opened += it },
                        onRetryLoad = { calls.retryLoad++ },
                        onRetryAction = { calls.retryAction++ },
                        onDismissActionError = {},
                        onLoadMore = {},
                        onClose = { calls.closed++ },
                    )
                }
            }
        }
        return calls
    }

    private fun loaded(vararg rows: ConflictRow, hasMore: Boolean = false) =
        ConflictReviewState(loading = false, rows = rows.toList(), hasMore = hasMore)

    // ── states ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `loading shows three skeleton rows and a polite Loading conflicts line`() {
        show(ConflictReviewState(loading = true))

        val node = composeTestRule.onNodeWithText("Loading conflicts...")
        node.assertIsDisplayed()
        assertEquals(LiveRegionMode.Polite, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        composeTestRule.onAllNodesWithContentDescription("Loading placeholder").assertCountEquals(3)
    }

    @Test
    fun `load failure shows a banner with Retry and Close`() {
        val calls = show(ConflictReviewState(loading = false, loadError = "disk gone"))

        composeTestRule.onNodeWithText("Could not load conflicts. disk gone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.onNodeWithText("Close").performClick()
        assertEquals(1, calls.retryLoad)
        assertEquals(1, calls.closed)
    }

    @Test
    fun `empty result says No conflicts to review with Close`() {
        val calls = show(loaded())

        composeTestRule.onNodeWithText("No conflicts to review.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Close").performClick()
        assertEquals(1, calls.closed)
    }

    @Test
    fun `action failure keeps an inline message with Retry`() {
        val err = ConflictActionError("Could not mark this conflict resolved on Roadmap.", ConflictAction.MarkResolved, row().key)
        val calls = show(loaded(row()).copy(actionError = err))

        composeTestRule.onNodeWithText(err.message).assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").performClick()
        assertEquals(1, calls.retryAction)
    }

    // ── rows ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `row is one merged node with the spoken description`() {
        show(loaded(row()))

        composeTestRule.onAllNodes(hasContentDescription(rowDescription)).assertCountEquals(1)
        composeTestRule.onNodeWithText("Conflict").assertIsDisplayed()
    }

    @Test
    fun `row custom actions carry the page name and invoke the callbacks`() {
        val r = row()
        val calls = show(loaded(r))

        val actions = composeTestRule.onNode(hasContentDescription(rowDescription))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(
            listOf("Mark resolved on page Roadmap", "Remove this block on page Roadmap", "Open page Roadmap"),
            actions.map { it.label },
        )
        composeTestRule.runOnIdle { actions.forEach { it.action() } }
        assertEquals(listOf(r.key), calls.resolved)
        assertEquals(listOf(r.key), calls.removeRequested)
        assertEquals(listOf(r.pageUuid), calls.opened)
    }

    @Test
    fun `buttons and tap-to-open call back`() {
        val r = row()
        val calls = show(loaded(r))

        composeTestRule.onNodeWithText("Mark resolved").performClick()
        composeTestRule.onNodeWithText("Remove this block").performClick()
        composeTestRule.onNodeWithText("Open page").performClick()
        assertEquals(listOf(r.key), calls.resolved)
        assertEquals(listOf(r.key), calls.removeRequested)
        assertEquals(listOf(r.pageUuid), calls.opened)
    }

    @Test
    fun `remaining count is a polite live region`() {
        show(loaded(row(1), row(2, page = "Plan")))

        val node = composeTestRule.onNodeWithText("2 conflicts to review")
        assertEquals(LiveRegionMode.Polite, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `missing original is described rather than omitted`() {
        show(loaded(row(original = null)))

        composeTestRule.onAllNodes(
            hasContentDescription("Conflict on page Roadmap: original not found, copied 'Draft v2' from Personal"),
        ).assertCountEquals(1)
    }

    // ── confirmation ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `remove confirmation uses the exact wording and three choices`() {
        val r = row()
        val calls = show(loaded(r).copy(confirmRemove = r))

        composeTestRule.onNodeWithText(
            "Remove this block? It will come back, flagged again, if you copy this page from Personal again. " +
                "To keep it from coming back, choose Mark resolved instead.",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Mark resolved instead").performClick()
        composeTestRule.onAllNodes(hasText("Remove this block") and hasClickAction()).onLast().performClick()
        assertEquals(2, calls.cancelled, "Cancel, then Mark resolved instead (which also closes the dialog)")
        assertEquals(listOf(r.key), calls.resolved)
        assertEquals(1, calls.confirmed)
    }

    // ── large text, RTL ──────────────────────────────────────────────────────────────────────

    @Test
    fun `at 200 percent font scale the row text and all three buttons stay displayed`() {
        show(loaded(row())) { inner ->
            FontScaled(2f) { inner() }
        }

        composeTestRule.onNodeWithText("Mark resolved").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remove this block").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open page").assertIsDisplayed()
        composeTestRule.onNodeWithText("Original: Draft v1").assertIsDisplayed()
    }

    @Test
    fun `in RTL the button order mirrors`() {
        show(loaded(row())) { inner ->
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { inner() }
        }

        val first = composeTestRule.onNodeWithText("Mark resolved").fetchSemanticsNode().boundsInRoot
        val last = composeTestRule.onNodeWithText("Open page").fetchSemanticsNode().boundsInRoot
        assertTrue(first.left > last.left, "Mark resolved should sit to the right of Open page in RTL")
    }
}
