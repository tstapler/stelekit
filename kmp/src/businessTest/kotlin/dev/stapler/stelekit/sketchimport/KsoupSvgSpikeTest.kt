package dev.stapler.stelekit.sketchimport

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 4.1a: does Ksoup XML mode keep the SVG details the 4.1b reader depends on? Uses only commonMain-safe APIs. */
class KsoupSvgSpikeTest {
    private fun fixture(name: String): String =
        checkNotNull(KsoupSvgSpikeTest::class.java.classLoader.getResourceAsStream("home-sketch/closet/$name")) {
            "fixture $name missing from the test classpath"
        }.bufferedReader().use { it.readText() }

    private fun xml(text: String): Document = Ksoup.parse(text, parser = Parser.xmlParser())

    @Test
    fun `elevation fixture parses in xml mode with all paths and text labels`() {
        val doc = xml(fixture("closet-elevation.svg"))
        assertEquals(245, doc.select("path").size)
        val texts = doc.select("text")
        assertTrue(texts.isNotEmpty())
        assertTrue(texts.any { it.attr("text-anchor") == "middle" })
        assertTrue(texts.any { it.text() == "33 7/16\"" }, "label text lost: ${texts.map { it.text() }.take(5)}")
    }

    @Test
    fun `fixture transform attribute is preserved verbatim`() {
        val g = xml(fixture("closet-elevation.svg")).select("g[transform]").first()
        checkNotNull(g)
        assertTrue(g.attr("transform").startsWith("scale(11.06382978723404, -11.06382978723404)"))
    }

    @Test
    fun `viewBox and text-anchor attribute-name case survive xml mode`() {
        val doc = xml(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50"><text text-anchor="end" x="1">a</text></svg>""",
        )
        val svg = checkNotNull(doc.select("svg").first())
        assertEquals("0 0 100 50", svg.attr("viewBox"))
        assertTrue(svg.attributes().asList().any { it.key == "viewBox" }, "attribute key case folded")
        assertEquals("end", checkNotNull(doc.select("text").first()).attr("text-anchor"))
    }

    @Test
    fun `text whitespace is preserved in xml mode`() {
        val doc = xml("""<svg><text x="0">  a  b </text><text x="1">3'  4"</text></svg>""")
        val texts = doc.select("text")
        assertEquals("  a  b ", texts[0].wholeText())
        assertEquals("3'  4\"", texts[1].wholeText())
    }

    @Test
    fun `self-closing elements and mixed-case element names survive`() {
        val doc = xml("""<svg><linearGradient id="g"/><path d="M0,0 L1,1"/></svg>""")
        assertEquals("linearGradient", checkNotNull(doc.select("linearGradient").first()).tagName())
        assertEquals("M0,0 L1,1", checkNotNull(doc.select("path").first()).attr("d"))
    }
}
