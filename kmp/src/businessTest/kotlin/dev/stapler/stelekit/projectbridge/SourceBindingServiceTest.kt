// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One simulated device: its own page uuids, files, local binding table and sketch repositories. */
@OptIn(DirectRepositoryWrite::class)
private class SimulatedDevice(pageUuid: String, val pageName: String = "Closet Plan", val text: String = "| a |", seed: Int = 1) {
    val repos: SketchRepositories = inMemorySketchRepositories()
    val repo = InMemoryPageRepository()
    val pages = CountingPages(repo)
    val files = mutableMapOf<String, String>()
    val table = LocalBindingTable(BridgeMapSettings())
    var failWrites = false
    val pageId = pageUuid
    val service = SourceBindingService(
        repos, pages, { files[it] },
        { op -> if (failWrites) DomainError.DatabaseError.WriteFailed("x").left() else op() },
        table, Random(seed),
    )

    suspend fun setUp(project: dev.stapler.stelekit.model.SketchProject) {
        repos.projects.saveProject(project)
        repo.savePage(testPage(pageId, pageName))
        files["/g/pages/$pageName.md"] = text
    }

    suspend fun settings() = repos.projects.loadProject(ProjectUuid("pr1")).getOrNull()!!.project.settings
}

@OptIn(DirectRepositoryWrite::class)
class SourceBindingServiceTest {
    private val project = ProjectFactory.create("Closet", "proj-0a1b2c", ProjectUuid("pr1"), 1L)

    private suspend fun device(id: Int = 7, seed: Int = 1) = SimulatedDevice(uuidOf(id), seed = seed).also { it.setUp(project) }

    @Test
    fun searchIsBoundedAndIgnoresShortQueries() = runTest {
        val d = device()
        assertTrue(d.service.search("c").isEmpty())
        assertEquals(0, d.pages.searches)
        assertEquals(listOf(PickerPage(uuidOf(7), "Closet Plan")), d.service.search(" clos "))
        assertEquals(1, d.pages.searches)
    }

    @Test
    fun syncedSettingsHoldOnlyAnOpaqueIdAndAHash() = runTest {
        val d = device()
        assertEquals(BindOutcome.Bound, d.service.bind("pr1", SourceBindingKind.TABLE, uuidOf(7)))
        val stored = d.settings()
        val binding = SourceBindings.of(stored).single()
        assertEquals(ContentHasher.sha256("| a |"), binding.contentHash)
        assertTrue(BindingIds.isValid(binding.bindingId))
        val bundle = ProjectBundle(project.copy(settings = stored), emptyList(), emptyList(), emptyList())
        val sidecar = SidecarCodec.encode(bundle).values.joinToString("\n") { it.decodeToString() }
        listOf("Closet Plan", uuidOf(7), "/g/pages", ".md", "pageUuid", "title", "path").forEach {
            assertFalse(sidecar.contains(it), "synced sidecar must not contain <$it>")
        }
        assertTrue(sidecar.contains(binding.bindingId))
    }

    @Test
    fun listShowsTheTitleLocallyOnly() = runTest {
        val d = device()
        d.service.bind("pr1", SourceBindingKind.TABLE, uuidOf(7))
        val row = d.service.listBound("pr1").single()
        assertEquals("Closet Plan", row.title)
        assertFalse(row.needsRebind)
    }

    @Test
    fun secondDeviceWithDifferentUuidsResolvesByContentHash() = runTest {
        val a = device(id = 7)
        a.service.bind("pr1", SourceBindingKind.TABLE, uuidOf(7))
        val synced = a.settings()

        val b = SimulatedDevice(uuidOf(900), seed = 2).also { it.setUp(project.copy(settings = synced)) }
        val before = b.service.listBound("pr1").single()
        assertTrue(before.needsRebind, "no local mapping on the new device")
        assertEquals(1, readBoundSources(b.repos, BoundPagesReader(b.pages) { b.files[it] }, b.table).needsRebind)

        assertEquals(1, b.service.autoRebind("pr1", b.service.search("clos")))
        val after = b.service.listBound("pr1").single()
        assertFalse(after.needsRebind)
        assertEquals("Closet Plan", after.title)
        val read = readBoundSources(b.repos, BoundPagesReader(b.pages) { b.files[it] }, b.table)
        assertEquals(listOf("| a |"), read.tablePages.map { it.markdown })
        assertEquals(0, read.needsRebind)
        assertEquals(synced, b.settings(), "re-resolving is device-local; the synced settings are untouched")
    }

    @Test
    fun noMatchingPageLeavesTheBindingNeedingRebind() = runTest {
        val a = device(id = 7)
        a.service.bind("pr1", SourceBindingKind.TABLE, uuidOf(7))
        val b = SimulatedDevice(uuidOf(901), text = "| different |", seed = 2).also { it.setUp(project.copy(settings = runBlockingSettings(a))) }
        assertEquals(0, b.service.autoRebind("pr1", b.service.search("clos")))
        assertTrue(b.service.listBound("pr1").single().needsRebind)
        assertEquals(0, b.service.autoRebind("nope", emptyList()))
        assertEquals(0, b.service.autoRebind("pr1", emptyList()))
    }

    private suspend fun runBlockingSettings(d: SimulatedDevice) = d.settings()

    @Test
    fun bindingAnUnresolvedKindTakesOverTheWaitingBindingAndRefreshesItsHash() = runTest {
        val a = device(id = 7)
        a.service.bind("pr1", SourceBindingKind.TABLE, uuidOf(7))
        val originalId = SourceBindings.of(a.settings()).single().bindingId
        val b = SimulatedDevice(uuidOf(902), text = "| edited |", seed = 2).also { it.setUp(project.copy(settings = a.settings())) }
        assertEquals(BindOutcome.Bound, b.service.bind("pr1", SourceBindingKind.TABLE, uuidOf(902)))
        val only = SourceBindings.of(b.settings()).single()
        assertEquals(originalId, only.bindingId)
        assertEquals(ContentHasher.sha256("| edited |"), only.contentHash)
        assertFalse(b.service.listBound("pr1").single().needsRebind)
    }

    @Test
    fun bindingTheSamePageTwiceKeepsOneBinding() = runTest {
        val d = device()
        d.service.bind("pr1", SourceBindingKind.PROSE, uuidOf(7))
        d.service.bind("pr1", SourceBindingKind.PROSE, uuidOf(7))
        assertEquals(1, SourceBindings.of(d.settings()).size)
    }

    @Test
    fun unbindRemovesTheEntryAndTheLocalMapping() = runTest {
        val d = device()
        d.service.bind("pr1", SourceBindingKind.PROSE, uuidOf(7))
        val id = SourceBindings.of(d.settings()).single().bindingId
        assertEquals(BindOutcome.Bound, d.service.unbind("pr1", id))
        assertTrue(d.service.listBound("pr1").isEmpty())
        assertEquals(null, d.table.pageUuid("proj-0a1b2c", id))
    }

    @Test
    fun refusesUnknownProjectPageStubAndUnreadableFile() = runTest {
        val d = device()
        val s = d.service
        assertEquals(BindOutcome.ProjectNotFound, s.bind("nope", SourceBindingKind.TABLE, uuidOf(7)))
        assertEquals(BindOutcome.ProjectNotFound, s.unbind("nope", "00000001"))
        assertEquals(BindOutcome.PageNotFound, s.bind("pr1", SourceBindingKind.TABLE, uuidOf(99)))
        d.repo.savePage(testPage(uuidOf(8), "Stub", loaded = false))
        assertEquals(BindOutcome.NotReadable, s.bind("pr1", SourceBindingKind.TABLE, uuidOf(8)))
        d.repo.savePage(testPage(uuidOf(9), "Unread"))
        assertEquals(BindOutcome.NotReadable, s.bind("pr1", SourceBindingKind.TABLE, uuidOf(9)))
        assertTrue(s.listBound("nope").isEmpty())
    }

    @Test
    fun limitAndWriteFailureAreReported() = runTest {
        val d = device()
        var p = d.repos.projects.loadProject(ProjectUuid("pr1")).getOrNull()!!.project
        repeat(SourceBindings.MAX_BINDINGS) { n ->
            p = p.copy(settings = SourceBindings.with(p.settings, SourceBinding(SourceBindingKind.PROSE, n.toString().padStart(8, '0'), "h"))!!)
        }
        d.repos.projects.saveProject(p)
        d.table.put("proj-0a1b2c", "00000000", uuidOf(7))
        d.table.put("proj-0a1b2c", "00000001", uuidOf(7))
        assertEquals(BindOutcome.LimitReached, d.service.bind("pr1", SourceBindingKind.TABLE, uuidOf(7)))
        d.failWrites = true
        d.repos.projects.saveProject(project.copy(uuid = ProjectUuid("pr2"), slug = "proj-0a1b2d"))
        assertTrue(d.service.bind("pr2", SourceBindingKind.TABLE, uuidOf(7)) is BindOutcome.Failed)
        assertTrue(d.service.unbind("pr2", "00000001") is BindOutcome.Failed)
    }

    @Test
    fun readBoundSourcesIsEmptyWithoutProjectOrBindings() = runTest {
        val d = SimulatedDevice(uuidOf(7))
        val reader = BoundPagesReader(d.pages) { d.files[it] }
        assertEquals(SourceReadResult.EMPTY, readBoundSources(d.repos, reader, d.table))
        d.setUp(project)
        assertEquals(SourceReadResult.EMPTY, readBoundSources(d.repos, reader, d.table))
    }

    @Test
    fun readResultHandsBothPageKindsAndBoundPagesToTheCollector() = runTest {
        val read = SourceReadResult(
            tablePages = listOf(TablePageText("page:1", "no table here")),
            prosePages = listOf(TablePageText("page:2", "notes")),
            boundPages = listOf(BoundPageRef("P", "/g/P.md")),
        )
        val out = ProjectSourceCollector(reader = ProjectSourceReader { read.toTexts() }).collect()
        assertEquals(read.boundPages, out.boundPages)
        assertEquals(listOf("page:1", "page:2"), read.toTexts().pages.map { it.locator })
        assertTrue(out.tableObservations.isEmpty())
        assertEquals(emptyList(), ProjectSourceCollector().collect().boundPages)
    }
}
