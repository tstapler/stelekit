// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.PageRepository
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(DirectRepositoryWrite::class)
class InstrumentedPageRepositoryTest {

    private class CollectingExporter : SpanExporter {
        val spans = mutableListOf<SpanData>()
        override fun export(batch: Collection<SpanData>): CompletableResultCode {
            spans.addAll(batch); return CompletableResultCode.ofSuccess()
        }
        override fun flush() = CompletableResultCode.ofSuccess()
        override fun shutdown() = CompletableResultCode.ofSuccess()
    }

    private val exporter = CollectingExporter()
    private val tracer = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
        .get("test-scope")

    private fun page(id: String) = Page(
        uuid = PageUuid(id), name = "Page $id",
        createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /** Delegates to a real in-memory repo but throws from every write. */
    private fun throwingRepo(): PageRepository = object : PageRepository by InMemoryPageRepository() {
        override suspend fun savePage(page: Page): Either<DomainError, Unit> = error("save boom")
        override suspend fun savePages(pages: List<Page>): Either<DomainError, Unit> = error("savePages boom")
        override suspend fun toggleFavorite(pageUuid: PageUuid): Either<DomainError, Unit> = error("fav boom")
        override suspend fun renamePage(pageUuid: PageUuid, newName: String): Either<DomainError, Unit> = error("rename boom")
        override suspend fun deletePage(pageUuid: PageUuid): Either<DomainError, Unit> = error("delete boom")
        override suspend fun clear() = error("clear boom")
    }

    private val repo = InstrumentedPageRepository(InMemoryPageRepository(), tracer)

    @Test
    fun `savePage emits a page_save span with the page uuid and delegates`() = runBlocking {
        val result = repo.savePage(page("p1"))
        assertTrue(result.isRight())
        val span = exporter.spans.single()
        assertEquals("page.save", span.name)
        assertEquals("p1", span.attributes.get(AttributeKey.stringKey("page.uuid")))
        assertTrue(span.hasEnded())
        assertEquals("Page p1", repo.getPageByUuid(PageUuid("p1")).first().getOrNull()?.name)
    }

    @Test
    fun `savePage marks the span ERROR, ends it, and rethrows`() = runBlocking {
        val failing = InstrumentedPageRepository(throwingRepo(), tracer)
        assertFailsWith<IllegalStateException> { failing.savePage(page("p1")) }
        val span = exporter.spans.single()
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        assertEquals("save boom", span.status.description)
    }

    @Test
    fun `savePages span carries page_count`() = runBlocking {
        repo.savePages(listOf(page("a"), page("b"), page("c")))
        val span = exporter.spans.single()
        assertEquals("page.savePages", span.name)
        assertEquals(3L, span.attributes.get(AttributeKey.longKey("page.count")))
    }

    @Test
    fun `toggleFavorite renamePage and deletePage emit named spans`() = runBlocking {
        repo.savePage(page("p1"))
        exporter.spans.clear()
        repo.toggleFavorite(PageUuid("p1"))
        repo.renamePage(PageUuid("p1"), "Renamed")
        repo.deletePage(PageUuid("p1"))
        assertEquals(listOf("page.toggleFavorite", "page.rename", "page.delete"), exporter.spans.map { it.name })
        assertTrue(exporter.spans.all { it.attributes.get(AttributeKey.stringKey("page.uuid")) == "p1" })
    }

    @Test
    fun `clear emits a page_clear span`() = runBlocking {
        repo.clear()
        assertEquals("page.clear", exporter.spans.single().name)
    }

    @Test
    fun `every write marks its span ERROR when the delegate throws`() = runBlocking {
        val failing = InstrumentedPageRepository(throwingRepo(), tracer)
        val uuid = PageUuid("p1")
        assertFailsWith<IllegalStateException> { failing.savePages(listOf(page("p1"))) }
        assertFailsWith<IllegalStateException> { failing.toggleFavorite(uuid) }
        assertFailsWith<IllegalStateException> { failing.renamePage(uuid, "x") }
        assertFailsWith<IllegalStateException> { failing.deletePage(uuid) }
        assertFailsWith<IllegalStateException> { failing.clear() }
        assertEquals(5, exporter.spans.size)
        assertTrue(exporter.spans.all { it.status.statusCode == StatusCode.ERROR && it.hasEnded() })
    }

    @Test
    fun `read-only methods delegate and create no spans`() = runBlocking {
        repo.savePage(page("p1"))
        exporter.spans.clear()

        assertEquals("Page p1", repo.getPageByUuid(PageUuid("p1")).first().getOrNull()?.name)
        repo.getPageByName("Page p1").first()
        repo.getPagesInNamespace("ns").first()
        assertEquals(1, repo.getPages(10, 0).first().getOrNull()?.size)
        repo.searchPages("Page", 10, 0).first()
        repo.getJournalPages(10, 0).first()
        repo.getPagesWithProperty("k", "v", 10, 0).first()
        repo.getRecentPages(10).first()
        repo.getFavoritePages().first()
        repo.getPageNameEntries().first()
        repo.getUnloadedPages(10, 0).first()
        repo.countUnloadedPages()
        repo.countPages().first()
        repo.getPagesByNames(listOf("Page p1"))
        repo.getJournalPagesByDates(emptyList())
        repo.getAllPagesSnapshot(100)

        assertTrue(exporter.spans.isEmpty(), "read paths must not create spans, got ${exporter.spans.map { it.name }}")
    }
}
