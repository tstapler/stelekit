// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.PullIndexState
import dev.stapler.stelekit.merge.ReadError
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.screens.copy.CopyPagesActions
import dev.stapler.stelekit.ui.screens.copy.CopyPagesContent
import dev.stapler.stelekit.ui.screens.copy.CopyPagesState
import dev.stapler.stelekit.ui.screens.copy.DestinationAction
import dev.stapler.stelekit.ui.screens.copy.DestinationActionKind
import dev.stapler.stelekit.ui.screens.copy.DestinationRow
import dev.stapler.stelekit.ui.screens.copy.DestinationStatus
import dev.stapler.stelekit.ui.screens.copy.DisabledKind
import dev.stapler.stelekit.ui.screens.copy.ListLoad
import dev.stapler.stelekit.ui.screens.copy.PageRowState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Pull source chooser (disabled reasons, exits) and the name-index loading/failure states. */
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class PullCopyScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun uuid(n: Int) = PageUuid("00000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

    private val current = DestinationRow(GraphId("a"), "Personal", DestinationStatus.Disabled(DisabledKind.CurrentGraph, "current graph"))
    private val readable = DestinationRow(GraphId("w"), "Work", DestinationStatus.Available)
    private fun noGrant(canRegrant: Boolean = true) = DestinationRow(
        GraphId("n"), "Notes",
        DestinationStatus.Disabled(
            DisabledKind.NoGrant,
            "Can't read Notes: SteleKit no longer has permission to read this graph's folder.",
            if (canRegrant) DestinationAction(DestinationActionKind.RegrantAccess, CopyPagesState.RESELECT_FOLDER) else null,
        ),
    )

    private fun pull(
        destinations: List<DestinationRow>,
        chosen: GraphId? = null,
        index: PullIndexState = PullIndexState.Idle,
        rows: List<PageRowState> = emptyList(),
    ) = CopyPagesState(
        direction = CopyDirection.Pull,
        activeGraphName = "Personal",
        destinations = destinations,
        destinationId = chosen,
        indexState = index,
        rows = rows,
        listLoad = ListLoad.Idle,
        totalMatching = rows.size.toLong(),
        gate2LinkedPages = true,
    )

    private fun show(state: CopyPagesState, actions: CopyPagesActions = CopyPagesActions()) {
        rule.setContent { MaterialTheme { CopyPagesContent(state, actions, Modifier) } }
    }

    @Test
    fun `chooser lists current graph, readable source and a disabled one with Re-select folder`() {
        var regrant: GraphId? = null
        show(
            pull(listOf(current, readable, noGrant())),
            CopyPagesActions(onDestinationAction = { id, kind -> if (kind == DestinationActionKind.RegrantAccess) regrant = id }),
        )
        rule.onNodeWithText("Copy pages from...").assertExists()
        rule.onNodeWithText("current graph").assertExists()
        rule.onNodeWithText("Can't read Notes: SteleKit no longer has permission to read this graph's folder.").assertExists()
        rule.onNodeWithText("Re-select folder").performScrollTo().performClick()
        assertEquals(GraphId("n"), regrant)
    }

    @Test
    fun `no source available offers Add a graph and Close and no Re-select when not re-grantable`() {
        var added = false
        var closed = false
        show(
            pull(listOf(current, noGrant(canRegrant = false))),
            CopyPagesActions(onAddGraph = { added = true }, onRequestClose = { closed = true }),
        )
        rule.onNodeWithText("No graph can be read from here.").assertExists()
        rule.onNodeWithText("Add a graph").performScrollTo().performClick()
        rule.onNodeWithText("Close").performScrollTo().performClick()
        assertEquals(true to true, added to closed)
    }

    @Test
    fun `reading state shows the count, a usable list, the still reading note and Stop`() {
        var stopped = false
        show(
            pull(
                listOf(current, readable), chosen = GraphId("w"), index = PullIndexState.Reading(42),
                rows = listOf(PageRowState(uuid(1), "Roadmap", isJournal = false, subtitle = "2 KB - 2026-10-08")),
            ),
            CopyPagesActions(onStopReading = { stopped = true }),
        )
        rule.onNodeWithText("Reading Work... 42 files found").assertExists()
        rule.onNodeWithText("Still reading... results so far").assertExists()
        rule.onNodeWithText("Roadmap").assertExists()
        rule.onNodeWithText("2 KB - 2026-10-08").assertExists()
        rule.onNodeWithText("Stop").performClick()
        assertEquals(true, stopped)
    }

    @Test
    fun `no empty-list message while the first rows are still being read`() {
        show(pull(listOf(current, readable), chosen = GraphId("w"), index = PullIndexState.Reading(0)))
        rule.onNodeWithText("This graph has no pages to copy.").assertDoesNotExist()
    }

    @Test
    fun `a read error shows the banner with Retry, Re-select folder and Close`() {
        var retried = false
        var regrant: GraphId? = null
        show(
            pull(listOf(current, readable), chosen = GraphId("w"), index = PullIndexState.Failed(ReadError.NoGrant)),
            CopyPagesActions(onRetryIndex = { retried = true }, onDestinationAction = { id, _ -> regrant = id }),
        )
        rule.onNodeWithText("Couldn't read Work: No permission to read this graph's folder").assertExists()
        rule.onNodeWithText("Retry").performClick()
        rule.onNodeWithText("Re-select folder").performClick()
        assertEquals(true to GraphId("w"), retried to regrant)
    }

    @Test
    fun `tag filter, linked pages and assets are disabled with the pull note`() {
        show(pull(listOf(current, readable), chosen = GraphId("w"), index = PullIndexState.Ready(0)))
        rule.onNodeWithText("Tag").assertIsNotEnabled()
        assertEquals(
            CopyPagesState.NOT_AVAILABLE_PULL,
            rule.onNodeWithText("Tag").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        rule.onNodeWithText("Tag, property and backlink filters: ${CopyPagesState.NOT_AVAILABLE_PULL}").assertExists()
        rule.onNodeWithText(CopyPagesState.NOT_AVAILABLE_PULL).assertExists()
        rule.onNodeWithText("Include their assets").assertExists()
    }

    @Test
    fun `header names the chosen source`() {
        show(pull(listOf(current, readable), chosen = GraphId("w"), index = PullIndexState.Ready(0)))
        rule.onNodeWithText("Copy pages from \"Work\"").assertExists()
    }
}
