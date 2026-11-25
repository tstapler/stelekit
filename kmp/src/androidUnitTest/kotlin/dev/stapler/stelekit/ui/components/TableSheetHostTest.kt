// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.LadderAction
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.ui.WindowSizeClass
import dev.stapler.stelekit.ui.components.sheet.DraftText
import dev.stapler.stelekit.ui.components.sheet.SheetCloseReason
import dev.stapler.stelekit.ui.components.sheet.sheetWidthFraction
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 5.1 over a real `BlockStateManager` and in-memory repositories: open and close, flush, undo scoped to the
 * sheet's own entries, lifecycle closes, versions, the held conflict and the failed-commit path. Gradle-only (Compose host).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableSheetHostTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val h = SheetHarness(rule)

    @After
    fun tearDown() = h.close()

    private fun s(n: Int) = withCell(h.c0, CellAddress(2, 2), "$n")

    private fun ctrl(key: Key, shift: Boolean = false) {
        rule.onNodeWithTag("tableSheetHost").performKeyInput {
            keyDown(Key.CtrlLeft)
            if (shift) keyDown(Key.ShiftLeft)
            pressKey(key)
            if (shift) keyUp(Key.ShiftLeft)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
    }

    private fun noticeText(): String? =
        rule.onAllNodesWithTagOrNull("noticeText")

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagOrNull(tag: String): String? =
        runCatching {
            onNodeWithTag(tag).fetchSemanticsNode().config
                .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }
        }.getOrNull()

    @Test
    fun open_should_ShowADialogTitledWithThePageName() {
        h.show()
        rule.onNodeWithTag("tableSheetHost").assertExists()
        rule.onNodeWithTag("sheetTitle").assertExists()
        assertEquals("Groceries", rule.onNodeWithTag("sheetTitle").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.single()?.text)
    }

    @Test
    fun width_should_BeFullBelow840dpAndAboutNinetyPercentAtOrAbove() {
        assertEquals(1f, sheetWidthFraction(WindowSizeClass.COMPACT))
        assertEquals(1f, sheetWidthFraction(WindowSizeClass.MEDIUM))
        assertEquals(0.9f, sheetWidthFraction(WindowSizeClass.EXPANDED))
    }

    @Test
    fun close_should_CommitThePendingDraftAndFlushOnTheManagerScope() {
        h.show()
        h.hooks.draft = DraftText(CellAddress(2, 2), "7")
        rule.onNodeWithTag("sheetClose").performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { h.flushes == 1 }
        assertTrue(h.managerContent().contains("| 7 |"), h.managerContent())
        assertEquals(h.managerContent(), h.storedContent())
        assertEquals(listOf(SheetCloseReason.User), h.closes)
    }

    @Test
    fun undo_should_ShowThePreviousValue_AndNeverOpenTheRawEditor() {
        h.show()
        h.commit(s(7))
        assertEquals(s(7), h.managerContent())
        ctrl(Key.Z)
        assertEquals(h.c0, h.managerContent())
        assertNull(h.manager.editingBlockUuid.value)
        assertEquals(h.c0, h.session!!.content, "an adopted undo is shown without a banner")
        assertFalse(h.session!!.conflictHeld)
        ctrl(Key.Y)
        assertEquals(s(7), h.managerContent())
        assertNull(h.manager.editingBlockUuid.value)
    }

    @Test
    fun undo_should_NotRevertAnUnrelatedEntry_When_TheSheetHasNoCommitOfItsOwn() {
        var eUndone = false
        h.manager.record(undo = { eUndone = true }, redo = null)
        h.show()
        ctrl(Key.Z)
        assertFalse(eUndone, "the page's own entry E is not the sheet's to undo")
        assertNull(h.manager.editingBlockUuid.value)
        assertEquals("Nothing more to undo in this table", noticeText())
        assertTrue(h.closes.isEmpty())
    }

    @Test
    fun undo_should_StopAtTheSheetsOwnEntries_When_TwoCommitsAndThreePresses() {
        var eUndone = false
        h.manager.record(undo = { eUndone = true }, redo = null)
        h.show()
        h.commit(s(1))
        h.commit(s(2))
        repeat(3) { ctrl(Key.Z) }
        assertEquals(h.c0, h.managerContent())
        assertFalse(eUndone)
        assertEquals("Nothing more to undo in this table", noticeText())
    }

    @Test
    fun foreignUndoEntry_should_CloseTheSheetAndNotBeUndoneByIt() {
        var eUndone = false
        h.show()
        h.commit(s(1))
        rule.runOnUiThread { h.manager.record(undo = { eUndone = true }, redo = null) }
        rule.waitForIdle()
        assertEquals(listOf(SheetCloseReason.ForeignEntry), h.closes)
        assertFalse(eUndone)
    }

    @Test
    fun rawEditorOpening_should_CloseTheSheet() {
        h.show()
        rule.runOnUiThread { h.manager.requestEditBlock(h.uuid) }
        rule.waitForIdle()
        assertEquals(listOf(SheetCloseReason.EditorOpened), h.closes)
    }

    @Test
    fun deletedBlock_should_CloseTheSheetWithoutAnyCommit() {
        h.show()
        h.hooks.draft = DraftText(CellAddress(2, 2), "9")
        h.deleteBlock()
        rule.waitForIdle()
        assertEquals(listOf(SheetCloseReason.Removed), h.closes)
        assertEquals(0, h.hooks.pendingCommits, "no commit is issued for a block that cannot be found")
        assertNotNull(h.recovered.pinned, "the pending draft is kept as a recovered draft")
    }

    @Test
    fun contentThatIsNoLongerATable_should_CloseTheSheet() {
        h.show()
        h.foreignWrite("just some text")
        rule.waitForIdle()
        assertEquals(listOf(SheetCloseReason.NotATable), h.closes)
    }

    @Test
    fun graphChange_should_CloseTheSheetWithoutCommitting() {
        h.show()
        h.hooks.draft = DraftText(CellAddress(2, 2), "9")
        rule.runOnUiThread { h.graphKey = "graph-2" }
        rule.waitForIdle()
        assertEquals(listOf(SheetCloseReason.GraphChanged), h.closes)
        assertEquals(0, h.hooks.pendingCommits)
        assertNotNull(h.recovered.pinned)
    }

    @Test
    fun flagTurnedOff_should_CommitThePendingDraftAndClose() {
        h.show()
        h.hooks.draft = DraftText(CellAddress(2, 2), "5")
        rule.runOnUiThread { h.sheetEnabled = false }
        rule.waitForIdle()
        assertEquals(listOf(SheetCloseReason.FlagOff), h.closes)
        assertTrue(h.managerContent().contains("| 5 |"))
    }

    @Test
    fun readOnly_should_RefuseCommitsAndShowTheReason() {
        h.show(readOnly = true)
        assertFalse(h.commit(s(1)))
        assertEquals(h.c0, h.managerContent())
        assertEquals("This graph is read-only", noticeText())
    }

    @Test
    fun storedVersions_should_StrictlyIncrease_AcrossInlineSheetAndInlineCommits() {
        val seen = mutableListOf(h.managerVersion())
        h.manager.updateBlockContent(h.uuid, s(1), 1, restoreEditFocus = false) // inline, before the sheet opens, stale local version
        seen += h.managerVersion()
        h.show(initialContent = s(1))
        h.commit(s(2))
        seen += h.managerVersion()
        rule.runOnUiThread { h.session!!.close(SheetCloseReason.User) }
        rule.waitForIdle()
        h.manager.updateBlockContent(h.uuid, s(3), 1, restoreEditFocus = false) // inline again, after the sheet closed
        seen += h.managerVersion()
        assertEquals(seen.sorted().distinct(), seen, "strictly increasing: $seen")
        assertEquals(s(3), h.managerContent())
    }

    @Test
    fun externalChange_should_RebuildKeepingSelectionAndShowUpdatedFromDisk_When_NoDraftIsPending() {
        h.show()
        h.foreignWrite(s(42))
        rule.waitForIdle()
        assertEquals(s(42), h.session!!.content)
        assertEquals(s(42) to CellAddress(2, 2), h.hooks.rebuilds.last())
        assertEquals("Updated from disk", noticeText())
        assertFalse(h.session!!.conflictHeld)
    }

    @Test
    fun externalChange_should_ShowTheBannerAndKeepTheDraft_When_ADraftIsPending() {
        h.show()
        h.hooks.draft = DraftText(CellAddress(2, 2), "42")
        h.foreignWrite(s(9))
        rule.waitForIdle()
        rule.onNodeWithTag("conflictBanner").assertExists()
        assertEquals("42", h.hooks.draft?.text)
        assertEquals(0, h.hooks.rebuilds.size)
    }

    @Test
    fun back_should_DismissOnlyTheBanner_KeepDraftShowResolveChipAndHoldCommits() {
        h.show()
        h.hooks.draft = DraftText(CellAddress(2, 3), "42")
        h.foreignWrite(s(9))
        rule.waitForIdle()
        var action: LadderAction? = null
        rule.runOnUiThread { action = h.session!!.onBack(LadderKey.Back) }
        rule.waitForIdle()
        assertEquals(LadderAction.DismissBanner, action)
        rule.onNodeWithTag("conflictBanner").assertDoesNotExist()
        rule.onNodeWithTag("resolveChip").assertExists()
        rule.onNodeWithTag("tableSheetHost").assertExists()
        assertTrue(h.closes.isEmpty())
        assertEquals("42", h.hooks.draft?.text)
        assertEquals(s(9), h.storedContent(), "the sheet wrote nothing over the foreign content")
        assertFalse(h.commit(s(1)), "commits are held while the conflict is unresolved")
        rule.onNodeWithTag("conflictBanner").assertExists() // the held commit re-shows the banner
        rule.runOnUiThread { h.session!!.dismissBanner() }
        rule.waitForIdle()
        rule.onNodeWithTag("resolveChip").performClick()
        rule.onNodeWithTag("conflictBanner").assertExists()
    }

    @Test
    fun takeTheirs_should_DiscardTheDraftAndOfferUndoThatRestoresItIntoACellDraft() {
        h.show()
        h.hooks.draft = DraftText(CellAddress(2, 2), "42")
        h.foreignWrite(s(9))
        rule.waitForIdle()
        rule.onNodeWithTag("conflictTakeTheirs").performClick()
        rule.waitForIdle()
        assertEquals(1, h.hooks.discards)
        assertEquals(s(9), h.session!!.content)
        assertEquals("Discarded your edit", noticeText())
        rule.onNodeWithTag("noticeAction").performClick()
        rule.waitForIdle()
        assertEquals(DraftText(CellAddress(2, 2), "42"), h.hooks.restored.single())
        assertEquals(s(9), h.managerContent(), "restoring the draft makes no block commit")
    }

    @Test
    fun keepMine_should_CommitTheDraftOnTopOfTheirs() {
        h.show()
        h.hooks.draft = DraftText(CellAddress(2, 2), "42")
        h.foreignWrite(withCell(h.c0, CellAddress(2, 1), "Pear"))
        rule.waitForIdle()
        rule.onNodeWithTag("conflictKeepMine").performClick()
        rule.waitForIdle()
        assertEquals("| Item | Qty |\n|---|---|\n| Pear | 42 |", h.managerContent())
    }

    @Test
    fun editAsMarkdownWhileConflicted_should_HandTheDraftToARecoveredDraftAndClose() {
        h.show()
        h.hooks.draft = DraftText(CellAddress(2, 2), "42")
        h.foreignWrite(s(9))
        rule.waitForIdle()
        rule.onNodeWithTag("conflictEditMarkdown").performClick()
        rule.waitForIdle()
        assertEquals(listOf(SheetCloseReason.EditAsMarkdown), h.closes)
        assertEquals(DraftText(CellAddress(2, 2), "42"), h.recovered.pinned?.draft)
        assertEquals(s(9), h.storedContent(), "no file or row was written for the draft")
    }

    @Test
    fun closeWhileConflicted_should_RecoverTheDraft() {
        h.show()
        h.hooks.draft = DraftText(CellAddress(2, 2), "42")
        h.foreignWrite(s(9))
        rule.waitForIdle()
        rule.onNodeWithTag("sheetClose").performClick()
        rule.waitForIdle()
        assertEquals(DraftText(CellAddress(2, 2), "42"), h.recovered.pinned?.draft)
        assertEquals(0, h.hooks.pendingCommits)
    }

    @Test
    fun commitFailure_should_ShowRetryAndKeepDraft_When_RepositoryReturnsLeft() {
        h.show()
        h.failContentWrites(true)
        val draft = DraftText(CellAddress(2, 2), "7")
        h.hooks.draft = draft
        rule.runOnUiThread { h.session!!.commit(s(7), "edit B2", draft) }
        rule.waitForIdle()
        assertEquals("Couldn't save the change", noticeText())
        assertEquals(draft, h.hooks.restored.single(), "the draft is put back")
        assertEquals(h.c0, h.storedContent())
        h.failContentWrites(false)
        rule.onNodeWithTag("noticeAction").performClick()
        rule.waitForIdle()
        assertEquals(s(7), h.storedContent(), "Retry persisted it with a new commit")
        assertTrue(h.closes.isEmpty())
    }

    @Test
    fun dialogKeys_should_NotReachAParentKeyHandler_AndCtrlZStillUndoes() {
        h.show(wrapParent = true)
        h.commit(s(7))
        val before = h.parentKeys
        ctrl(Key.Z)
        assertEquals(h.c0, h.managerContent())
        assertEquals(before, h.parentKeys, "Dialog is its own window: the page's onGraphKeyEvent never sees the key")
    }

    @Test
    fun overflow_should_OfferEditAsMarkdownCopyAsFormulasShortcutsAndTheWidthsNote() {
        h.show()
        rule.onNodeWithTag("sheetOverflow").performClick()
        rule.onNodeWithTag("menuEditAsMarkdown").assertExists()
        rule.onNodeWithTag("menuCopyAsFormulas").assertExists()
        rule.onNodeWithTag("menuShortcuts").assertExists()
        rule.onNodeWithTag("menuColumnWidthsNote").assertExists()
        rule.onNodeWithTag("menuShortcuts").performClick()
        rule.onNodeWithTag("shortcutsPanel").assertExists()
        assertTrue(rule.onAllNodes(hasTestTag("shortcutRows")).fetchSemanticsNodes().isNotEmpty())
    }
}
