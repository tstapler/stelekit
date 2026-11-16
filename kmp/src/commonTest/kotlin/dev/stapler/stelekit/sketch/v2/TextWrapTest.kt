package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.text.EditLayoutResult
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.StoredTextLayout
import dev.stapler.stelekit.sketch.v2.text.TextLayoutPolicy
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import dev.stapler.stelekit.sketch.v2.text.TextWrap
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.printableAscii
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Story 2.1.2, Task 2.1.2a: wrap algorithm over the fixed-advance provider (8 units per character). */
class TextWrapTest {
    private val style = TextStyle(fontFamily = 5, fontSize = 20.0)
    private val fixed = FixedAdvanceTextMetricsProvider()

    private fun wrap(text: String, maxWidth: Double?) = TextWrap.layout(text, maxWidth, style, fixed)

    @Test
    fun wrap_should_BreakAuthenticationServiceAtWidth120() {
        val layout = wrap("Authentication Service", 120.0)
        assertEquals(listOf("Authentication", "Service"), layout.lines)
        assertEquals(112.0, layout.width)
        assertEquals(40.0, layout.height)
    }

    @Test
    fun wrap_should_FitExactlyFifteenCharactersPerLine() {
        assertEquals(listOf("a".repeat(15)), wrap("a".repeat(15), 120.0).lines)
        assertEquals(listOf("a".repeat(15), "a"), wrap("a".repeat(16), 120.0).lines)
    }

    @Test
    fun wrap_should_BreakAnOverlongWordBetweenCharacters() {
        assertEquals(listOf("x".repeat(15), "x".repeat(15), "x".repeat(10)), wrap("x".repeat(40), 120.0).lines)
    }

    @Test
    fun wrap_should_HonourExplicitNewlinesAndKeepEmptyLines() {
        assertEquals(listOf("one", "", "two"), wrap("one\n\ntwo", 120.0).lines)
    }

    @Test
    fun wrap_should_NotWrapWhenNoWidthIsGiven() {
        val layout = wrap("a long line that would otherwise wrap " + "z".repeat(50), null)
        assertEquals(1, layout.lines.size)
    }

    @Test
    fun wrap_should_NeverSplitASurrogatePair() {
        val emoji = "😀" // one code point, two chars, 8 units
        val lines = wrap(emoji.repeat(20), 120.0).lines
        assertEquals(listOf(15, 5), lines.map { it.length / 2 })
        lines.forEach { assertTrue(it.length % 2 == 0) }
    }

    @Test
    fun wrap_should_BeIdenticalAcrossRepeatedCalls() {
        val text = "The quick brown fox jumps over the lazy dog"
        assertEquals(wrap(text, 120.0), wrap(text, 120.0))
    }

    @Test
    fun layout_should_TakeTheFallbackPathForGlyphsTheBundledFontLacks() {
        val asciiOnly = FixedAdvanceTextMetricsProvider(supportsGlyph = { it < 0x80 }, fallbackAdvancePerCodePoint = 20.0)
        val cjk = TextWrap.layout("データ", null, style, asciiOnly)
        assertTrue(cjk.usedFallback)
        assertEquals(60.0, cjk.width)
        assertEquals(3, asciiOnly.fallbackMeasureCalls)

        val latin = TextWrap.layout("data", null, style, asciiOnly)
        assertFalse(latin.usedFallback)
    }

    @Test
    fun render_should_UseStoredLayoutWithoutMeasuringWhileFontIsLoading() {
        val loading = FixedAdvanceTextMetricsProvider(fontStatus = FontStatus.Loading)
        val stored = StoredTextLayout(listOf("Authentication", "Service"), width = 96.0, height = 40.0)
        assertSame(stored, TextLayoutPolicy.forRender(stored))
        assertEquals(0, loading.measureCalls)
    }

    @Test
    fun edit_should_RefuseToMeasureOrPersistUntilTheFontIsLoaded() {
        for (status in listOf(FontStatus.Loading, FontStatus.Failed)) {
            val provider = FixedAdvanceTextMetricsProvider(fontStatus = status)
            assertIs<EditLayoutResult.FontNotLoaded>(TextLayoutPolicy.forEdit("text", 120.0, style, provider))
            assertEquals(0, provider.measureCalls)
        }
        val measured = TextLayoutPolicy.forEdit("Authentication Service", 120.0, style, fixed)
        assertEquals(listOf("Authentication", "Service"), assertIs<EditLayoutResult.Measured>(measured).layout.lines)
    }

    @Test
    fun wrap_should_PreserveAllNonSpaceCharactersAndRespectTheWidth() = runTest {
        checkAll(Arb.string(0..80, Codepoint.printableAscii()), Arb.int(8..200)) { text, maxWidth ->
            val layout = wrap(text, maxWidth.toDouble())
            assertTrue(layout.lines.isNotEmpty())
            layout.lines.forEach { assertTrue(fixed.advance(it, style) <= maxWidth, "'$it' wider than $maxWidth") }
            assertEquals(text.replace(" ", ""), layout.lines.joinToString("").replace(" ", ""))
        }
    }
}
