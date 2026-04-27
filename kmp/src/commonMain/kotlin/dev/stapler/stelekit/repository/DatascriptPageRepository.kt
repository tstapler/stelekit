package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import dev.stapler.stelekit.coroutines.PlatformDispatcher

/**
 * Datalog-style in-memory repository for pages that mirrors Datascript behavior.
 * Uses Datalog query patterns similar to Logseq's Clojure implementation.
 * Cross-platform compatible - doesn't use JVM-specific Dispatchers.IO.
 *
 * Updated to use UUID-native storage.
 */
@OptIn(DirectRepositoryWrite::class)
class DatascriptPageRepository : PageRepository {

    private val pages = MutableStateFlow<Map<String, Page>>(emptyMap())

    /**
     * Datalog-style indexes for fast lookups
     */
    private val byUuid = MutableStateFlow<Map<String, Page>>(emptyMap())
    private val byName = MutableStateFlow<Map<String, Page>>(emptyMap())
    private val byNamespace = MutableStateFlow<Map<String, List<Page>>>(emptyMap())

    override fun getPageByUuid(uuid: String): Flow<Either<DomainError, Page?>> {
        return pages.map { map ->
            map[uuid].right()
        }
    }

    override fun getPageByName(name: String): Flow<Either<DomainError, Page?>> {
        return byName.map { map ->
            map[name].right()
        }
    }

    override fun getPagesInNamespace(namespace: String): Flow<Either<DomainError, List<Page>>> {
        return byNamespace.map { map ->
            (map[namespace] ?: emptyList()).right()
        }
    }

    override fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
        return pages.map { map ->
            val result = map.values.sortedBy { it.name }.drop(offset).take(limit)
            result.right()
        }
    }

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
        return pages.map { map ->
            val result = map.values
                .filter { it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name }
                .drop(offset)
                .take(limit)
            result.right()
        }
    }

    override fun getAllPages(): Flow<Either<DomainError, List<Page>>> {
        return pages.map { map ->
            map.values.toList().right()
        }
    }

    override fun getJournalPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
        return pages.map { map ->
            val journals = map.values
                .filter { it.isJournal && it.journalDate != null }
                .sortedByDescending { it.journalDate }
                .drop(offset)
                .take(limit)
            journals.right()
        }
    }

    override fun getJournalPageByDate(date: kotlinx.datetime.LocalDate): Flow<Either<DomainError, Page?>> {
        return pages.map { map ->
            map.values.find { it.journalDate == date }.right()
        }
    }

    override fun getRecentPages(limit: Int): Flow<Either<DomainError, List<Page>>> {
        return pages.map { map ->
            map.values.sortedByDescending { it.updatedAt }.take(limit).right()
        }
    }

    override fun getUnloadedPages(): Flow<Either<DomainError, List<Page>>> {
        return pages.map { map ->
            map.values.filter { !it.isContentLoaded }.right()
        }
    }

    override suspend fun savePage(page: Page): Either<DomainError, Unit> {
        return try {
            val current = pages.value.toMutableMap()
            current[page.uuid] = page
            pages.value = current
            refreshIndexes(current)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun savePages(pageList: List<Page>): Either<DomainError, Unit> {
        return try {
            val current = pages.value.toMutableMap()
            pageList.forEach { current[it.uuid] = it }
            pages.value = current
            refreshIndexes(current)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun toggleFavorite(pageUuid: String): Either<DomainError, Unit> {
        return try {
            val page = pages.value[pageUuid] ?: return DomainError.DatabaseError.NotFound("page", pageUuid).left()
            val newPage = page.copy(isFavorite = !page.isFavorite)
            savePage(newPage)
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun renamePage(pageUuid: String, newName: String): Either<DomainError, Unit> {
        return try {
            val page = pages.value[pageUuid] ?: return DomainError.DatabaseError.NotFound("page", pageUuid).left()
            val newPage = page.copy(name = newName, updatedAt = kotlin.time.Clock.System.now())
            savePage(newPage)
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun deletePage(pageUuid: String): Either<DomainError, Unit> {
        return try {
            val current = pages.value.toMutableMap()
            current.remove(pageUuid)
            pages.value = current
            refreshIndexes(current)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override fun countPages(): Flow<Either<DomainError, Long>> {
        return pages.map { it.size.toLong().right() }
    }

    override suspend fun clear() {
        pages.value = emptyMap()
        refreshIndexes(emptyMap())
    }

    private fun refreshIndexes(currentPages: Map<String, Page>) {
        val allPages = currentPages.values
        byUuid.value = currentPages
        byName.value = allPages.associateBy { it.name }
        byNamespace.value = allPages.filter { it.namespace != null }.groupBy { it.namespace!! }
    }
}
