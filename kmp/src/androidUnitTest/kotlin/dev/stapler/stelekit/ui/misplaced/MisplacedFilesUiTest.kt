// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.misplaced

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.db.ProposedResolution
import dev.stapler.stelekit.db.misplaced.MisplacedFile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Robolectric Compose tests for the misplaced-files banner and review dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class MisplacedFilesUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun item(name: String, hasCopy: Boolean, proposal: ProposedResolution?) = MisplacedItem(
        MisplacedFile("journals/$name.md", MisplacedFile.Kind.JOURNAL, name, hasCopy, "1:1"),
        proposal,
    )

    private fun show(
        state: MisplacedFilesUiState,
        onMove: (MisplacedItem) -> Unit = {},
        onIgnoreAll: () -> Unit = {},
        onMergeAnyway: (MisplacedItem) -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                MisplacedFilesDialog(state, onMove = onMove, onMergeAnyway = onMergeAnyway, onIgnore = {}, onMoveAll = {}, onIgnoreAll = onIgnoreAll, onClose = {})
            }
        }
    }

    @Test
    fun banner_should_SayHowManyNotesAreOutside_AndOfferReviewAndIgnore() {
        composeTestRule.setContent { MaterialTheme { MisplacedFilesBanner(count = 3, onReview = {}, onIgnore = {}) } }

        composeTestRule.onNodeWithText("3 notes are outside your notes folder").assertExists()
        composeTestRule.onNodeWithText("Review").assertIsEnabled()
        composeTestRule.onNodeWithText("Ignore").assertIsEnabled()
    }

    @Test
    fun banner_should_UseTheSingular_When_ThereIsOneNote() {
        composeTestRule.setContent { MaterialTheme { MisplacedFilesBanner(count = 1, onReview = {}, onIgnore = {}) } }

        composeTestRule.onNodeWithText("1 note is outside your notes folder").assertExists()
    }

    @Test
    fun dialog_should_ExplainEachItemInPlainWords() {
        show(
            MisplacedFilesUiState(
                items = listOf(
                    item("only-here", hasCopy = false, proposal = ProposedResolution.MoveAsNew),
                    item("both", hasCopy = true, proposal = ProposedResolution.MergeIntoExisting(2)),
                    item("clash", hasCopy = true, proposal = ProposedResolution.CannotMerge("the stray copy has content outside its block list")),
                ),
                dialogVisible = true,
            ),
        )

        composeTestRule.onNodeWithText("Only here. Moving it adds it to your notes.").assertExists()
        composeTestRule.onNodeWithText("Also in your notes. Merging adds 2 blocks to that page and removes nothing.").assertExists()
        composeTestRule.onNodeWithText("Can't merge automatically: the stray copy has content outside its block list. Both copies are kept.").assertExists()
        composeTestRule.onNodeWithText("Move all (2)").assertIsEnabled()
    }

    @Test
    fun dialog_should_ShowCheckingAndHoldBackMoving_UntilThePreviewIsReady() {
        show(MisplacedFilesUiState(items = listOf(item("pending", hasCopy = false, proposal = null)), dialogVisible = true))

        composeTestRule.onNodeWithText("Checking…").assertExists()
        composeTestRule.onNodeWithText("Move").assertIsNotEnabled()
    }

    @Test
    fun dialog_should_NotOfferToMoveWhatCannotBeMergedSafely() {
        show(
            MisplacedFilesUiState(
                items = listOf(item("clash", hasCopy = true, proposal = ProposedResolution.CannotMerge("they conflict"))),
                dialogVisible = true,
            ),
        )

        composeTestRule.onNodeWithText("Merge").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Move all").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Ignore").assertIsEnabled()
    }

    @Test
    fun dialog_should_OfferMergeAnywayOnlyForARefusalThatCanBeAppended() {
        val appended = mutableListOf<String>()
        show(
            MisplacedFilesUiState(
                items = listOf(
                    item("props", hasCopy = true, proposal = ProposedResolution.CannotMerge("the stray copy has content outside its block list", canAppend = true)),
                    item("unreadable", hasCopy = true, proposal = ProposedResolution.CannotMerge("the copy in your notes cannot be read")),
                ),
                dialogVisible = true,
            ),
            onMergeAnyway = { appended += it.file.name },
        )

        composeTestRule.onAllNodesWithText("Merge anyway").assertCountEquals(1)
        composeTestRule.onNodeWithText("Merge anyway").performClick()
        assertEquals(listOf("props"), appended)
    }

    @Test
    fun dialog_should_MoveJustTheChosenItem_AndLetTheUserIgnoreEverything() {
        val moved = mutableListOf<String>()
        var ignoredAll = false
        show(
            MisplacedFilesUiState(
                items = listOf(item("both", hasCopy = true, proposal = ProposedResolution.MergeIntoExisting(1))),
                dialogVisible = true,
            ),
            onMove = { moved += it.file.name },
            onIgnoreAll = { ignoredAll = true },
        )

        composeTestRule.onNodeWithText("Merge").performClick()
        composeTestRule.onNodeWithText("Ignore all").performClick()

        assertEquals(listOf("both"), moved)
        assertEquals(true, ignoredAll)
    }

    @Test
    fun dialog_should_DisableActionsWhileWorking() {
        show(
            MisplacedFilesUiState(
                items = listOf(item("both", hasCopy = true, proposal = ProposedResolution.MergeIntoExisting(1))),
                dialogVisible = true,
                busy = true,
            ),
        )

        composeTestRule.onNodeWithText("Merge").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Ignore all").assertIsNotEnabled()
    }
}
