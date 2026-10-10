// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.MergePlan
import dev.stapler.stelekit.merge.MergeProgress
import dev.stapler.stelekit.merge.MergeResult
import dev.stapler.stelekit.merge.ApplyFailure
import dev.stapler.stelekit.merge.PageSource
import dev.stapler.stelekit.merge.PlanRequest
import dev.stapler.stelekit.merge.SelectionFilter
import dev.stapler.stelekit.merge.SourcePage
import dev.stapler.stelekit.merge.filteredAndSorted
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.screens.copy.CopyFlowGateway
import dev.stapler.stelekit.ui.screens.copy.CopyPagesActions
import dev.stapler.stelekit.ui.screens.copy.CopyPagesContent
import dev.stapler.stelekit.ui.screens.copy.CopyPagesState
import dev.stapler.stelekit.ui.screens.copy.CopyPagesViewModel
import dev.stapler.stelekit.ui.screens.copy.DestinationAction
import dev.stapler.stelekit.ui.screens.copy.DestinationActionKind
import dev.stapler.stelekit.ui.screens.copy.DestinationProbe
import dev.stapler.stelekit.ui.screens.copy.DestinationRow
import dev.stapler.stelekit.ui.screens.copy.DestinationStatus
import dev.stapler.stelekit.ui.screens.copy.DisabledKind
import dev.stapler.stelekit.ui.screens.copy.ListLoad
import dev.stapler.stelekit.ui.screens.copy.actions
import dev.stapler.stelekit.ui.screens.copy.PageRowState
import dev.stapler.stelekit.ui.screens.copy.PageSelectionRow
import dev.stapler.stelekit.ui.screens.copy.PickedPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Picker semantics, selection persistence, destination states, discard dialog, font scale and RTL. */
@OptIn(ExperimentalTestApi::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class CopyPagesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun uuid(n: Int) = PageUuid("00000000-0000-0000-0000-" + n.toString().padStart(12, '0'))
    private val roadmap = PageRowState(uuid(1), "Roadmap", isJournal = false, blockCount = 14)

    private fun state(
        rows: List<PageRowState> = listOf(roadmap),
        picked: Set<PageUuid> = emptySet(),
        destinations: List<DestinationRow> = emptyList(),
        listLoad: ListLoad = ListLoad.Idle,
        total: Long? = rows.size.toLong(),
        gate2: Boolean = false,
        discard: Boolean = false,
    ) = CopyPagesState(
        activeGraphName = "Personal",
        rows = rows,
        totalMatching = total,
        listLoad = listLoad,
        picked = PickedPages(picked),
        destinations = destinations,
        gate2LinkedPages = gate2,
        discardPrompt = discard,
    )

    private fun show(state: CopyPagesState, actions: CopyPagesActions = CopyPagesActions()) {
        rule.setContent { MaterialTheme { CopyPagesContent(state, actions, Modifier) } }
    }

    // ---- accessibility ----

    @Test
    fun `row is a toggleable checkbox with merged label and state`() {
        rule.setContent { MaterialTheme { PageSelectionRow(roadmap, checked = true, onToggle = {}) } }
        val node = rule.onNodeWithContentDescription("Roadmap, 14 blocks").fetchSemanticsNode()
        assertEquals(Role.Checkbox, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.On, node.config.getOrNull(SemanticsProperties.ToggleableState))
    }

    @Test
    fun `clicking and pressing Space toggle the row, Enter does not`() {
        var toggles = 0
        rule.setContent { MaterialTheme { PageSelectionRow(roadmap, checked = false, onToggle = { toggles++ }) } }
        val row = rule.onNodeWithContentDescription("Roadmap, 14 blocks")
        row.performClick()
        assertEquals(1, toggles)
        row.requestFocus()
        row.performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(2, toggles)
        row.performKeyInput { pressKey(Key.Enter) }
        assertEquals(2, toggles)
    }

    @Test
    fun `count line is a polite live region`() {
        show(state(picked = setOf(uuid(1)), total = 213))
        val node = rule.onNodeWithText("213 results, 1 selected").fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.LiveRegion))
    }

    // ---- loading states ----

    @Test
    fun `initial load shows Loading pages and Counting`() {
        show(state(rows = emptyList(), listLoad = ListLoad.Initial, total = null))
        rule.onNodeWithText("Loading pages...").assertExists()
        rule.onNodeWithText("Counting..., 0 selected").assertExists()
    }

    @Test
    fun `updating keeps rows and says Updating`() {
        show(state(listLoad = ListLoad.Updating, total = null))
        rule.onNodeWithContentDescription("Roadmap, 14 blocks").assertExists()
        rule.onNodeWithText("Counting..., 0 selected, Updating...").assertExists()
    }

    @Test
    fun `load failure shows banner with Retry and Close`() {
        var retried = false
        show(
            state(rows = emptyList(), listLoad = ListLoad.Failed).copy(loadError = "closed"),
            CopyPagesActions(onRetryLoad = { retried = true }),
        )
        rule.onNodeWithText("Couldn't read pages from Personal.").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    // ---- selection persistence through the real ViewModel ----

    private class Source(val pages: List<Page>) : PageSource {
        override suspend fun listPages(filter: SelectionFilter, search: String?, limit: Int, offset: Int): Either<DomainError, List<Page>> =
            pages.filteredAndSorted(filter).filter { search == null || it.name.contains(search, true) }.drop(offset).take(limit).right()

        override suspend fun countPages(filter: SelectionFilter, search: String?): Either<DomainError, Long> =
            pages.filteredAndSorted(filter).count { search == null || it.name.contains(search, true) }.toLong().right()

        override suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>> = emptyList<SourcePage>().right()
    }

    private object NoGateway : CopyFlowGateway {
        override val progress: StateFlow<MergeProgress> = MutableStateFlow(MergeProgress())
        override suspend fun plan(request: PlanRequest): Either<DomainError, MergePlan> = error("unused")
        override suspend fun apply(plan: MergePlan): Either<ApplyFailure, MergeResult> = error("unused")
        override fun cancel() = Unit
    }

    @Test
    fun `selection persists while search hides the row`() {
        val t = Instant.fromEpochMilliseconds(0)
        val pages = listOf("Alpha", "Beta").mapIndexed { i, n -> Page(uuid(i + 1), n, createdAt = t, updatedAt = t) }
        val vm = CopyPagesViewModel(
            source = Source(pages),
            activeGraphId = GraphId("a"),
            graphRegistry = MutableStateFlow(GraphRegistry(graphs = listOf(GraphInfo(GraphId("a"), "/a", "A", 0)))),
            gateway = NoGateway,
            probe = DestinationProbe { _, _ -> DestinationStatus.Available },
            dispatcher = Dispatchers.Unconfined,
            searchDebounceMs = 0,
        )
        rule.setContent {
            val s = vm.state.collectAsState().value
            MaterialTheme { CopyPagesContent(s, vm.actions()) }
        }
        rule.waitUntil(5_000) { vm.state.value.rows.size == 2 }
        rule.onNodeWithContentDescription("Alpha").performClick()
        rule.waitUntil(5_000) { vm.state.value.selectedCount == 1 }
        vm.onSearchTextChanged("Beta")
        rule.waitUntil(5_000) { vm.state.value.rows.map { it.name } == listOf("Beta") }
        rule.onNodeWithText("1 result, 1 selected").assertExists()
        vm.onSearchTextChanged("")
        rule.waitUntil(5_000) { vm.state.value.rows.size == 2 }
        rule.onNodeWithContentDescription("Alpha").fetchSemanticsNode().let {
            assertEquals(ToggleableState.On, it.config.getOrNull(SemanticsProperties.ToggleableState))
        }
        vm.close()
    }

    // ---- destination chooser ----

    @Test
    fun `disabled destinations show their reason and Review stays disabled`() {
        val rows = listOf(
            DestinationRow(GraphId("a"), "Personal", DestinationStatus.Disabled(DisabledKind.CurrentGraph, "current graph")),
            DestinationRow(
                GraphId("enc"), "Vault",
                DestinationStatus.Disabled(DisabledKind.Encrypted, "Can't write here: This graph is encrypted, so it can only be changed while it is open."),
            ),
            DestinationRow(GraphId("wait"), "Slow", DestinationStatus.Checking),
            DestinationRow(GraphId("late"), "Late", DestinationStatus.CouldntCheck("Couldn't check Late: timed out")),
            DestinationRow(GraphId("ok"), "Work", DestinationStatus.Available),
        )
        var retried: GraphId? = null
        show(state(picked = setOf(uuid(1)), destinations = rows), CopyPagesActions(onRetryProbe = { retried = it }))
        rule.onNodeWithText("current graph").assertExists()
        rule.onNodeWithText("Can't write here: This graph is encrypted, so it can only be changed while it is open.").assertExists()
        rule.onNodeWithText("Checking...").assertExists()
        rule.onNodeWithText("Couldn't check Late: timed out").assertExists()
        rule.onNodeWithText("Review copy").assertIsNotEnabled()
        rule.onNodeWithText("Choose a destination").assertExists()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(GraphId("late"), retried)
    }

    @Test
    fun `iOS and Web push chooser offers the pull direction link`() {
        var kind: DestinationActionKind? = null
        val rows = listOf(
            DestinationRow(
                GraphId("w"), "Work",
                DestinationStatus.Disabled(
                    DisabledKind.PlatformUnsupported,
                    "Can't copy into Work from here on this device. Open Work, then use Copy pages from...",
                    DestinationAction(DestinationActionKind.SwitchToPull, "Copy pages from..."),
                ),
            ),
            DestinationRow(GraphId("a"), "Personal", DestinationStatus.Disabled(DisabledKind.CurrentGraph, "current graph")),
        )
        show(state(destinations = rows), CopyPagesActions(onDestinationAction = { _, k -> kind = k }))
        rule.onNodeWithText("Copy pages from...").performClick()
        assertEquals(DestinationActionKind.SwitchToPull, kind)
    }

    @Test
    fun `only the available chosen destination with a selection enables Review`() {
        val rows = listOf(DestinationRow(GraphId("ok"), "Work", DestinationStatus.Available))
        show(state(picked = setOf(uuid(1)), destinations = rows).copy(destinationId = GraphId("ok")))
        rule.onNodeWithText("Review copy").assertIsEnabled()
    }

    @Test
    fun `no second graph offers Add a graph and Close`() {
        val rows = listOf(DestinationRow(GraphId("a"), "Personal", DestinationStatus.Disabled(DisabledKind.CurrentGraph, "current graph")))
        show(state(destinations = rows))
        rule.onNodeWithText("You need a second graph to copy pages.").assertExists()
        rule.onNodeWithText("Add a graph").assertExists()
    }

    // ---- Gate 1 / Gate 2 ----

    @Test
    fun `linked page options are hidden in Gate 1 and shown in Gate 2`() {
        show(state(gate2 = false))
        rule.onAllNodesWithText("Include linked pages", substring = true).assertCountEquals(0)
    }

    @Test
    fun `linked page options render in Gate 2`() {
        show(state(gate2 = true))
        rule.onNodeWithText("Include linked pages  (adds 0 pages)").assertExists()
        rule.onNodeWithText("Include their assets").assertExists()
    }

    // ---- discard dialog ----

    @Test
    fun `discard dialog names the count and wires both buttons`() {
        var discarded = false
        var kept = false
        show(
            state(picked = setOf(uuid(1), uuid(2)), discard = true),
            CopyPagesActions(onConfirmDiscard = { discarded = true }, onKeepEditing = { kept = true }),
        )
        rule.onNodeWithText("Discard selection of 2 pages?").assertExists()
        rule.onNodeWithText("Keep editing").performClick()
        assertTrue(kept)
        rule.onNodeWithText("Discard").performClick()
        assertTrue(discarded)
    }

    // ---- font scale and RTL ----

    private val longName = "A very long page name that must wrap onto two lines at two hundred percent font scale and stay readable"

    @Test
    fun `at 200 percent font scale the full name is in semantics and the target stays 48dp`() {
        val row = PageSelectionRowState(longName)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                MaterialTheme { PageSelectionRow(row, checked = false, onToggle = {}) }
            }
        }
        val node = rule.onNodeWithContentDescription(row.label)
        node.assertExists()
        assertTrue(node.getUnclippedBoundsInRoot().let { it.bottom - it.top } >= 48.dp)
    }

    @Test
    fun `in RTL the checkbox leads from the right and Hebrew names are exposed`() {
        val hebrew = PageSelectionRowState("מפת דרכים")
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { PageSelectionRow(hebrew, checked = false, onToggle = {}) }
            }
        }
        rule.onNodeWithContentDescription(hebrew.label).assertExists()
        val row = rule.onNodeWithContentDescription(hebrew.label).getUnclippedBoundsInRoot()
        val text = rule.onAllNodesWithText(hebrew.name, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()
        // Mirrored: checkbox at the right edge, the trailing detail text on the left.
        assertTrue(row.right - text.right >= 40.dp, "checkbox should occupy the right edge in RTL")
    }

    private fun PageSelectionRowState(name: String) = PageRowState(uuid(9), name, isJournal = false, blockCount = 3)
}
