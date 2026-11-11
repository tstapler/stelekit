// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(DirectRepositoryWrite::class)
class SourceBindingServiceTest {
    private val repos: SketchRepositories = inMemorySketchRepositories()
    private val repo = InMemoryPageRepository()
    private val pages = CountingPages(repo)
    private val files = mutableMapOf<String, String>()
    private val project = ProjectFactory.create("Closet", "proj-0a1b2c", ProjectUuid("pr1"), 1L)
    private var failWrites = false

    private fun service() = SourceBindingService(repos, pages, { files[it] }, { op -> if (failWrites) DomainError.DatabaseError.WriteFailed("x").left() else op() })

    private suspend fun setUp(): String {
        repos.projects.saveProject(project)
        val id = uuidOf(7)
        repo.savePage(testPage(id, "Closet Plan"))
        files["/g/pages/Closet Plan.md"] = "| a |"
        return id
    }

    @Test
    fun searchIsBoundedAndIgnoresShortQueries() = runTest {
        setUp()
        val s = service()
        assertTrue(s.search("c").isEmpty())
        assertEquals(0, pages.searches)
        assertEquals(listOf(PickerPage(uuidOf(7), "Closet Plan")), s.search(" clos "))
        assertEquals(1, pages.searches)
    }

    @Test
    fun bindStoresUuidAndHashOnlyAndListsTheTitleLocally() = runTest {
        val id = setUp()
        val s = service()
        assertEquals(BindOutcome.Bound, s.bind("pr1", SourceBindingKind.TABLE, id))
        val stored = repos.projects.loadProject(ProjectUuid("pr1")).getOrNull()!!.project.settings
        assertEquals(listOf(SourceBinding(SourceBindingKind.TABLE, id, ContentHasher.sha256("| a |"))), SourceBindings.of(stored))
        assertFalse(stored.toString().contains("Closet Plan"), "the title never enters the stored settings")
        assertEquals(listOf(BoundSourceRow(SourceBindingKind.TABLE, id, "Closet Plan")), s.listBound("pr1"))
    }

    @Test
    fun unbindRemovesTheEntry() = runTest {
        val id = setUp()
        val s = service()
        s.bind("pr1", SourceBindingKind.PROSE, id)
        assertEquals(BindOutcome.Bound, s.unbind("pr1", SourceBindingKind.PROSE, id))
        assertTrue(s.listBound("pr1").isEmpty())
    }

    @Test
    fun refusesUnknownProjectPageStubAndUnreadableFile() = runTest {
        val id = setUp()
        val s = service()
        assertEquals(BindOutcome.ProjectNotFound, s.bind("nope", SourceBindingKind.TABLE, id))
        assertEquals(BindOutcome.ProjectNotFound, s.unbind("nope", SourceBindingKind.TABLE, id))
        assertEquals(BindOutcome.PageNotFound, s.bind("pr1", SourceBindingKind.TABLE, uuidOf(99)))
        repo.savePage(testPage(uuidOf(8), "Stub", loaded = false))
        assertEquals(BindOutcome.NotReadable, s.bind("pr1", SourceBindingKind.TABLE, uuidOf(8)))
        repo.savePage(testPage(uuidOf(9), "Unread"))
        assertEquals(BindOutcome.NotReadable, s.bind("pr1", SourceBindingKind.TABLE, uuidOf(9)))
        assertTrue(s.listBound("nope").isEmpty())
    }

    @Test
    fun limitAndWriteFailureAreReported() = runTest {
        val id = setUp()
        val s = service()
        var settings = repos.projects.loadProject(ProjectUuid("pr1")).getOrNull()!!.project
        repeat(SourceBindings.MAX_BINDINGS) { settings = settings.copy(settings = SourceBindings.with(settings.settings, SourceBinding(SourceBindingKind.PROSE, "q$it", "h"))!!) }
        repos.projects.saveProject(settings)
        assertEquals(BindOutcome.LimitReached, s.bind("pr1", SourceBindingKind.TABLE, id))
        failWrites = true
        repos.projects.saveProject(project.copy(uuid = ProjectUuid("pr2"), slug = "proj-0a1b2d"))
        assertTrue(s.bind("pr2", SourceBindingKind.TABLE, id) is BindOutcome.Failed)
    }

    @Test
    fun readBoundSourcesFollowsTheFirstProject() = runTest {
        val reader = ProjectSourceReader(pages) { files[it] }
        assertEquals(SourceReadResult.EMPTY, readBoundSources(repos, reader))
        val id = setUp()
        assertEquals(SourceReadResult.EMPTY, readBoundSources(repos, reader))
        service().bind("pr1", SourceBindingKind.TABLE, id)
        assertEquals(listOf("| a |"), readBoundSources(repos, reader).tablePages.map { it.markdown })
    }

    @Test
    fun collectorReadStageFeedsTheExtractorsAndReportsBoundPages() = runTest {
        val read = SourceReadResult(
            tablePages = listOf(TablePageText("page:1", "no table here")),
            boundPages = listOf(BoundPageRef("P", "/g/P.md")),
        )
        val out = ProjectSourceCollector(sources = { read }).collect()
        assertEquals(read.boundPages, out.boundPages)
        assertTrue(out.tableObservations.isEmpty())
        assertEquals(emptyList(), ProjectSourceCollector().collect().boundPages)
    }
}
