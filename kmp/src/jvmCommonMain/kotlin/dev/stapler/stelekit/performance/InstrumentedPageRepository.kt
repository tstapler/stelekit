package dev.stapler.stelekit.performance

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageNameEntry
import dev.stapler.stelekit.repository.PageRepository
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate

class InstrumentedPageRepository(
    private val delegate: PageRepository,
    private val tracer: Tracer
) : PageRepository {

    override fun getPageByUuid(uuid: PageUuid): Flow<Either<DomainError, Page?>> = delegate.getPageByUuid(uuid)

    override fun getPageByName(name: String): Flow<Either<DomainError, Page?>> = delegate.getPageByName(name)

    override fun getPagesInNamespace(namespace: String): Flow<Either<DomainError, List<Page>>> =
        delegate.getPagesInNamespace(namespace)

    override fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        delegate.getPages(limit, offset)

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        delegate.searchPages(query, limit, offset)

    override fun getJournalPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        delegate.getJournalPages(limit, offset)

    override fun getJournalPageByDate(date: LocalDate): Flow<Either<DomainError, Page?>> =
        delegate.getJournalPageByDate(date)

    override fun getRecentPages(limit: Int): Flow<Either<DomainError, List<Page>>> =
        delegate.getRecentPages(limit)

    override fun getFavoritePages(): Flow<Either<DomainError, List<Page>>> = delegate.getFavoritePages()

    override fun getPageNameEntries(): Flow<Either<DomainError, List<PageNameEntry>>> =
        delegate.getPageNameEntries()

    override fun getUnloadedPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        delegate.getUnloadedPages(limit, offset)

    override fun countUnloadedPages(): Flow<Either<DomainError, Long>> = delegate.countUnloadedPages()

    // Explicit delegation (not the interface defaults) so the SQL-optimized chunked IN
    // queries and bounded-batch snapshot of the wrapped repository are preserved.
    override suspend fun getPagesByNames(names: Collection<String>): Either<DomainError, List<Page>> =
        delegate.getPagesByNames(names)

    override suspend fun getJournalPagesByDates(dates: Collection<LocalDate>): Either<DomainError, List<Page>> =
        delegate.getJournalPagesByDates(dates)

    override suspend fun getAllPagesSnapshot(batchSize: Int): Either<DomainError, List<Page>> =
        delegate.getAllPagesSnapshot(batchSize)

    override fun countPages(): Flow<Either<DomainError, Long>> = delegate.countPages()

    @DirectRepositoryWrite
    override suspend fun savePage(page: Page): Either<DomainError, Unit> {
        val span = tracer.spanBuilder("page.save")
            .setAttribute("page.uuid", page.uuid.value)
            .startSpan()
        return try {
            delegate.savePage(page)
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            span.end()
        }
    }

    @DirectRepositoryWrite
    override suspend fun toggleFavorite(pageUuid: PageUuid): Either<DomainError, Unit> {
        val span = tracer.spanBuilder("page.toggleFavorite")
            .setAttribute("page.uuid", pageUuid.value)
            .startSpan()
        return try {
            delegate.toggleFavorite(pageUuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            span.end()
        }
    }

    @DirectRepositoryWrite
    override suspend fun renamePage(pageUuid: PageUuid, newName: String): Either<DomainError, Unit> {
        val span = tracer.spanBuilder("page.rename")
            .setAttribute("page.uuid", pageUuid.value)
            .startSpan()
        return try {
            delegate.renamePage(pageUuid, newName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            span.end()
        }
    }

    @DirectRepositoryWrite
    override suspend fun deletePage(pageUuid: PageUuid): Either<DomainError, Unit> {
        val span = tracer.spanBuilder("page.delete")
            .setAttribute("page.uuid", pageUuid.value)
            .startSpan()
        return try {
            delegate.deletePage(pageUuid)
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            span.end()
        }
    }
}
