// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun switcher(onCopyPages: (() -> Unit)?) = rule.setContent {
        MaterialTheme {
            GraphSwitcher(
                currentGraphName = "Personal",
                availableGraphs = graphs,
                activeGraphId = "g1",
                onGraphSelected = {},
                onAddGraph = {},
                onRemoveGraph = {},
                onCopyPages = onCopyPages,
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
        switcher(null)
        rule.onNodeWithText("Personal").performClick()
        rule.onNodeWithText("Copy pages to...").assertDoesNotExist()
    }
}
