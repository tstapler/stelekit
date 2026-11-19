// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.export.SvgExporter
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 5.2.1: whatever a text element holds, the SVG is well-formed XML and the decoded text is the original minus forbidden characters. */
class SvgEscapingPropertyTest {
    private val nasty = listOf("<", "&", "\"", "'", "]]>", "\u0000", "\u0001", "\u001F", "￾", "😀", "\uD83D", "\uDE00", "<![CDATA[", "&amp;", "é", "\t", "\r", "\n")

    private fun export(text: String): String {
        val u = SceneUnit.ofInt(0)
        val e = TextElement(ElementBase(ElementId("t")), u, u, SceneUnit.ofInt(10), SceneUnit.ofInt(10), text = text)
        val scene = SketchSceneV2.empty().add(e).getOrNull()!!
        return SvgExporter.export(scene, DrawListBuilder(FixedAdvanceTextMetricsProvider()), RenderStyle.Clean)
    }

    /** The documented rule: drop C0 controls except tab and carriage return, U+FFFE, U+FFFF and unpaired surrogates. */
    private fun sanitised(line: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c.isHighSurrogate() && i + 1 < line.length && line[i + 1].isLowSurrogate() -> { sb.append(c).append(line[i + 1]); i++ }
                c.isSurrogate() -> Unit
                c.code < SPACE && c != '\t' && c != '\r' -> Unit
                c.code == NOT_A_CHAR_FFFE || c.code == NOT_A_CHAR_FFFF -> Unit
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    @Test
    fun svg_should_BeWellFormed_And_RoundTripText_When_TextIsArbitrary() = runTest {
        val pieces = Arb.list(Arb.element(nasty), 0..PIECES)
        val filler = Arb.string(0..FILLER)
        checkAll(PropTestConfig(iterations = ITERATIONS), pieces, filler) { specials, plain ->
            val text = specials.joinToString("") + plain + specials.reversed().joinToString("")
            val svg = export(text)
            assertEquals(null, MiniXml.problem(svg), "not well-formed for ${text.map { it.code }}")
            assertEquals(text.split('\n').map(::sanitised), MiniXml.tspans(svg), "text changed for ${text.map { it.code }}")
        }
    }

    @Test
    fun svg_should_EscapeTheNamedCases() {
        val svg = export("a<b & \"c\" 'd' ]]> \u0000x😀")
        assertEquals(null, MiniXml.problem(svg))
        assertEquals(listOf("a<b & \"c\" 'd' ]]> x😀"), MiniXml.tspans(svg))
        assertTrue(svg.contains("a&lt;b &amp; "))
        assertTrue(svg.contains("]]&gt;"))
    }

    @Test
    fun checker_should_RejectMalformedXml() {
        assertTrue(MiniXml.problem("<a>x < y</a>") != null)
        assertTrue(MiniXml.problem("<a>&bogus;</a>") != null)
        assertTrue(MiniXml.problem("<a><b></a>") != null)
        assertTrue(MiniXml.problem("<a x=\"\u0001\"/>") != null)
        assertEquals(null, MiniXml.problem("<a x=\"1\"><b/>t&amp;&lt;</a>"))
    }

    private companion object {
        const val ITERATIONS = 300
        const val PIECES = 8
        const val FILLER = 12
        const val SPACE = 0x20
        const val NOT_A_CHAR_FFFE = 0xFFFE
        const val NOT_A_CHAR_FFFF = 0xFFFF
    }
}

/** A just-enough XML 1.0 well-formedness checker: tags nest, attributes are quoted, only the five entities, no forbidden characters. */
internal object MiniXml {
    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
    private val entityPattern = Regex("&(amp|lt|gt|quot|apos);")
    private const val SPACE = 0x20
    private const val NOT_A_CHAR_FFFE = 0xFFFE
    private const val NOT_A_CHAR_FFFF = 0xFFFF

    private fun xmlChar(c: Char) = c == '\t' || c == '\n' || c == '\r' || c.isSurrogate() || (c.code >= SPACE && c.code != NOT_A_CHAR_FFFE && c.code != NOT_A_CHAR_FFFF)

    fun problem(xml: String): String? {
        val stack = ArrayList<String>()
        var i = 0
        while (i < xml.length) {
            val c = xml[i]
            when {
                c == '<' -> {
                    val end = xml.indexOf('>', i)
                    if (end < 0) return "unterminated tag at $i"
                    val tag = xml.substring(i + 1, end)
                    if (tag.startsWith("/")) {
                        if (stack.removeLastOrNull() != tag.drop(1)) return "mismatched close $tag"
                    } else {
                        tagProblem(tag.removeSuffix("/"))?.let { return it }
                        if (!tag.endsWith("/")) stack += tag.substringBefore(' ')
                    }
                    i = end
                }
                c == '&' -> {
                    val semi = xml.indexOf(';', i)
                    if (semi < 0 || xml.substring(i + 1, semi) !in entities) return "bad entity at $i"
                    i = semi
                }
                c == '>' -> return "stray > at $i"
                !xmlChar(c) -> return "forbidden char ${c.code} at $i"
            }
            i++
        }
        return if (stack.isEmpty()) null else "unclosed ${stack.last()}"
    }

    private fun tagProblem(tag: String): String? {
        if (tag.substringBefore(' ').isEmpty()) return "empty tag name"
        var rest = tag.substringAfter(' ', "")
        while (rest.isNotBlank()) {
            rest = rest.trimStart()
            val eq = rest.indexOf("=\"")
            if (eq <= 0) return "bad attribute in <$tag>"
            val close = rest.indexOf('"', eq + 2)
            if (close < 0) return "unterminated attribute in <$tag>"
            val value = rest.substring(eq + 2, close)
            if ('<' in value || value.any { !xmlChar(it) }) return "bad attribute value in <$tag>"
            if ('&' in entityPattern.replace(value, "")) return "bad entity in attribute of <$tag>"
            rest = rest.substring(close + 1)
        }
        return null
    }

    /** Decoded text of every `<tspan>`, in document order. */
    fun tspans(xml: String): List<String> = Regex("<tspan [^>]*>(.*?)</tspan>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        .map { m -> entityPattern.replace(m.groupValues[1]) { entities.getValue(it.groupValues[1]) } }
        .toList()
}
