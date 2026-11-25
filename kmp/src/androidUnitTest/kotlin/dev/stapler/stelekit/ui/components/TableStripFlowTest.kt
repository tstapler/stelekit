// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.components.sheet.overlayTopY
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stories 4.4a and 4.4c: the window-anchored strip, its actions, undo and redo, notices. Gradle-only (Compose host). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableStripFlowTest {
    @get:Rule
    val rule = createComposeRule()
    private val host = TableTestHost(rule)

    private var undoCalls = 0
    private var redoCalls = 0
    private val canUndo = MutableStateFlow(true)
    private val canRedo = MutableStateFlow(true)
    private val records = MutableStateFlow(0L)
    private val undoHost = TableUndoHost(
        undo = { undoCalls++ },
        redo = { redoCalls++ },
        canUndo = canUndo,
        canRedo = canRedo,
        recordCount = records,
    )

    private fun tapTouch(text: String) {
        rule.onNodeWithText(text).performTouchInput { click() }
        rule.waitForIdle()
    }

    private fun editor() = rule.onNode(hasSetTextAction())
    private fun strip(action: String) = rule.onNodeWithTag("strip:$action")

    private fun activate(action: String) {
        strip(action).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun stripTags(): List<String> = rule.onAllNodes(SemanticsMatcher("strip tag") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("strip:") == true
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("strip:") }

    @Test
    fun touchEdit_should_ShowDockedStripWithFixedFirstSlotAndDone_AndEveryTargetIs48dp() {
        host.show(sheet = true)
        tapTouch("3")
        strip("CancelEdit").assertIsDisplayed()
        strip("Done").assertIsDisplayed()
        for (tag in stripTags()) strip(tag).assertHeightIsAtLeast(48.dp)
        strip("Markdown").assertIsDisplayed()
        assertEquals(listOf("CancelEdit", "Done"), stripTags().take(2))
    }

    @Test
    fun stripBottomEdge_should_SitOnTheImeTop_OrTheWindowBottomWhenTheImeIsHidden() {
        assertEquals(800 - 60, overlayTopY(windowHeight = 800, popupHeight = 60, imeBottom = 0))
        assertEquals(800 - 300 - 60, overlayTopY(windowHeight = 800, popupHeight = 60, imeBottom = 300))
        assertEquals(0, overlayTopY(windowHeight = 100, popupHeight = 400, imeBottom = 300))
    }

    @Test
    fun navigateAndEdit_should_ShareOneActionOrderAndOneFirstSlotWidth() {
        host.show(sheet = true)
        tapTouch("3")
        val editTags = stripTags()
        val editFirst = strip("CancelEdit").getBoundsInRoot()
        editor().performTextReplacement("45")
        assertEquals(editFirst.width, strip("CancelEdit").getBoundsInRoot().width, "label does not change while typing")
        activate("Done")
        val navTags = stripTags()
        val navFirst = strip("Close").getBoundsInRoot()
        assertEquals(editFirst.width, navFirst.width)
        assertEquals(editFirst.left, navFirst.left)
        val shared = editTags.filter { it in navTags }
        assertEquals(shared, navTags.filter { it in editTags })
        assertEquals("Markdown", navTags.last())
        assertEquals("Markdown", editTags.last())
    }

    @Test
    fun stripShowAndHide_should_NotAnimate_OnTheTestClock() {
        host.show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("3").performTouchInput { click() }
        rule.mainClock.advanceTimeByFrame()
        strip("Done").assertExists()
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun cancelEdit_should_PostNothing_When_DraftUnchanged() {
        host.show()
        tapTouch("3")
        activate("CancelEdit")
        assertTrue(host.commits.isEmpty())
        rule.onAllNodes(hasTestTag("tableStrip")).assertCountEquals(1)
        rule.onAllNodes(androidx.compose.ui.test.hasText(TableMessages.DISCARDED_EDIT)).assertCountEquals(0)
        strip("Close").assertExists()
    }

    @Test
    fun cancelEdit_should_DiscardAndOfferUndo_When_DraftDiffers() {
        host.show()
        tapTouch("3")
        editor().performTextReplacement("45")
        activate("CancelEdit")
        assertTrue(host.commits.isEmpty())
        rule.onNodeWithText("3").assertExists()
        rule.onNodeWithText(TableMessages.DISCARDED_EDIT).assertExists()
        rule.onNodeWithTag("noticeAction").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        editor().assertTextEquals("45")
    }

    @Test
    fun done_should_CommitOnceAndNavigateOnTheSameCell() {
        host.show()
        tapTouch("3")
        editor().performTextReplacement("45")
        activate("Done")
        assertEquals(1, host.commits.size)
        assertTrue(host.commits.single().contains("| Apple | 45 | red |"))
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        strip("Close").assertExists()
    }

    @Test
    fun threeTaps_should_CopyARow_CellThenSelectRowThenCopy() {
        host.show()
        tapTouch("Apple")
        activate("SelectRow")
        activate("Copy")
        assertEquals(listOf("Apple\t3\tred"), host.clipboard.copies)
        rule.onNodeWithText("Copied 1 x 3 cells").assertExists()
    }

    @Test
    fun everyStripAction_should_CommitExactlyOnceFirst_ThenCopyReadsTheCommittedCell() {
        host.show(sheet = true)
        tapTouch("3")
        editor().performTextReplacement("45")
        activate("Copy")
        assertEquals(1, host.commits.size)
        assertEquals(listOf("45"), host.clipboard.copies)
        tapTouch("5")
        editor().performTextReplacement("6")
        activate("Maximize")
        assertEquals(2, host.commits.size)
        assertEquals(1, host.sheetOpened)
    }

    @Test
    fun navigateUndo_should_NameTheStepAndCallTheManagerOnce() {
        host.show(undoHost = undoHost)
        tapTouch("3")
        editor().performTextReplacement("45")
        activate("Done")
        records.value = 1
        rule.waitForIdle()
        rule.onNodeWithText("Undo: edit B2").assertExists()
        activate("Undo")
        assertEquals(1, undoCalls)
        rule.onNodeWithText(TableMessages.UNDONE).assertExists()
        activate("Redo")
        assertEquals(1, redoCalls)
    }

    @Test
    fun navigateUndo_should_BeDimmedFocusableAndInert_When_OnlyAForeignEntryExists() {
        host.show(undoHost = undoHost)
        tapTouch("3")
        activate("CancelEdit")
        strip("Undo").assert(
            SemanticsMatcher("dimmed with reason") {
                it.config.getOrNull(SemanticsProperties.StateDescription) == TableMessages.NOTHING_TO_UNDO_TABLE
            },
        )
        activate("Undo")
        assertEquals(0, undoCalls)
        rule.onNodeWithText(TableMessages.NOTHING_TO_UNDO_TABLE).assertExists()
    }

    @Test
    fun foreignUndoRecordWhileVisible_should_ResetTheDepth() {
        host.show(undoHost = undoHost)
        tapTouch("3")
        editor().performTextReplacement("45")
        activate("Done")
        records.value = 1
        rule.waitForIdle()
        rule.onNodeWithText("Undo: edit B2").assertExists()
        records.value = 2
        rule.waitForIdle()
        rule.onNodeWithText("Undo").assertExists()
    }

    @Test
    fun editUndo_should_BeDraftOnlyViaCtrlZ_AndNeverCommitOrCallTheManager() {
        host.show(undoHost = undoHost)
        tapTouch("3")
        editor().performTextReplacement("45")
        editor().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Z)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
        editor().assertTextEquals("3")
        assertTrue(host.commits.isEmpty())
        assertEquals(0, undoCalls)
        activate("Undo")
        rule.onNodeWithText(TableMessages.NOTHING_TO_UNDO_CELL).assertExists()
    }

    @Test
    fun why_should_ShowErrorDetailAsANoticeThatStaysUntilActedOn() {
        host.show(initial = "| A | B |\n|---|---|\n| 1 | =A2/0 |")
        tapTouch("#DIV/0!")
        activate("CancelEdit")
        activate("Why")
        rule.onNodeWithText("Division by zero").assertExists()
        rule.mainClock.advanceTimeBy(60_000)
        rule.onNodeWithText("Division by zero").assertExists()
        rule.onNodeWithText(TableMessages.EDIT).assertExists()
        rule.onNodeWithTag("noticeDismiss").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Division by zero")).assertCountEquals(0)
    }

    @Test
    fun nextColumnAndLineBreak_should_WorkWithoutAHardwareKey() {
        host.show()
        tapTouch("3")
        editor().performTextReplacement("9")
        activate("NextColumn")
        assertEquals(1, host.commits.size)
        assertTrue(host.commits[0].contains("| Apple | 9 | red |"))
        editor().assertTextEquals("red")
        editor().performTextReplacement("a")
        activate("LineBreak")
        activate("Done")
        assertTrue(host.commits.last().contains("a<br>"), host.commits.last())
    }

    @Test
    fun extendPicker_should_ListFiveChoices_AndGrowTheRange() {
        host.show()
        tapTouch("Apple")
        activate("CancelEdit")
        activate("Extend")
        for (choice in listOf("Right", "Down", "To row end", "To column end", "To table end")) rule.onNodeWithText(choice).assertExists()
        rule.onNodeWithText("To row end").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onAllNodes(androidx.compose.ui.test.isSelected()).assertCountEquals(3)
    }

    @Test
    @Config(sdk = [29], qualifiers = "w800dp-h360dp")
    fun landscape_should_CollapseTheStripToFirstSlotDoneMore_AndPinMarkdown() {
        host.show()
        tapTouch("3")
        strip("CancelEdit").assertIsDisplayed()
        strip("Done").assertIsDisplayed()
        strip("More").assertIsDisplayed()
        strip("Markdown").assertIsDisplayed()
        rule.onAllNodes(hasTestTag("strip:Copy")).assertCountEquals(0)
        activate("More")
        strip("Copy").assertExists()
    }

    @Test
    fun hardwareEdit_should_NotShowTheStripUntilNavigate() {
        host.show()
        rule.onNodeWithText("3").performMouseInput { click() }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("tableStrip")).assertCountEquals(0)
    }

    @Test
    fun rootCount_should_IncludeThePopupOnceTheStripShows() {
        host.show()
        assertEquals(1, rule.onAllNodes(isRoot()).fetchSemanticsNodes().size)
        tapTouch("3")
        assertEquals(2, rule.onAllNodes(isRoot()).fetchSemanticsNodes().size)
        rule.onAllNodes(isRoot()).onFirst().assertExists()
    }
}
