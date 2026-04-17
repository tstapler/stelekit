// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class AllPagesViewModelTest {

    private fun makePage(
        uuid: String,
        name: String,
        isJournal: Boolean = false,
        journalDate: LocalDate? = null,
    ): Page {
        val now = Clock.System.now()
        return Page(
            uuid = uuid,
            name = name,
            createdAt = now,
            updatedAt = now,
            isJournal = isJournal,
            journalDate = journalDate,
        )
    }

    @Test
    fun `filter by name returns only matching pages after debounce`() = runTest {
        val pages = listOf(
            makePage("1", "Alpha"),
            makePage("2", "Beta"),
            makePage("3", "Gamma"),
        )
        val pageRepo = FakePageRepository(pages)
        val blockRepo = FakeBlockRepository()
        val vm = AllPagesViewModel(pageRepo, blockRepo, backgroundScope)

        vm.onFilterChange("alp")
        advanceTimeBy(400)

        val result = vm.pages.value
        assertEquals(1, result.size)
        assertEquals("Alpha", result.first().page.name)
    }

    @Test
    fun `sort by name ascending then descending`() = runTest {
        val pages = listOf(
            makePage("1", "Charlie"),
            makePage("2", "Alice"),
            makePage("3", "Bob"),
        )
        val pageRepo = FakePageRepository(pages)
        val blockRepo = FakeBlockRepository()
        val vm = AllPagesViewModel(pageRepo, blockRepo, backgroundScope)

        // Allow initial load to complete
        advanceTimeBy(400)

        val ascNames = vm.pages.value.map { it.page.name }
        assertEquals(listOf("Alice", "Bob", "Charlie"), ascNames)

        vm.toggleSort(SortColumn.NAME)
        advanceTimeBy(100)

        val descNames = vm.pages.value.map { it.page.name }
        assertEquals(listOf("Charlie", "Bob", "Alice"), descNames)
    }

    @Test
    fun `selection mode toggles correctly with selectAll and clearSelection`() = runTest {
        val pages = listOf(
            makePage("uuid-1", "Page One"),
            makePage("uuid-2", "Page Two"),
            makePage("uuid-3", "Page Three"),
        )
        val pageRepo = FakePageRepository(pages)
        val blockRepo = FakeBlockRepository()
        val vm = AllPagesViewModel(pageRepo, blockRepo, backgroundScope)

        advanceTimeBy(400)

        assertFalse(vm.isInSelectionMode.value)

        vm.toggleSelection("uuid-1")
        advanceTimeBy(100)

        assertTrue(vm.isInSelectionMode.value)
        assertEquals(1, vm.selectedUuids.value.size)
        assertTrue(vm.selectedUuids.value.contains("uuid-1"))

        vm.selectAll()
        advanceTimeBy(100)

        assertEquals(3, vm.selectedUuids.value.size)

        vm.clearSelection()
        advanceTimeBy(100)

        assertFalse(vm.isInSelectionMode.value)
        assertTrue(vm.selectedUuids.value.isEmpty())
    }

    @Test
    fun `page type filter shows only journals or only regular pages`() = runTest {
        val pages = listOf(
            makePage("j1", "2025-01-01", isJournal = true, journalDate = LocalDate(2025, 1, 1)),
            makePage("j2", "2025-01-02", isJournal = true, journalDate = LocalDate(2025, 1, 2)),
            makePage("p1", "My Notes"),
            makePage("p2", "Project Ideas"),
        )
        val pageRepo = FakePageRepository(pages)
        val blockRepo = FakeBlockRepository()
        val vm = AllPagesViewModel(pageRepo, blockRepo, backgroundScope)

        advanceTimeBy(400)

        vm.setPageTypeFilter(PageTypeFilter.JOURNALS)
        advanceTimeBy(100)

        val journalPages = vm.pages.value
        assertEquals(2, journalPages.size)
        assertTrue(journalPages.all { it.page.isJournal })

        vm.setPageTypeFilter(PageTypeFilter.PAGES)
        advanceTimeBy(100)

        val regularPages = vm.pages.value
        assertEquals(2, regularPages.size)
        assertTrue(regularPages.none { it.page.isJournal })
    }
}
