@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Real-SQLite proof that the query-block reads are push-based (re-emit on write, no re-subscribe). */
class QueryRepositoryReactivityTest {
    private val now = Clock.System.now()

    private class Repos(val blocks: SqlDelightBlockRepository, val pages: SqlDelightPageRepository)

    private fun repos(): Repos {
        val database = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite::memory:"))
        return Repos(SqlDelightBlockRepository(database), SqlDelightPageRepository(database))
    }

    private fun page(uuid: String, name: String, props: Map<String, String> = emptyMap()) =
        Page(uuid = PageUuid(uuid), name = name, createdAt = now, updatedAt = now, properties = props)

    private fun block(uuid: String, content: String, pageUuid: String = "p1") = Block(
        uuid = BlockUuid(uuid), pageUuid = PageUuid(pageUuid), content = content,
        position = "a0", createdAt = now, updatedAt = now,
    )

    private suspend fun <T> awaitLatest(
        seen: CopyOnWriteArrayList<T>,
        what: String,
        predicate: (T) -> Boolean,
    ) {
        withTimeout(10_000) {
            while (seen.isEmpty() || !predicate(seen.last())) delay(20)
        }
        assertTrue(predicate(seen.last()), what)
    }

    private fun <T> kotlinx.coroutines.CoroutineScope.collectRights(
        flow: Flow<Either<DomainError, T>>,
        into: CopyOnWriteArrayList<T>,
    ) = launch(Dispatchers.Default) {
        flow.collect { either -> either.onRight { into += it } }
    }

    @Test
    fun `findBlocksWithTaskMarker re-emits after marker edit commits`() = runBlocking {
        val r = repos()
        r.pages.savePage(page("p1", "P"))
        r.blocks.saveBlocks(listOf(block("b1", "TODO write the report"), block("b2", "TODOLIST not a task")))

        val seen = CopyOnWriteArrayList<List<Block>>()
        val job = collectRights(r.blocks.findBlocksWithTaskMarker(setOf("TODO"), 50, 0), seen)
        awaitLatest(seen, "initial") { l -> l.map { it.uuid.value } == listOf("b1") }

        r.blocks.saveBlocks(listOf(block("b1", "DONE write the report")))
        awaitLatest(seen, "re-emits empty") { it.isEmpty() }
        job.cancel()
    }

    @Test
    fun `getPagesWithProperty re-emits after property edit and rejects substring collisions`() = runBlocking {
        val r = repos()
        r.pages.savePage(page("p1", "Book", mapOf("type" to "book", "author" to "Jane")))
        r.pages.savePage(page("p2", "Mark", mapOf("sub-type" to "bookmark")))
        r.pages.savePage(page("p3", "Other", mapOf("mytype" to "book")))

        val seen = CopyOnWriteArrayList<List<Page>>()
        val job = collectRights(r.pages.getPagesWithProperty("type", "book", 50, 0), seen)
        awaitLatest(seen, "only exact pair") { l -> l.map { it.uuid.value } == listOf("p1") }

        r.pages.savePage(page("p1", "Book", mapOf("author" to "Jane")))
        awaitLatest(seen, "re-emits empty") { it.isEmpty() }
        job.cancel()
    }

    @Test
    fun `findReferencingBlocksReactive re-emits when a new referencing block is saved`() = runBlocking {
        val r = repos()
        r.pages.savePage(page("p1", "P"))

        val seen = CopyOnWriteArrayList<List<Block>>()
        val job = collectRights(r.blocks.findReferencingBlocksReactive("ProjectX", 50, 0), seen)
        awaitLatest(seen, "initially empty") { it.isEmpty() }

        r.blocks.saveBlocks(listOf(block("b1", "see [[ProjectX]]"), block("b2", "see [[ProjectXYZ]]")))
        awaitLatest(seen, "re-emits new reference") { l -> l.map { it.uuid.value } == listOf("b1") }
        job.cancel()
    }

    @Test
    fun `findReferencingBlocksReactive finds hashtag and alias forms`() = runBlocking {
        val r = repos()
        r.pages.savePage(page("p1", "P"))
        r.blocks.saveBlocks(
            listOf(block("b1", "tagged #ProjectX here"), block("b2", "[[ProjectX|alias]]"), block("b3", "none")),
        )
        val seen = CopyOnWriteArrayList<List<Block>>()
        val job = collectRights(r.blocks.findReferencingBlocksReactive("ProjectX", 50, 0), seen)
        awaitLatest(seen, "both forms") { l -> l.map { it.uuid.value }.toSet() == setOf("b1", "b2") }
        job.cancel()
        assertEquals(2, seen.last().size)
    }

    @Test
    fun `getPagesWithProperty fills the window past comma-in-value false positives`() = runBlocking {
        val r = repos()
        // "a1".."a5" store tags:a,b — the SQL token match for tags:a hits them, the exact parse does not.
        (1..5).forEach { i -> r.pages.savePage(page("f$i", "a$i", mapOf("tags" to "a,b"))) }
        r.pages.savePage(page("t1", "z1", mapOf("tags" to "a")))
        r.pages.savePage(page("t2", "z2", mapOf("tags" to "a")))

        val seen = CopyOnWriteArrayList<List<Page>>()
        val job = collectRights(r.pages.getPagesWithProperty("tags", "a", 2, 0), seen)
        awaitLatest(seen, "true matches fill the window") { l -> l.map { it.name } == listOf("z1", "z2") }
        job.cancel()
    }
}
