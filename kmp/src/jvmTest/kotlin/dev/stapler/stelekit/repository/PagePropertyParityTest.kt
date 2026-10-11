@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import arrow.core.getOrElse
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/** Holds every `getPagesWithProperty` backend to the same exact-match, paging and reactivity contract. */
class PagePropertyParityTest {
    private val now = Clock.System.now()

    private fun backends(): Map<String, PageRepository> = mapOf(
        "sqldelight" to SqlDelightPageRepository(SteleDatabase(DriverFactory().createDriver("jdbc:sqlite::memory:"))),
        "in-memory" to InMemoryPageRepository(),
        "datalog" to DatalogPageRepository(),
    )

    private fun page(id: String, name: String, props: Map<String, String>) =
        Page(uuid = PageUuid(id), name = name, createdAt = now, updatedAt = now, properties = props)

    private suspend fun names(repo: PageRepository, key: String, value: String, limit: Int, offset: Int): List<String> =
        withTimeout(10_000) {
            repo.getPagesWithProperty(key, value, limit, offset).first().getOrElse { emptyList() }.map { it.name }
        }

    @Test
    fun `substring and comma-in-value collisions are rejected on every backend`() = runBlocking {
        backends().forEach { (label, repo) ->
            repo.savePage(page("1", "bookmark", mapOf("type" to "bookmark")))
            repo.savePage(page("2", "sub", mapOf("sub-type" to "bookmark")))
            repo.savePage(page("3", "list", mapOf("tags" to "a,b")))
            repo.savePage(page("4", "single", mapOf("tags" to "a")))
            assertEquals(listOf("bookmark"), names(repo, "type", "bookmark", 10, 0), "$label substring key")
            assertEquals(listOf("single"), names(repo, "tags", "a", 10, 0), "$label comma value")
        }
    }

    @Test
    fun `window fills past false positives and honours offset on every backend`() = runBlocking {
        backends().forEach { (label, repo) ->
            (1..5).forEach { repo.savePage(page("f$it", "a$it", mapOf("tags" to "a,b"))) }
            listOf("z1", "z2", "z3").forEachIndexed { i, n -> repo.savePage(page("t$i", n, mapOf("tags" to "a"))) }
            assertEquals(listOf("z1", "z2"), names(repo, "tags", "a", 2, 0), "$label first window")
            assertEquals(listOf("z2", "z3"), names(repo, "tags", "a", 2, 1), "$label offset window")
        }
    }

    @Test
    fun `new matching page re-emits on every backend`() = runBlocking {
        backends().forEach { (label, repo) ->
            repo.savePage(page("1", "one", mapOf("k" to "v")))
            val seen = CopyOnWriteArrayList<List<String>>()
            val job = launch(Dispatchers.Default) {
                repo.getPagesWithProperty("k", "v", 10, 0).collect { e -> e.onRight { l -> seen += l.map { it.name } } }
            }
            withTimeout(10_000) { while (seen.isEmpty()) delay(20) }
            repo.savePage(page("2", "two", mapOf("k" to "v")))
            withTimeout(10_000) { while (seen.last() != listOf("one", "two")) delay(20) }
            assertEquals(listOf("one", "two"), seen.last(), label)
            job.cancel()
        }
    }
}
