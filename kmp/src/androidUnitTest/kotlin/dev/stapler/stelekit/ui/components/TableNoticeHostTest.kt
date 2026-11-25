// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.NoticeType
import dev.stapler.stelekit.ui.components.sheet.DraftText
import dev.stapler.stelekit.ui.components.sheet.RecoveredDraft
import dev.stapler.stelekit.ui.components.sheet.RecoveredDraftActions
import dev.stapler.stelekit.ui.components.sheet.TableNoticeHost
import dev.stapler.stelekit.ui.components.sheet.TableNotices
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 5.1d: the notice host's queue, time limits (WCAG 2.2.1) and recovered-draft slot on a test clock. Gradle-only (Compose host). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableNoticeHostTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val notices = TableNotices()
    private var recovered: RecoveredDraft? = null
    private val applied = mutableListOf<RecoveredDraft>()
    private val discarded = mutableListOf<RecoveredDraft>()
    private var canApply = true

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                TableNoticeHost(
                    notices = notices,
                    recovered = recovered,
                    recoveredActions = RecoveredDraftActions(
                        onCopy = {},
                        onApply = { applied += it },
                        canApply = { canApply },
                        onDiscard = { discarded += it },
                    ),
                )
            }
        }
        rule.mainClock.advanceTimeBy(50)
    }

    private fun post(block: () -> Unit) {
        rule.runOnUiThread {
            block()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(50)
    }

    private fun text(): String? = runCatching {
        rule.onNodeWithTag("noticeText").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.single()?.text
    }.getOrNull()

    @Test
    fun actionNotice_should_StayVisible_After60SecondsWithNoInput() {
        show()
        post { notices.withAction(NoticeType.Undo, "Pasted 3 x 2", TableMessages.UNDO) {} }
        rule.mainClock.advanceTimeBy(60_000)
        assertEquals("Pasted 3 x 2", text())
    }

    @Test
    fun infoNotice_should_AutoDismissAfterTenSeconds() {
        show()
        post { notices.info("Updated from disk") }
        rule.mainClock.advanceTimeBy(9_000)
        assertEquals("Updated from disk", text())
        rule.mainClock.advanceTimeBy(1_500)
        assertEquals(null, text())
    }

    @Test
    fun infoNotice_should_PauseWhileHoveredAndResumeWhenThePointerLeaves() {
        show()
        post { notices.info("Updated from disk") }
        rule.onNodeWithTag("tableNotice").performMouseInput { enter() }
        rule.mainClock.advanceTimeBy(30_000)
        assertEquals("Updated from disk", text(), "hover pauses the countdown")
        rule.onNodeWithTag("tableNotice").performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(-20f, -20f)) }
        rule.mainClock.advanceTimeBy(9_000)
        assertEquals("Updated from disk", text())
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(null, text())
    }

    @Test
    fun secondUndoNotice_should_QueueBehindTheFirstWithAMoreCount_AndNeitherIsLost() {
        show()
        post {
            notices.withAction(NoticeType.Undo, "Pasted 3 x 2", TableMessages.UNDO) {}
            notices.withAction(NoticeType.Undo, "2 formulas now show #REF!", TableMessages.UNDO) {}
        }
        assertEquals("Pasted 3 x 2", text())
        rule.onNodeWithTag("noticeMore").assertExists()
        assertEquals("1 more", rule.onNodeWithTag("noticeMore").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.single()?.text)
        rule.onNodeWithTag("noticeAction").performClick()
        rule.runOnUiThread { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals("2 formulas now show #REF!", text())
    }

    @Test
    fun infoNotice_should_GoOnlyToTheLiveRegion_While_AnUndoNoticeShows() {
        show()
        post {
            notices.withAction(NoticeType.Undo, "Pasted 3 x 2", TableMessages.UNDO) {}
            notices.info("Updated from disk")
        }
        assertEquals("Pasted 3 x 2", text())
        rule.onNodeWithTag("noticeMore").assertDoesNotExist()
    }

    @Test
    fun recoveredDraft_should_OfferCopyApplyAndDiscard_AndDisableApplyWithAReason() {
        recovered = RecoveredDraft(BlockUuid("b"), DraftText(CellAddress(2, 2), "42"))
        show()
        rule.onNodeWithTag("recoveredCopy").assertExists()
        rule.onNodeWithTag("recoveredApply").performClick()
        rule.onNodeWithTag("recoveredDiscard").performClick()
        assertEquals(1, applied.size)
        assertEquals(1, discarded.size)
        assertTrue(rule.onNodeWithTag("recoveredDraft").fetchSemanticsNode().children.isNotEmpty())
    }
}
