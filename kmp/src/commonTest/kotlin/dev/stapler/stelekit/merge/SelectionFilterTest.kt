package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SelectionFilterTest {
    private val t = Instant.fromEpochMilliseconds(0)
    private val ids = HashMap<String, Int>()

    // Page validation requires UUID-shaped ids; one stable id per name.
    private fun uuidFor(name: String): String =
        "00000000-0000-0000-0000-" + ids.getOrPut(name) { ids.size + 1 }.toString(16).padStart(12, '0')

    private fun page(
        name: String,
        journal: LocalDate? = null,
        props: Map<String, String> = emptyMap(),
    ) = Page(
        uuid = PageUuid(uuidFor(name)),
        name = name,
        createdAt = t,
        updatedAt = t,
        properties = props,
        isJournal = journal != null,
        journalDate = journal,
    )

    @Test
    fun `default filter matches everything`() {
        assertTrue(SelectionFilter().matches(page("a")))
        assertTrue(SelectionFilter().matches(page("2024-01-01", LocalDate(2024, 1, 1))))
    }

    @Test
    fun `journals false excludes journal pages only`() {
        val f = SelectionFilter(journals = false)
        assertFalse(f.matches(page("j", LocalDate(2024, 1, 1))))
        assertTrue(f.matches(page("work/a")))
    }

    @Test
    fun `date range is inclusive and keeps only journals`() {
        val f = SelectionFilter(dateFrom = LocalDate(2024, 2, 1), dateTo = LocalDate(2024, 2, 28))
        assertTrue(f.matches(page("a", LocalDate(2024, 2, 1))))
        assertTrue(f.matches(page("b", LocalDate(2024, 2, 28))))
        assertFalse(f.matches(page("c", LocalDate(2024, 1, 31))))
        assertFalse(f.matches(page("d", LocalDate(2024, 3, 1))))
        assertFalse(f.matches(page("not a journal")))
    }

    @Test
    fun `date range with journals false matches nothing`() {
        val f = SelectionFilter(journals = false, dateFrom = LocalDate(2024, 1, 1))
        assertFalse(f.matches(page("a", LocalDate(2024, 2, 1))))
        assertFalse(f.matches(page("b")))
    }

    @Test
    fun `namespace is a case-insensitive prefix`() {
        val f = SelectionFilter(namespace = "work/")
        assertTrue(f.matches(page("work/roadmap")))
        assertTrue(f.matches(page("WORK/Roadmap")))
        assertFalse(f.matches(page("network/work/x")))
        assertFalse(f.matches(page("work")))
    }

    @Test
    fun `tag matches a normalized token of tags or tag`() {
        val f = SelectionFilter(tag = "#Road")
        assertTrue(f.matches(page("a", props = mapOf("tags" to "road"))))
        assertTrue(f.matches(page("b", props = mapOf("tags" to "x, [[Road]]"))))
        assertTrue(f.matches(page("c", props = mapOf("tag" to "#road"))))
        assertFalse(f.matches(page("d", props = mapOf("tags" to "roadmap"))))
        assertFalse(f.matches(page("e", props = mapOf("other" to "road"))))
    }

    @Test
    fun `blank namespace and tag are ignored`() {
        assertTrue(SelectionFilter(namespace = "", tag = " # ").matches(page("a")))
    }

    @Test
    fun `filteredAndSorted orders by folded name`() {
        val out = listOf(page("b"), page("A"), page("c")).filteredAndSorted(SelectionFilter())
        assertEquals(listOf("A", "b", "c"), out.map { it.name })
    }

    @Test
    fun `sql args encode prefix range and escaped tag`() {
        val a = SelectionFilter(namespace = "Work/", tag = "50%_x").toSqlArgs()
        assertEquals("work/", a.nameLo)
        assertEquals("work0", a.nameHi)
        assertEquals("%50\\%\\_x%", a.tagLike)
        assertEquals("[", prefixUpperBound("@"))
    }

    @Test
    fun `prefix range admits exactly the prefixed names under folded order`() = runTest {
        checkAll(500, Arb.string(1..3, Codepoint.ascii()), Arb.string(0..6, Codepoint.ascii())) { rawPrefix, name ->
            val prefix = rawPrefix.asciiLower()
            for (candidate in listOf(name, rawPrefix + name)) {
                val folded = candidate.asciiLower()
                val inRange = folded >= prefix && folded < prefixUpperBound(prefix)
                assertEquals(folded.startsWith(prefix), inRange, "prefix=$prefix name=$folded")
            }
        }
    }
}
