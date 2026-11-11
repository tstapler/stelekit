// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

internal fun uuidOf(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')

internal fun testPage(id: String, name: String, loaded: Boolean = true, path: String? = "/g/pages/$name.md") =
    Page(PageUuid(id), name, filePath = path, createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0), isContentLoaded = loaded)

internal class CountingPages(private val inner: InMemoryPageRepository) : PageRepository by inner {
    var uuidLookups = 0
    var searches = 0
    override fun getPageByUuid(uuid: PageUuid): Flow<Either<DomainError, Page?>> { uuidLookups++; return inner.getPageByUuid(uuid) }
    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
        searches++
        return inner.searchPages(query, limit, offset)
    }
}

private fun resolved(kind: SourceBindingKind, pageUuid: String) =
    BindingResolution.Resolved(SourceBinding(kind, pageUuid.takeLast(8), "x"), pageUuid)

class BoundPagesReaderTest {
    private val repo = InMemoryPageRepository()
    private val pages = CountingPages(repo)
    private val files = mutableMapOf<String, String>()
    private val reader = BoundPagesReader(pages) { files[it] }

    private suspend fun add(id: String, name: String, text: String?, loaded: Boolean = true) {
        repo.savePage(testPage(id, name, loaded))
        text?.let { files["/g/pages/$name.md"] = it }
    }

    @Test
    fun readsTableAndProsePagesAndHashesTheirText() = runTest {
        add("00000000-0000-0000-0000-000000000001", "Closet", "| a | b |")
        add("00000000-0000-0000-0000-000000000002", "Notes", "rod is 36 inches")
        val r = reader.read(listOf(
            resolved(SourceBindingKind.TABLE, "00000000-0000-0000-0000-000000000001"),
            resolved(SourceBindingKind.PROSE, "00000000-0000-0000-0000-000000000002"),
        ))
        assertEquals(listOf("| a | b |"), r.tablePages.map { it.markdown })
        assertEquals(listOf("rod is 36 inches"), r.prosePages.map { it.markdown })
        assertEquals(2, r.boundPages.size)
        assertEquals(ContentHasher.sha256("| a | b |"), r.contentHashes["00000001"])
        assertTrue(r.tablePages.single().locator.startsWith("page:"))
    }

    @Test
    fun indexOnlyStubContributesNothingAndIsCountedForRetry() = runTest {
        add("00000000-0000-0000-0000-000000000003", "Stub", "text", loaded = false)
        val r = reader.read(listOf(resolved(SourceBindingKind.TABLE, "00000000-0000-0000-0000-000000000003")))
        assertEquals(1, r.unloaded)
        assertTrue(r.tablePages.isEmpty())
        assertEquals(1, r.boundPages.size)
    }

    @Test
    fun unreadableFileCountsAsUnloaded() = runTest {
        add("00000000-0000-0000-0000-000000000004", "Gone", null)
        val r = reader.read(listOf(resolved(SourceBindingKind.PROSE, "00000000-0000-0000-0000-000000000004")))
        assertEquals(1, r.unloaded)
    }

    @Test
    fun removedPageIsCountedMissing() = runTest {
        val r = reader.read(listOf(resolved(SourceBindingKind.TABLE, "00000000-0000-0000-0000-0000000000ff")))
        assertEquals(1, r.missing)
        assertEquals(0, r.unloaded)
    }

    @Test
    fun pageWithoutAFilePathIsMissing() = runTest {
        repo.savePage(testPage("00000000-0000-0000-0000-000000000005", "NoFile", path = null))
        val r = reader.read(listOf(resolved(SourceBindingKind.TABLE, "00000000-0000-0000-0000-000000000005")))
        assertEquals(1, r.missing)
    }

    @Test
    fun largeGraphReadsOnlyTheBoundPages() = runTest {
        repeat(400) { add(uuidOf(it + 100), "P$it", "t$it") }
        val bound = (0 until 25).map { resolved(SourceBindingKind.TABLE, uuidOf(it + 100)) }
        val r = reader.read(bound)
        assertEquals(25, r.tablePages.size)
        assertEquals(25, pages.uuidLookups, "one point lookup per bound page, no scan")
    }

    @Test
    fun unresolvedBindingsAreCountedNotDropped() = runTest {
        val r = reader.read(listOf(BindingResolution.NeedsRebind(SourceBinding(SourceBindingKind.TABLE, "0000000a", "h"))))
        assertEquals(1, r.needsRebind)
        assertEquals(0, pages.uuidLookups)
    }

    @Test
    fun noBindingsReadsNothing() = runTest {
        assertEquals(SourceReadResult.EMPTY, reader.read(emptyList()))
        assertEquals(0, pages.uuidLookups)
    }
}
