package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlin.Result.Companion.success

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

    override fun getPageByUuid(uuid: String): Flow<Result<Page?>> {
        return pages.map { map ->
            success(map[uuid])
        }
    }

    override fun getPageByName(name: String): Flow<Result<Page?>> {
        return byName.map { map ->
            success(map[name])
        }
    }

    override fun getPagesInNamespace(namespace: String): Flow<Result<List<Page>>> {
        return byNamespace.map { map ->
            success(map[namespace] ?: emptyList())
        }
    }

    override fun getPages(limit: Int, offset: Int): Flow<Result<List<Page>>> {
        return pages.map { map ->
            val result = map.values.sortedBy { it.name }.drop(offset).take(limit)
            success(result)
        }
    }

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>> {
        return pages.map { map ->
            val result = map.values
                .filter { it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name }
                .drop(offset)
                .take(limit)
            success(result)
        }
    }

    override fun getAllPages(): Flow<Result<List<Page>>> {
        return pages.map { map ->
            success(map.values.toList())
        }
    }

    override fun getJournalPages(limit: Int, offset: Int): Flow<Result<List<Page>>> {
        return pages.map { map ->
            val journals = map.values
                .filter { it.isJournal && it.journalDate != null }
                .sortedByDescending { it.journalDate }
                .drop(offset)
                .take(limit)
            success(journals)
        }
    }

    override fun getJournalPageByDate(date: kotlinx.datetime.LocalDate): Flow<Result<Page?>> {
        return pages.map { map ->
            success(map.values.find { it.journalDate == date })
        }
    }

    override fun getRecentPages(limit: Int): Flow<Result<List<Page>>> {
        return pages.map { map ->
            success(map.values.sortedByDescending { it.updatedAt }.take(limit))
        }
    }

    override fun getUnloadedPages(): Flow<Result<List<Page>>> {
        return pages.map { map ->
            success(map.values.filter { !it.isContentLoaded })
        }
    }

    override suspend fun savePage(page: Page): Result<Unit> {
        return try {
            val current = pages.value.toMutableMap()
            current[page.uuid] = page
            pages.value = current

            // Update indexes
            refreshIndexes(current)
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun toggleFavorite(pageUuid: String): Result<Unit> {
        return try {
            val page = pages.value[pageUuid] ?: return Result.failure(Exception("Page not found"))
            val newPage = page.copy(isFavorite = !page.isFavorite)
            savePage(newPage)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun renamePage(pageUuid: String, newName: String): Result<Unit> {
        return try {
            val page = pages.value[pageUuid] ?: return Result.failure(Exception("Page not found"))
            val newPage = page.copy(name = newName, updatedAt = kotlin.time.Clock.System.now())
            savePage(newPage)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deletePage(pageUuid: String): Result<Unit> {
        return try {
            val current = pages.value.toMutableMap()
            current.remove(pageUuid)
            pages.value = current
            refreshIndexes(current)
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun countPages(): Flow<Result<Long>> {
        return pages.map { success(it.size.toLong()) }
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
