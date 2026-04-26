package dev.stapler.stelekit.performance

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

class InstrumentedPageRepository(
    private val delegate: PageRepository,
    private val tracer: Tracer
) : PageRepository {

    override fun getPageByUuid(uuid: String): Flow<Either<DomainError, Page?>> = delegate.getPageByUuid(uuid)

    override fun getPageByName(name: String): Flow<Either<DomainError, Page?>> = delegate.getPageByName(name)

    override fun getPagesInNamespace(namespace: String): Flow<Either<DomainError, List<Page>>> =
        delegate.getPagesInNamespace(namespace)

    override fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        delegate.getPages(limit, offset)

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        delegate.searchPages(query, limit, offset)

    override fun getAllPages(): Flow<Either<DomainError, List<Page>>> = delegate.getAllPages()

    override fun getJournalPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        delegate.getJournalPages(limit, offset)

    override fun getJournalPageByDate(date: LocalDate): Flow<Either<DomainError, Page?>> =
        delegate.getJournalPageByDate(date)

    override fun getRecentPages(limit: Int): Flow<Either<DomainError, List<Page>>> =
        delegate.getRecentPages(limit)

    override fun getUnloadedPages(): Flow<Either<DomainError, List<Page>>> = delegate.getUnloadedPages()

    override fun countPages(): Flow<Either<DomainError, Long>> = delegate.countPages()

    @DirectRepositoryWrite
    override suspend fun savePage(page: Page): Either<DomainError, Unit> {
        val span = tracer.spanBuilder("page.save")
            .setAttribute("page.uuid", page.uuid)
            .startSpan()
        return try {
            delegate.savePage(page)
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            span.end()
        }
    }

    @DirectRepositoryWrite
    override suspend fun savePages(pages: List<Page>): Either<DomainError, Unit> {
        val span = tracer.spanBuilder("page.savePages")
            .setAttribute("page.count", pages.size.toLong())
            .startSpan()
        return try {
            delegate.savePages(pages)
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            span.end()
        }
    }

    @DirectRepositoryWrite
    override suspend fun toggleFavorite(pageUuid: String): Either<DomainError, Unit> {
        val span = tracer.spanBuilder("page.toggleFavorite")
            .setAttribute("page.uuid", pageUuid)
            .startSpan()
        return try {
            delegate.toggleFavorite(pageUuid)
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            span.end()
        }
    }

    @DirectRepositoryWrite
    override suspend fun renamePage(pageUuid: String, newName: String): Either<DomainError, Unit> {
        val span = tracer.spanBuilder("page.rename")
            .setAttribute("page.uuid", pageUuid)
            .startSpan()
        return try {
            delegate.renamePage(pageUuid, newName)
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            span.end()
        }
    }

    @DirectRepositoryWrite
    override suspend fun deletePage(pageUuid: String): Either<DomainError, Unit> {
        val span = tracer.spanBuilder("page.delete")
            .setAttribute("page.uuid", pageUuid)
            .startSpan()
        return try {
            delegate.deletePage(pageUuid)
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            span.end()
        }
    }

    @DirectRepositoryWrite
    override suspend fun clear() {
        val span = tracer.spanBuilder("page.clear").startSpan()
        try {
            delegate.clear()
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            span.end()
        }
    }
}
