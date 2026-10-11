package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class LinkClosureTest {
    private val t = Instant.fromEpochSeconds(0)

    private fun page(name: String, n: Int = name.hashCode()) = Page(
        uuid = PageUuid("00000000-0000-0000-0000-" + (n.toLong() and 0xFFFFFFFFFFFFL).toString(16).padStart(12, '0')),
        name = name,
        createdAt = t,
        updatedAt = t,
    )

    private fun merge(name: String, vararg links: String) =
        MergePage(name, blocks = listOf(MergeBlock("u-$name", links.joinToString(" ") { "[[$it]]" })))

    /** In-memory graph: name -> linked names. Records lookup chunk sizes. */
    private class Graph(val pages: Map<String, Page>) {
        private val byLower = pages.mapKeys { it.key.lowercase() }
        val chunks = mutableListOf<Int>()
        val lookup: suspend (List<String>) -> Either<DomainError, List<Page>> = { names ->
            chunks += names.size
            names.mapNotNull { n -> byLower[n.lowercase()] }.right()
        }
    }

    @Test
    fun `depth1 adds Q only, not R`() = runTest {
        val g = Graph(listOf("P", "Q", "R").mapIndexed { i, n -> n to page(n, i + 1) }.toMap())
        val r = LinkClosure.expand(listOf(merge("P", "Q")), LinkClosurePolicy.Depth1(), g.lookup).getOrFail()
        assertEquals(listOf("Q"), r.added.map { it.name })
        assertEquals(0, r.notIncluded)
        assertFalse(r.requiresConfirmation)
    }

    @Test
    fun `off adds nothing and never touches the repository`() = runTest {
        val g = Graph(mapOf("Q" to page("Q", 1)))
        val r = LinkClosure.expand(listOf(merge("P", "Q")), LinkClosurePolicy.Off, g.lookup).getOrFail()
        assertEquals(emptyList(), r.added)
        assertTrue(g.chunks.isEmpty())
        assertEquals(LinkClosurePolicy.Off, LinkClosurePolicy.DEFAULT)
    }

    @Test
    fun `1340 page closure requires confirmation and truncates at 1000 with remainder count`() = runTest {
        val all = (1..1340).associate { i -> "Page $i" to page("Page $i", i) }
        val g = Graph(all)
        val selected = listOf(merge("Root", *all.keys.toTypedArray()))
        val r = LinkClosure.expand(selected, LinkClosurePolicy.Depth1(), g.lookup).getOrFail()
        assertEquals(1000, r.added.size)
        assertEquals(340, r.notIncluded)
        assertEquals(1340, r.totalResolved)
        assertTrue(r.requiresConfirmation)
        assertTrue(g.chunks.all { it <= LinkClosure.LOOKUP_CHUNK }, "chunks: ${g.chunks}")
        assertEquals(listOf(500, 500, 340), g.chunks)
    }

    @Test
    fun `exactly 200 does not require confirmation, 201 does`() {
        fun result(n: Int) = LinkClosure.limit((1..n).map { page("P$it", it) }, LinkClosurePolicy.Depth1())
        assertFalse(result(200).requiresConfirmation)
        assertTrue(result(201).requiresConfirmation)
    }

    @Test
    fun `caller cap is clamped to the hard cap`() {
        val r = LinkClosure.limit((1..1100).map { page("P$it", it) }, LinkClosurePolicy.Depth1(cap = 5000))
        assertEquals(1000, r.added.size)
        assertEquals(100, r.notIncluded)
        assertEquals(10, LinkClosure.limit((1..30).map { page("P$it", it) }, LinkClosurePolicy.Depth1(cap = 10)).added.size)
    }

    @Test
    fun `already selected pages, self links and duplicate links are not re-added`() = runTest {
        val g = Graph(listOf("P", "Q", "S").mapIndexed { i, n -> n to page(n, i + 1) }.toMap())
        val selected = listOf(merge("P", "p", "Q", "q", "S"), merge("S", "P"))
        val r = LinkClosure.expand(selected, LinkClosurePolicy.Depth1(), g.lookup).getOrFail()
        assertEquals(listOf("Q"), r.added.map { it.name })
    }

    @Test
    fun `links to missing pages are skipped and lookup failure propagates`() = runTest {
        val g = Graph(mapOf("Q" to page("Q", 1)))
        val r = LinkClosure.expand(listOf(merge("P", "Q", "Ghost")), LinkClosurePolicy.Depth1(), g.lookup).getOrFail()
        assertEquals(listOf("Q"), r.added.map { it.name })

        val failed = LinkClosure.expand(listOf(merge("P", "Q")), LinkClosurePolicy.Depth1()) {
            DomainError.DatabaseError.ReadFailed("boom").left()
        }
        assertIs<DomainError.DatabaseError.ReadFailed>((failed as Either.Left).value)
    }

    @Test
    fun `links in nested children and tag form are found`() {
        val p = MergePage("P", blocks = listOf(MergeBlock(null, "a", children = listOf(MergeBlock(null, "see #[[Deep Page]] and [[Other]]")))))
        assertEquals(listOf("Deep Page", "Other"), LinkClosure.candidateNames(listOf(p)))
    }

    @Test
    fun `property - closure is idempotent, capped and never contains selected pages`() = runTest {
        checkAll(40, Arb.int(0..1500), Arb.int(0..2000)) { linked, cap ->
            val all = (1..linked).associate { "L$it" to page("L$it", it) }
            val g = Graph(all)
            val selected = listOf(merge("Root", *all.keys.toTypedArray()))
            val policy = LinkClosurePolicy.Depth1(cap)
            val first = LinkClosure.expand(selected, policy, g.lookup).getOrFail()
            val again = LinkClosure.expand(selected, policy, g.lookup).getOrFail()
            assertEquals(first, again)
            assertTrue(first.added.size <= LinkClosure.HARD_CAP && first.added.size <= cap)
            assertEquals(linked, first.totalResolved)
            assertEquals(linked - first.added.size, first.notIncluded)
            assertEquals(first.added.size, first.added.map { it.name }.toSet().size)
            assertEquals(linked > LinkClosure.CONFIRM_THRESHOLD, first.requiresConfirmation)
            // Feeding the added pages back as selection adds nothing new beyond itself (depth 1, no transitive growth).
            val withAdded = selected + first.added.map { MergePage(it.name) }
            val next = LinkClosure.expand(withAdded, policy, g.lookup).getOrFail()
            assertTrue(next.added.none { a -> a.name == "Root" || first.added.any { it.name == a.name } })
        }
    }

    private fun <T> Either<DomainError, T>.getOrFail(): T = when (this) {
        is Either.Right -> value
        is Either.Left -> error("expected Right but was $value")
    }
}
