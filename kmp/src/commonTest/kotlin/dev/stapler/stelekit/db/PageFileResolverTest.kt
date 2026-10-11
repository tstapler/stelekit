package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.util.FileUtils
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageFileResolverTest {
    private val graph = "/g"

    private fun pathOf(r: Either<PageFileError, String>): String = (r as Either.Right).value

    private val dates = arbitrary {
        LocalDate(Arb.int(1000..9999).bind(), Arb.int(1..12).bind(), Arb.int(1..28).bind())
    }

    // Pre-extraction body of GraphWriter.getPageFilePath, kept verbatim as the oracle.
    private fun legacy(name: String, isJournal: Boolean, graphPath: String, encrypted: Boolean): String {
        val basePath = if (graphPath.endsWith("/")) graphPath else "$graphPath/"
        val folder = if (isJournal) "journals" else "pages"
        val extension = if (encrypted) ".md.stek" else ".md"
        return "${basePath}$folder/${FileUtils.sanitizeFileName(name)}$extension"
    }

    @Test
    fun namespaceAndJournalNames() {
        assertEquals("/g/pages/a%2Fb.md", pathOf(PageFileResolver.resolve("a/b", false, "/g")))
        assertEquals("/g/pages/a%2Fb.md", pathOf(PageFileResolver.resolve("a/b", false, "/g/")))
        assertEquals("/g/journals/Oct 7th, 2026.md", pathOf(PageFileResolver.resolve("Oct 7th, 2026", true, "/g")))
        assertEquals("/g/pages/x.md.stek", pathOf(PageFileResolver.resolve("x", false, "/g", encrypted = true)))
    }

    @Test
    fun matchesLegacyGraphWriterMapping() = runTest {
        checkAll(Arb.string(0..40), Arb.int(0..3)) { raw, flags ->
            val name = raw.replace("\u0000", "")
            val isJournal = flags and 1 == 1
            val enc = flags and 2 == 2
            val expected = legacy(name, isJournal, graph, enc)
            assertEquals(expected, PageFileResolver.pagePath(name, isJournal, graph, enc))
            assertEquals(expected, pathOf(PageFileResolver.resolve(name, isJournal, graph, enc)))
        }
    }

    @Test
    fun hostileNamesNeverEscapeTheFolder() {
        val names = listOf(
            "../../etc/passwd", "a/../../b", "..%2f..", "..", ".", "....", "/etc/passwd",
            "C:\\Windows\\x", "..\\..\\x", "x/../y", " ", "",
        )
        for (journal in listOf(false, true)) {
            val folder = "/g/" + if (journal) "journals" else "pages"
            for (n in names) {
                val r = PageFileResolver.resolve(n, journal, graph)
                if (r is Either.Right) assertTrue(PageFileResolver.isWithin(folder, r.value), "$n -> ${r.value}")
            }
        }
    }

    @Test
    fun nulNameIsInvalid() {
        val err = (PageFileResolver.resolve("a\u0000b", false, graph) as Either.Left).value
        assertIs<PageFileError.InvalidPageName>(err)
    }

    @Test
    fun arbitraryNamesAreContainedOrRefused() = runTest {
        checkAll(Arb.string(0..60), Arb.string(0..6)) { a, b ->
            for (n in listOf(a, "../$a", "$a/../$b", "$a\\..\\..\\$b", "..$a")) {
                when (val r = PageFileResolver.resolve(n, false, graph)) {
                    is Either.Right -> assertTrue(PageFileResolver.isWithin("/g/pages", r.value), "$n -> ${r.value}")
                    is Either.Left -> assertTrue(n.contains('\u0000'), "unexpected refusal for $n")
                }
            }
        }
    }

    @Test
    fun isWithinLexicalCases() {
        assertTrue(PageFileResolver.isWithin("/g/pages", "/g/pages/a.md"))
        assertTrue(PageFileResolver.isWithin("/g/pages", "/g/pages/sub/../a.md"))
        assertTrue(PageFileResolver.isWithin("/g/pages/", "/g//pages/./a.md"))
        assertTrue(!PageFileResolver.isWithin("/g/pages", "/g/pages"))
        assertTrue(!PageFileResolver.isWithin("/g/pages", "/g/pages/../x.md"))
        assertTrue(!PageFileResolver.isWithin("/g/pages", "/g/pagesx/a.md"))
        assertTrue(!PageFileResolver.isWithin("/g/pages", "/etc/passwd"))
        assertTrue(!PageFileResolver.isWithin("/g/pages", "pages/a.md"))
        assertTrue(!PageFileResolver.isWithin("/g/pages", "/g/pages/a\u0000.md"))
        assertTrue(!PageFileResolver.isWithin("/g/pages", "/../g/pages/a.md"))
    }

    @Test
    fun journalStemRoundTripsAndMatchesEnsureTodayJournalName() = runTest {
        checkAll(dates) { d ->
            val path = pathOf(PageFileResolver.resolveJournal(d, graph, emptyList()))
            val stem = path.removePrefix("/g/journals/").removeSuffix(".md")
            assertEquals(d, JournalUtils.parseJournalDate(stem))
            // GraphLoader names the page decodeFileName(stem); ensureTodayJournal names it date.toString() with '_'.
            assertEquals(d.toString().replace('-', '_'), FileUtils.decodeFileName(stem))
        }
    }

    @Test
    fun existingStemIsReusedForEitherSeparator() = runTest {
        checkAll(dates) { d ->
            val hyphen = d.toString()
            val otherDay = LocalDate(d.year, d.month.ordinal + 1, if (d.day == 1) 2 else 1).toString()
            assertEquals("/g/journals/$hyphen.md", pathOf(PageFileResolver.resolveJournal(d, graph, listOf(otherDay, hyphen))))
            val under = hyphen.replace('-', '_')
            assertEquals("/g/journals/$under.md", pathOf(PageFileResolver.resolveJournal(d, graph, listOf(hyphen, under))))
        }
    }

    @Test
    fun nonDefaultJournalFormatIsRefused() {
        val d = LocalDate(2026, 10, 7)
        val cfg = """{:preferred-format :markdown
                      :journal/file-name-format "yyyy-MM-dd"}"""
        val r = PageFileResolver.resolveJournal(d, graph, emptyList(), configEdn = cfg)
        assertEquals(PageFileError.JournalFormatUnsupported("yyyy-MM-dd"), (r as Either.Left).value)
        assertEquals("yyyy-MM-dd", PageFileResolver.parseJournalFileNameFormat(cfg))
    }

    @Test
    fun defaultAbsentNilAndCommentedFormatsAreAccepted() {
        val d = LocalDate(2026, 10, 7)
        val expected = "/g/journals/2026_10_07.md"
        for (cfg in listOf(
            null, "{}", "{:journal/file-name-format nil}",
            "{:journal/file-name-format \"yyyy_MM_dd\"}",
            ";; :journal/file-name-format \"yyyy-MM-dd\"\n{}",
            "{:x \"a;b\" ; :journal/file-name-format \"yyyy-MM-dd\"\n}",
        )) {
            assertEquals(expected, pathOf(PageFileResolver.resolveJournal(d, graph, emptyList(), configEdn = cfg)), "cfg=$cfg")
        }
        assertNull(PageFileResolver.parseJournalFileNameFormat("{:journal/file-name-format nil}"))
    }
}
