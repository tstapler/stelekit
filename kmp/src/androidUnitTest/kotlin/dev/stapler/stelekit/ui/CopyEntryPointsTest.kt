// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.stapler.stelekit.merge.SourcePlatform
import dev.stapler.stelekit.merge.offeredDirections
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.ui.components.GraphSwitcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** The single "Copy pages to..." sidebar action that replaced "export pages for merge" / "Merge captured pages". */
@RunWith(RobolectricTestRunner::class)
class CopyEntryPointsTest {
    @get:Rule
    val rule = createComposeRule()

    private val graphs = listOf(GraphInfo(id = GraphId("g1"), path = "/g1", displayName = "Personal", addedAt = 0L))
    private val twoGraphs = graphs + GraphInfo(id = GraphId("g2"), path = "/g2", displayName = "Work", addedAt = 0L)

    private fun switcher(
        platform: SourcePlatform = SourcePlatform.Desktop,
        available: List<GraphInfo> = graphs,
        onFromGraph: ((GraphInfo) -> Unit)? = null,
        onCopyPages: (() -> Unit)?,
    ) = rule.setContent {
        MaterialTheme {
            GraphSwitcher(
                currentGraphName = "Personal",
                availableGraphs = available,
                activeGraphId = "g1",
                onGraphSelected = {},
                onAddGraph = {},
                onRemoveGraph = {},
                onCopyPages = onCopyPages,
                copyDirection = offeredDirections(platform).first(),
                onCopyPagesFromGraph = onFromGraph,
            )
        }
    }

    @Test
    fun copy_pages_action_with_subtitle_invokes_callback() {
        var opened = 0
        switcher { opened++ }
        rule.onNodeWithText("Personal").performClick()
        rule.onNodeWithText("Adds pages; combines with existing ones").assertExists()
        rule.onNodeWithText("Copy pages to...").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun old_merge_actions_are_gone() {
        switcher { }
        rule.onNodeWithText("Personal").performClick()
        rule.onNodeWithText("Copy pages from this graph...").assertDoesNotExist()
        rule.onNodeWithText("Merge captured pages here", substring = true).assertDoesNotExist()
    }

    @Test
    fun action_is_hidden_when_copy_is_unavailable() {
        switcher(onCopyPages = null)
        rule.onNodeWithText("Personal").performClick()
        rule.onNodeWithText("Copy pages to...").assertDoesNotExist()
    }

    private fun assertPushOnly(platform: SourcePlatform) {
        switcher(onCopyPages = {}, platform = platform, available = twoGraphs, onFromGraph = {})
        rule.onNodeWithText("Personal").performClick()
        rule.onNodeWithText("Copy pages to...").assertExists()
        rule.onNodeWithText("Copy pages from...").assertDoesNotExist()
        rule.onNodeWithContentDescription("More actions for Work").assertDoesNotExist()
    }

    private fun assertPullOnly(platform: SourcePlatform) {
        var opened = 0
        var from: GraphInfo? = null
        switcher(onCopyPages = { opened++ }, platform = platform, available = twoGraphs, onFromGraph = { from = it })
        rule.onNodeWithText("Personal").performClick()
        rule.onNodeWithText("Copy pages to...").assertDoesNotExist()
        rule.onNodeWithText("Copy pages from...").performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("Personal").performClick()
        // The active graph's own row has no overflow; the other graph's does.
        rule.onNodeWithContentDescription("More actions for Personal").assertDoesNotExist()
        rule.onNodeWithContentDescription("More actions for Work").performClick()
        rule.onNodeWithText("Copy pages from Work to Personal").performClick()
        assertEquals("Work", from?.displayName)
    }

    @Test
    fun android_shows_push_only() = assertPushOnly(SourcePlatform.Android)

    @Test
    fun desktop_shows_push_only() = assertPushOnly(SourcePlatform.Desktop)

    @Test
    fun ios_shows_pull_only_with_row_overflow() = assertPullOnly(SourcePlatform.Ios)

    @Test
    fun web_shows_pull_only_with_row_overflow() = assertPullOnly(SourcePlatform.Web)
}
