@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.query

import arrow.core.Either
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.DatalogBlockRepository
import dev.stapler.stelekit.repository.DatalogPageRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class QueryExecutorTest {
    private val now = Clock.System.now()
    private val blocks = DatalogBlockRepository()
    private val pages = DatalogPageRepository()
    private val executor = QueryExecutor(blocks, pages, blocks)

    private suspend fun page(
        uuid: String,
        name: String,
        props: Map<String, String> = emptyMap(),
        journal: LocalDate? = null,
    ) {
        pages.savePage(
            Page(
                uuid = PageUuid(uuid), name = name, createdAt = now, updatedAt = now,
                properties = props, isJournal = journal != null, journalDate = journal,
            )
        )
    }

    private suspend fun block(uuid: String, page: String, content: String) {
        blocks.saveBlock(
            Block(
                uuid = BlockUuid(uuid), pageUuid = PageUuid(page), content = content,
                position = "a0", createdAt = now, updatedAt = now,
            )
        )
    }

    private suspend fun ids(q: SimpleQuery): Set<String> {
        val r = executor.executeQuery(q).first()
        assertTrue(r is Either.Right, "expected Right, got $r")
        return r.value.map { it.uuid.value }.toSet()
    }

    @Test
    fun `executeQuery dispatches Task filter by marker prefix`() = runBlocking {
        page("p1", "P")
        block("b1", "p1", "NOW write report")
        block("b2", "p1", "LATER file taxes")
        block("b3", "p1", "NOWHERE is a place")
        block("b4", "p1", "plain note")
        assertEquals(setOf("b1", "b2"), ids(QueryFilter.Task(setOf("NOW", "LATER"))))
    }

    @Test
    fun `executeQuery dispatches PageProperty and rejects substring collisions`() = runBlocking {
        page("p1", "Book", mapOf("type" to "book"))
        page("p2", "Mark", mapOf("sub-type" to "bookmark"))
        block("b1", "p1", "in book")
        block("b2", "p1", "also in book")
        block("b3", "p2", "in bookmark")
        assertEquals(setOf("b1", "b2"), ids(QueryFilter.PageProperty("type", "book")))
    }

    @Test
    fun `executeQuery dispatches Between to journal pages in inclusive range`() = runBlocking {
        page("j1", "2026_01_01", journal = LocalDate(2026, 1, 1))
        page("j2", "2026_01_05", journal = LocalDate(2026, 1, 5))
        page("j3", "2026_01_10", journal = LocalDate(2026, 1, 10))
        block("b1", "j1", "a")
        block("b2", "j2", "b")
        block("b3", "j3", "c")
        assertEquals(setOf("b1", "b2"), ids(QueryFilter.Between("2026_01_01", "2026_01_05")))
    }

    @Test
    fun `executeQuery returns empty when Between references a nonexistent page`() = runBlocking {
        page("j1", "2026_01_01", journal = LocalDate(2026, 1, 1))
        block("b1", "j1", "a")
        assertEquals(emptySet(), ids(QueryFilter.Between("NoSuchPage", "2026_01_01")))
    }

    @Test
    fun `executeQuery dispatches PageRef for wikilink alias and tag forms`() = runBlocking {
        page("p1", "P")
        block("b1", "p1", "see [[ProjectX]]")
        block("b2", "p1", "tagged #ProjectX")
        block("b3", "p1", "[[ProjectX|alias]]")
        block("b4", "p1", "[[ProjectXYZ]]")
        assertEquals(setOf("b1", "b2", "b3"), ids(QueryFilter.PageRef("ProjectX")))
    }

    @Test
    fun `executeQuery computes intersection for And and union for Or`() = runBlocking {
        page("p1", "P")
        block("b1", "p1", "[[a]] [[b]]")
        block("b2", "p1", "[[a]]")
        block("b3", "p1", "[[b]]")
        val a = QueryFilter.PageRef("a")
        val b = QueryFilter.PageRef("b")
        assertEquals(setOf("b1"), ids(And(a, b)))
        assertEquals(setOf("b1", "b2", "b3"), ids(Or(a, b)))
    }

    @Test
    fun `executeQuery computes set difference for And with Not operand`() = runBlocking {
        page("p1", "P")
        block("b1", "p1", "[[tag1]] [[tag2]]")
        block("b2", "p1", "[[tag2]]")
        val tag1 = QueryFilter.PageRef("tag1")
        val tag2 = QueryFilter.PageRef("tag2")
        assertEquals(setOf("b2"), ids(And(tag2, Not(tag1))))
        assertEquals(setOf("b2"), ids(And(Not(tag1), tag2)))
    }

    @Test
    fun `executeQuery pairs Task with PageRef`() = runBlocking {
        page("p1", "P")
        block("b1", "p1", "NOW fix [[ProjectX]]")
        block("b2", "p1", "NOW unrelated")
        block("b3", "p1", "discuss [[ProjectX]]")
        assertEquals(
            setOf("b1"),
            ids(And(QueryFilter.Task(setOf("NOW")), QueryFilter.PageRef("ProjectX"))),
        )
    }

    @Test
    fun `executeQuery never lists query blocks themselves`() = runBlocking {
        page("p1", "P")
        block("b1", "p1", "see [[ProjectX]]")
        block("q1", "p1", "{{query [[ProjectX]]}}")
        assertEquals(setOf("b1"), ids(QueryFilter.PageRef("ProjectX")))
    }

    @Test
    fun `and operands are not starved by the display cap`() = runBlocking {
        page("p1", "P")
        // 250 NOW blocks, only the oldest one also references [[X]]: a 200-row operand window would miss it.
        blocks.saveBlocks(
            (1..250).map { i ->
                Block(
                    uuid = BlockUuid("n$i"), pageUuid = PageUuid("p1"),
                    content = if (i == 1) "NOW old [[X]]" else "NOW item $i",
                    position = "a$i", createdAt = now.plus(kotlin.time.Duration.parse("${i}s")), updatedAt = now,
                )
            }
        )
        assertEquals(setOf("n1"), ids(And(QueryFilter.Task(setOf("NOW")), QueryFilter.PageRef("X"))))
    }

    @Test
    fun `executeQuery reflects later writes through the same Flow`() = runBlocking {
        page("p1", "P")
        block("b1", "p1", "NOW first")
        val emissions = async {
            executor.executeQuery(QueryFilter.Task(setOf("NOW")))
                .map { either -> (either as Either.Right).value.map { it.uuid.value }.toSet() }
                .first { it.size == 2 }
        }
        delay(100)
        block("b2", "p1", "NOW second")
        assertEquals(setOf("b1", "b2"), withTimeout(10_000) { emissions.await() })
    }

    @Test
    fun `executeQuery returns empty for reversed Between bounds`() = runBlocking {
        page("j1", "2026_01_01", journal = LocalDate(2026, 1, 1))
        page("j2", "2026_01_05", journal = LocalDate(2026, 1, 5))
        block("b1", "j1", "a")
        block("b2", "j2", "b")
        assertEquals(emptySet(), ids(QueryFilter.Between("2026_01_05", "2026_01_01")))
    }

    @Test
    fun `executeQuery finds Between range older than 500 newest journals`() = runBlocking {
        val old = LocalDate(2020, 1, 1)
        page("old", "2020_01_01", journal = old)
        block("bo", "old", "ancient")
        var d = LocalDate(2022, 1, 1)
        repeat(520) { i ->
            page("n$i", "newer_$i", journal = d)
            d = d.plus(1, DateTimeUnit.DAY)
        }
        assertEquals(setOf("bo"), ids(QueryFilter.Between("2020_01_01", "2020_01_01")))
    }
}
