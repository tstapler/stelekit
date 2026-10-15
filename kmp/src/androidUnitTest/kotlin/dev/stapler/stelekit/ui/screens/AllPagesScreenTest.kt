// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Robolectric Compose tests for [AllPagesScreen], driven by a real [AllPagesViewModel] over in-memory repositories. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@OptIn(ExperimentalTestApi::class)
class AllPagesScreenTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun page(uuid: String, name: String, isJournal: Boolean = false, filePath: String? = null): Page {
        val now = Clock.System.now()
        return Page(
            uuid = PageUuid(uuid),
            name = name,
            filePath = filePath,
            createdAt = now,
            updatedAt = now,
            isJournal = isJournal,
        )
    }

    private fun makeVm(pages: List<Page>): AllPagesViewModel {
        val repo = InMemoryPageRepository()
        runBlocking { pages.forEach { repo.savePage(it) } }
        return AllPagesViewModel(repo, InMemoryBlockRepository(), scope)
    }

    private fun show(
        vm: AllPagesViewModel,
        onPageClick: (Page) -> Unit = {},
        onBulkDelete: (List<String>) -> Unit = {},
        conflictFilePaths: Set<String> = emptySet(),
        conflictsOnly: Boolean = false,
        expectEmpty: Boolean = false,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                AllPagesScreen(vm, onPageClick, onBulkDelete, conflictFilePaths = conflictFilePaths, conflictsOnly = conflictsOnly)
            }
        }
        // `pages` lags `isLoading`: the filter flow is debounced 300ms before the first emission.
        composeTestRule.waitUntil(5_000) { !vm.isLoading.value && (vm.pages.value.isNotEmpty() || expectEmpty) }
    }

    @Test
    fun allPages_should_listEveryPage_when_loaded() {
        show(makeVm(listOf(page("1", "Alpha"), page("2", "Beta"), page("3", "Gamma"))))
        composeTestRule.onNodeWithText("All Pages").assertExists()
        listOf("Alpha", "Beta", "Gamma").forEach { composeTestRule.onNodeWithText(it).assertExists() }
    }

    @Test
    fun allPages_should_showEmptyMessage_when_noPages() {
        show(makeVm(emptyList()), expectEmpty = true)
        composeTestRule.onNodeWithText("No pages found.").assertExists()
    }

    @Test
    fun allPages_should_narrowList_when_filterTextEntered() {
        val vm = makeVm(listOf(page("1", "Alpha"), page("2", "Beta")))
        show(vm)
        composeTestRule.onNodeWithText("Filter by name").performTextInput("Beta")
        composeTestRule.waitUntil(5_000) { vm.pages.value.size == 1 }
        assertEquals(listOf("Beta"), vm.pages.value.map { it.page.name })
        composeTestRule.onNodeWithText("Alpha").assertDoesNotExist()
    }

    @Test
    fun allPages_should_invokeOnPageClick_when_rowClicked() {
        val clicked = mutableListOf<Page>()
        show(makeVm(listOf(page("1", "Alpha"), page("2", "Beta"))), onPageClick = { clicked += it })
        composeTestRule.onNodeWithText("Beta").performClick()
        assertEquals(listOf("Beta"), clicked.map { it.name })
    }

    @Test
    fun allPages_should_enterSelectionMode_when_rowLongPressed() {
        val vm = makeVm(listOf(page("1", "Alpha"), page("2", "Beta")))
        show(vm)
        composeTestRule.onNodeWithText("Alpha").performTouchInput { longClick() }
        composeTestRule.waitUntil(5_000) { vm.isInSelectionMode.value }
        composeTestRule.onNodeWithText("1 selected").assertExists()
        composeTestRule.onNodeWithText("Select All").performClick()
        composeTestRule.onNodeWithText("2 selected").assertExists()
        composeTestRule.onNodeWithText("Deselect All").performClick()
        composeTestRule.waitUntil(5_000) { !vm.isInSelectionMode.value }
    }

    @Test
    fun allPages_should_callOnBulkDelete_when_deleteConfirmed() {
        val deleted = mutableListOf<List<String>>()
        val vm = makeVm(listOf(page("1", "Alpha"), page("2", "Beta")))
        show(vm, onBulkDelete = { deleted += it })
        composeTestRule.onNodeWithText("Alpha").performTouchInput { longClick() }
        composeTestRule.waitUntil(5_000) { vm.isInSelectionMode.value }
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.onNodeWithText("Delete 1 selected page(s)? This cannot be undone.").assertExists()
        composeTestRule.onNode(hasText("Delete") and hasClickAction() and
            hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf(listOf("1")), deleted)
    }

    @Test
    fun allPages_should_notDelete_when_deleteDialogCancelled() {
        val deleted = mutableListOf<List<String>>()
        val vm = makeVm(listOf(page("1", "Alpha")))
        show(vm, onBulkDelete = { deleted += it })
        composeTestRule.onNodeWithText("Alpha").performTouchInput { longClick() }
        composeTestRule.waitUntil(5_000) { vm.isInSelectionMode.value }
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Delete Pages").assertDoesNotExist()
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun allPages_should_changeSortColumn_when_headerClicked() {
        val vm = makeVm(listOf(page("1", "Alpha"), page("2", "Beta")))
        show(vm)
        assertEquals(listOf("Alpha", "Beta"), vm.pages.value.map { it.page.name })
        composeTestRule.onNodeWithText("Backlinks").performClick()
        composeTestRule.waitUntil(5_000) { vm.sortColumn.value == SortColumn.BACKLINKS }
        composeTestRule.onNodeWithText("Modified").performClick()
        composeTestRule.waitUntil(5_000) { vm.sortColumn.value == SortColumn.LAST_MODIFIED }
        composeTestRule.onNodeWithText("Created").performClick()
        composeTestRule.waitUntil(5_000) { vm.sortColumn.value == SortColumn.CREATED }
        composeTestRule.onNodeWithText("Created").performClick()
        composeTestRule.waitUntil(5_000) { !vm.sortAscending.value }
    }

    @Test
    fun allPages_should_filterByType_when_chipSelected() {
        val vm = makeVm(listOf(page("1", "Alpha"), page("2", "2024-01-01", isJournal = true)))
        show(vm)
        composeTestRule.onNodeWithText("Journals").performClick()
        composeTestRule.waitUntil(5_000) { vm.pages.value.size == 1 }
        composeTestRule.onNodeWithText("2024-01-01").assertExists()
        composeTestRule.onNodeWithText("Pages").performClick()
        composeTestRule.waitUntil(5_000) { vm.pages.value.map { it.page.name } == listOf("Alpha") }
        composeTestRule.onNodeWithText("All").performClick()
        composeTestRule.waitUntil(5_000) { vm.pages.value.size == 2 }
    }

    @Test
    fun allPages_should_showOnlyConflictedPages_when_conflictsChipToggled() {
        val vm = makeVm(listOf(page("1", "Alpha", filePath = "/g/alpha.md"), page("2", "Beta", filePath = "/g/beta.md")))
        show(vm, conflictFilePaths = setOf("/g/beta.md"))
        composeTestRule.onNodeWithText("⚠ 1 conflicts").performClick()
        composeTestRule.onNodeWithText("Alpha").assertDoesNotExist()
        composeTestRule.onNodeWithText("Beta").assertExists()
    }

    @Test
    fun allPages_should_showNoConflictsMessage_when_conflictsOnlyAndNoneMatch() {
        val vm = makeVm(listOf(page("1", "Alpha", filePath = "/g/alpha.md")))
        show(vm, conflictFilePaths = setOf("/g/other.md"), conflictsOnly = true)
        composeTestRule.onNodeWithText("No conflicted pages found.").assertExists()
    }
}
