// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.db.StorageLocationResolver
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.ui.components.GraphSwitcher
import dev.stapler.stelekit.ui.components.UnifiedLocationPickerBrowseRowTag
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Idiom-review MUST FIX: the Sidebar/`GraphSwitcher` "Move storage location…" flow's
 * [dev.stapler.stelekit.ui.components.UnifiedLocationPicker] must call `onBrowseClickedForMove`
 * synchronously from the "Browse…" row's own click handler — before the suspend
 * `onBrowseRequestedForMove` runs — so the platform's native directory picker (e.g. wasmJs's
 * `showDirectoryPicker()`) is invoked inside the click's transient-user-activation window. Every
 * other `UnifiedLocationPicker` call site in this diff (`App.kt`'s new-graph picker,
 * `FolderSyncSettings`'s move flow, `GitSetupScreen`'s clone picker) already threads this hook;
 * this test guards the Sidebar's move-storage-location flow specifically.
 */
class GraphSwitcherMoveStorageBrowseClickTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val graph = GraphInfo(
        id = GraphId("real-id-001"),
        path = "/home/user/my-notes",
        displayName = "Real Graph",
        addedAt = 0L,
    )

    @Test
    fun graphSwitcher_invokesOnBrowseClickedForMove_synchronously_beforeOnBrowseRequestedForMove_When_BrowseRowTapped() {
        val events = mutableListOf<String>()

        composeTestRule.setContent {
            MaterialTheme {
                GraphSwitcher(
                    currentGraphName = graph.displayName,
                    availableGraphs = listOf(graph),
                    activeGraphId = graph.id.value,
                    onGraphSelected = {},
                    onAddGraph = {},
                    onRemoveGraph = {},
                    storageLocationResolver = object : StorageLocationResolver {
                        override suspend fun resolveOrBackfill(graphId: String): StorageLocation =
                            StorageLocation.AppOwned(graphId)
                    },
                    moveStorageLocationPlatformCapabilities = true,
                    onBrowseClickedForMove = { events += "clicked" },
                    onBrowseRequestedForMove = { _ ->
                        events += "requested"
                        null
                    },
                )
            }
        }

        // Open the graph dropdown, then the "Edit graph path" dialog for the one graph.
        composeTestRule.onNodeWithContentDescription("Graph: Real Graph, tap to switch graph").performClick()
        composeTestRule.onNodeWithContentDescription("Edit graph path").performClick()

        // Opens the "Move storage location…" flow — resolveOrBackfill runs in a launched
        // coroutine before UnifiedLocationPicker appears, so wait for it to settle.
        composeTestRule.onNodeWithText("Move storage location…").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(UnifiedLocationPickerBrowseRowTag).performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            listOf("clicked", "requested"),
            events,
            "onBrowseClickedForMove must fire synchronously in the click handler, before the " +
                "suspend onBrowseRequestedForMove runs",
        )
    }
}
