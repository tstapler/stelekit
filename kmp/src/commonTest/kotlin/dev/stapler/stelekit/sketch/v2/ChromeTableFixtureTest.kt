package dev.stapler.stelekit.sketch.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 3.6.4d: the generated chrome-table fixture parses, hashes to the value the generator wrote, and keeps
 * `$` and non-ASCII text intact. Asserts structure only; layout figures and strings come from the parsed fixture.
 */
class ChromeTableFixtureTest {
    private val table = ChromeTableParser.parseFixture()

    @Test
    fun fixture_should_HashToTheValueTheGeneratorWrote() {
        assertEquals(ChromeTableFixture.CONTENT_HASH, ChromeTableParser.contentHash(ChromeTableFixture.TABLE))
    }

    @Test
    fun contentHash_should_MatchKnownFnv1aVectors() {
        assertEquals("cbf29ce484222325", ChromeTableParser.contentHash(""))
        assertEquals("af63dc4c8601ec8c", ChromeTableParser.contentHash("a"))
        assertEquals("85944171f73967e8", ChromeTableParser.contentHash("foobar"))
    }

    @Test
    fun banners_should_BeRankedConsecutivelyFromOneWithUniqueIds() {
        val banners = table.banners
        assertTrue(banners.isNotEmpty())
        assertEquals((1..banners.size).toList(), banners.map { it.rank })
        assertEquals(banners.size, banners.map { it.id }.toSet().size)
        banners.forEach { assertTrue(it.summary.isNotBlank(), "${it.id} has no summary") }
    }

    @Test
    fun toasts_should_HaveUniqueIdsAndSummaries() {
        val toasts = table.toasts
        assertTrue(toasts.isNotEmpty())
        assertEquals(toasts.size, toasts.map { it.id }.toSet().size)
        toasts.forEach { assertTrue(it.summary.isNotBlank(), "${it.id} has no summary") }
    }

    @Test
    fun toastPlaceholders_should_SurviveParsing() {
        val named = table.toasts.filter { "{name}" in it.summary }
        assertTrue(named.isNotEmpty(), "no toast keeps its {name} placeholder")
    }

    @Test
    fun tools_should_BeUniqueAndEveryGroupBeDeclared() {
        val tools = table.tools
        assertEquals(tools.size, tools.map { it.id }.toSet().size)
        val groups = table.raw("groups").map { it[0] }.toSet()
        tools.mapNotNull { it.group }.forEach { assertTrue(it in groups, "tool group $it is not declared") }
    }

    @Test
    fun windows_should_BeInsideTheMinimumWindowWhenGuaranteed() {
        val minW = table.param("minWindowWidth")
        val minH = table.param("minWindowHeight")
        table.windows.filter { it.guaranteed }.forEach {
            assertTrue(it.widthDp >= minW && it.heightDp >= minH, "${it.name} ${it.widthDp}x${it.heightDp} is below the minimum")
        }
        assertNotNull(table.windows.firstOrNull { !it.guaranteed }, "the degraded window rows are missing")
    }

    @Test
    fun parser_should_KeepDollarQuotesAndNonAsciiText() {
        val dollar = '$'
        val text = "[toast]\nPrice | Cost ${dollar}5 \"\"\" Zurück 日本語 | Short | Undo | Details; Dismiss | Undo it\n"
        val toast = ChromeTableParser.parse(text).toasts.single()
        assertEquals("Cost ${dollar}5 \"\"\" Zurück 日本語", toast.summary)
        assertEquals(listOf("Details", "Dismiss"), toast.moreItems)
    }

    @Test
    fun parser_should_RejectMalformedRows() {
        assertFailsWith<IllegalArgumentException> { ChromeTableParser.parse("gutter = 16") }
        assertFailsWith<IllegalArgumentException> { ChromeTableParser.parse("[params]\ngutter = sixteen") }
        assertFailsWith<IllegalArgumentException> { ChromeTableParser.parse("[tools]\nselect | - | extra") }
        assertFailsWith<IllegalArgumentException> { ChromeTableParser.parse("[params]\na = 1\na = 2") }
    }
}
