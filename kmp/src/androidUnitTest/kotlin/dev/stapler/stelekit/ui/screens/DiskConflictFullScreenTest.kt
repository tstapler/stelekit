// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for [DiskConflictFullScreen] plus its pure diff helpers. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class DiskConflictFullScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var dismissed = 0

    private fun show(local: String, disk: String) {
        composeTestRule.setContent {
            MaterialTheme {
                DiskConflictFullScreen(localContent = local, diskContent = disk, onDismiss = { dismissed++ })
            }
        }
    }

    @Test
    fun screen_should_showIdenticalMessage_when_contentMatches() {
        show("same", "same")
        composeTestRule.onNodeWithText("Compare versions").assertExists()
        composeTestRule.onNodeWithText("No differences — the disk version matches your edit.").assertExists()
    }

    @Test
    fun screen_should_showDiskContent_when_localBlank() {
        show("  ", "disk body")
        composeTestRule.onNodeWithText("(no local edit to compare)").assertExists()
        composeTestRule.onNodeWithText("disk body").assertExists()
    }

    @Test
    fun screen_should_renderDiffMarkers_when_different() {
        show("keep\nold line\ntail", "keep\nnew line\ntail")
        composeTestRule.onNodeWithText("  keep").assertExists()
        composeTestRule.onNodeWithText("- old line").assertExists()
        composeTestRule.onNodeWithText("+ new line").assertExists()
        composeTestRule.onNodeWithText("  tail").assertExists()
    }

    @Test
    fun screen_should_dismiss_when_closeClicked() {
        show("a", "b")
        composeTestRule.onNodeWithContentDescription("Close comparison").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun computeDiskDiffState_should_classifyInputs() {
        assertTrue(computeDiskDiffState("", "x") is DiskDiffState.NoLocalEdit)
        assertTrue(computeDiskDiffState("x", "x") is DiskDiffState.Identical)
        assertTrue(computeDiskDiffState("x", "y") is DiskDiffState.Different)
    }

    @Test
    fun buildDiffLines_should_handleInsertDeleteChange() {
        val local = "a\nb\nc\nd"
        val disk = "a\nB\nc\nd\ne"
        val state = computeDiskDiffState(local, disk) as DiskDiffState.Different
        val lines = buildDiffLines(state.patch, local.lines())
        assertTrue(lines.contains(DiffLineItem("b", DiffLineKind.REMOVED)))
        assertTrue(lines.contains(DiffLineItem("B", DiffLineKind.ADDED)))
        assertTrue(lines.contains(DiffLineItem("e", DiffLineKind.ADDED)))
        assertTrue(lines.contains(DiffLineItem("a", DiffLineKind.UNCHANGED)))

        val delOnly = computeDiskDiffState("x\ny", "x") as DiskDiffState.Different
        assertTrue(buildDiffLines(delOnly.patch, "x\ny".lines()).contains(DiffLineItem("y", DiffLineKind.REMOVED)))
    }
}
