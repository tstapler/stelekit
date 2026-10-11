// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.capture.InboxItem
import dev.stapler.stelekit.capture.InboxItemStatus
import dev.stapler.stelekit.capture.InboxSlot
import dev.stapler.stelekit.capture.QuarantinedShare
import dev.stapler.stelekit.capture.RetryResult
import dev.stapler.stelekit.capture.ShareInboxState
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.ui.components.QueuedSharesBadge
import dev.stapler.stelekit.ui.components.ShareInboxPanelContent
import dev.stapler.stelekit.ui.components.ShareInboxStartNotice
import dev.stapler.stelekit.ui.components.StartNoticeGate
import dev.stapler.stelekit.ui.components.queuedAtLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ShareInboxPanelTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val work = InboxSlot.Graph(GraphId("bbbbbbbbbbbbbbbb"))
    private val names = { slot: InboxSlot -> if (slot == work) "Work graph" else "Other graph" }
    private val longText = "meeting notes " + "x".repeat(120)

    private fun item(id: String, text: String? = "meeting notes $id", status: InboxItemStatus = InboxItemStatus.Ready) =
        InboxItem(work, id, createdAtEpochMs = 0L, text = text, status = status, hasImage = false)

    private class FakeActions(val state: MutableStateFlow<ShareInboxState>) : ShareInboxActions {
        val copied = mutableListOf<String>()
        val retried = mutableListOf<String>()
        var retryResult = RetryResult.Drained

        override suspend fun copyText(item: InboxItem): String? = item.text
        override suspend fun discard(item: InboxItem): Boolean {
            state.value = state.value.copy(items = state.value.items - item)
            return true
        }
        override suspend fun retryNow(item: InboxItem): RetryResult {
            retried += item.captureId
            return retryResult
        }
    }

    private fun show(
        state: ShareInboxState,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ): FakeActions {
        val flow = MutableStateFlow(state)
        val actions = FakeActions(flow)
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalLayoutDirection provides direction,
            ) {
                MaterialTheme {
                    val current = flow.collectAsState().value
                    ShareInboxPanelContent(current, names, actions, onCopyToClipboard = { actions.copied += it }, nowEpochMs = { 0L })
                }
            }
        }
        return actions
    }

    private fun row(id: String): SemanticsNodeInteraction {
        val time = queuedAtLabel(0L, 0L)
        return composeTestRule.onNodeWithContentDescription("Queued share for Work graph: 'meeting notes $id', $time")
    }

    private fun SemanticsNodeInteraction.runAction(label: String) {
        val actions = assertNotNull(fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions))
        val action = actions.first { it.label == label }
        composeTestRule.runOnUiThread { action.action() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun row_should_BeOneMergedNode_WithGraphNamedCustomActions() {
        show(ShareInboxState(items = listOf(item("a"))))

        val labels = row("a").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)!!.map { it.label }

        assertEquals(listOf("Copy text for Work graph", "Discard share for Work graph", "Retry now for Work graph"), labels)
    }

    @Test
    fun count_should_BeAPoliteLiveRegion() {
        show(ShareInboxState(items = listOf(item("a"), item("b"))))

        val node = composeTestRule.onNodeWithText("2 shares queued for Work graph").fetchSemanticsNode()

        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun copyTextAction_should_PutTheShareOnTheClipboard() {
        val actions = show(ShareInboxState(items = listOf(item("a"))))

        row("a").runAction("Copy text for Work graph")

        assertEquals(listOf("meeting notes a"), actions.copied)
        composeTestRule.onNodeWithText("Copied the share for Work graph").assertIsDisplayed()
    }

    @Test
    fun discard_should_ConfirmWithFirst80Chars_OfferCopy_ThenRemoveAndFocusNextItem() {
        val actions = show(ShareInboxState(items = listOf(item("a", longText), item("b"))))

        composeTestRule.onNodeWithContentDescription("Queued share for Work graph: '${"meeting notes xxxxxxxxxx"}...', ${queuedAtLabel(0L, 0L)}")
            .runAction("Discard share for Work graph")

        composeTestRule.onNodeWithText("Discard this share?").assertIsDisplayed()
        composeTestRule.onNodeWithText("'${longText.take(80)}...'").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Copy text").onLast().assertIsDisplayed()
        assertTrue(actions.state.value.items.size == 2, "nothing is removed before confirming")

        composeTestRule.onAllNodesWithText("Discard").onLast().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("b"), actions.state.value.items.map { it.captureId })
        row("b").assertIsFocused()
    }

    @Test
    fun discardDialog_should_OfferCopyText_BeforeDiscarding() {
        val actions = show(ShareInboxState(items = listOf(item("a"))))
        row("a").runAction("Discard share for Work graph")

        composeTestRule.onAllNodesWithText("Copy text").onLast().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("meeting notes a"), actions.copied)
        assertEquals(1, actions.state.value.items.size)
    }

    @Test
    fun retryNow_should_CallDrain_AndAnnounceOutcome() {
        val actions = show(ShareInboxState(items = listOf(item("a"))))
        actions.retryResult = RetryResult.NotReady

        row("a").runAction("Retry now for Work graph")

        assertEquals(listOf("a"), actions.retried)
        composeTestRule.onNodeWithText("Work graph isn't open yet. The share is still queued").assertIsDisplayed()
    }

    @Test
    fun quarantined_should_ShowCouldntBeRead_WithCopyOnlyWhenRecoverable() {
        val actions = show(
            ShareInboxState(
                quarantined = listOf(QuarantinedShare("a.json", "rescued"), QuarantinedShare("b.json", null)),
            ),
        )

        assertEquals(2, composeTestRule.onAllNodesWithContentDescription("1 share couldn't be read", substring = true).fetchSemanticsNodes().size)
        composeTestRule.onNodeWithText("Copy text").performClick()

        assertEquals(listOf("rescued"), actions.copied)
    }

    @Test
    fun newerAppItem_should_BeKeptUntouched_WithNoActions() {
        show(ShareInboxState(items = listOf(item("a", text = null, status = InboxItemStatus.NeedsNewerApp(2)))))

        composeTestRule.onNodeWithText("Needs a newer app version").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Copy text", substring = true).assertCountEquals(0)
    }

    @Test
    fun layout_should_Render_At200PercentFontScale_AndRtl() {
        show(ShareInboxState(items = listOf(item("a"))), fontScale = 2f, direction = LayoutDirection.Rtl)

        composeTestRule.onNodeWithText("Copy text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry now").assertExists()
    }

    @Test
    fun badge_should_ShowCountAndText_AndOpenPanel() {
        var opened = 0
        composeTestRule.setContent {
            MaterialTheme {
                QueuedSharesBadge(ShareInboxState(items = listOf(item("a"), item("b"))), names, { opened++ })
            }
        }

        composeTestRule.onNodeWithText("2 shares queued for Work graph").assertIsDisplayed().performClick()

        assertEquals(1, opened)
        composeTestRule.onNode(hasContentDescription("2 shares queued for Work graph. Opens queued shares")).assertExists()
    }

    @Test
    fun badge_should_BeAbsent_WhenInboxIsEmpty() {
        composeTestRule.setContent { MaterialTheme { QueuedSharesBadge(ShareInboxState(), names, {}) } }

        composeTestRule.onAllNodes(hasText("queued", substring = true)).assertCountEquals(0)
    }

    private fun showStartNotice(state: ShareInboxState, gate: StartNoticeGate, onView: () -> Unit) {
        val host = SnackbarHostState()
        composeTestRule.setContent {
            MaterialTheme {
                SnackbarHost(host)
                ShareInboxStartNotice("k", { state }, onView, names, host, gate)
            }
        }
    }

    @Test
    fun startNotice_should_ShowOnce_WithViewActionOpeningPanel() {
        var viewed = 0
        val gate = StartNoticeGate()
        showStartNotice(ShareInboxState(items = listOf(item("a"), item("b"))), gate) { viewed++ }

        composeTestRule.onNodeWithText("2 shares are queued for Work graph").assertIsDisplayed()
        composeTestRule.onNodeWithText("View").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, viewed)
        assertTrue(!gate.tryShow(), "the gate stays closed after the first show")
    }

    @Test
    fun startNotice_should_NotShow_WhenGateAlreadyUsed() {
        val gate = StartNoticeGate().also { it.tryShow() }
        showStartNotice(ShareInboxState(items = listOf(item("a"))), gate) {}

        composeTestRule.onAllNodes(hasText("queued", substring = true)).assertCountEquals(0)
    }

    @Test
    fun startNotice_should_NotShow_WhenNothingQueued() {
        showStartNotice(ShareInboxState(), StartNoticeGate()) {}

        composeTestRule.onAllNodes(hasText("queued", substring = true)).assertCountEquals(0)
    }

    @Test
    fun queuedAtLabel_should_ReadTodayYesterdayOrDate() {
        val zone = TimeZone.UTC
        val noon = 1_760_000_000_000L // 2025-10-09T08:53:20Z
        assertEquals("Today 08:53", queuedAtLabel(noon, noon, zone))
        assertEquals("Yesterday 08:53", queuedAtLabel(noon - 86_400_000L, noon, zone))
        assertEquals("2025-10-07 08:53", queuedAtLabel(noon - 2 * 86_400_000L, noon, zone))
    }
}
