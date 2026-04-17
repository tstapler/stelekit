package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.ast.*
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec-driven tests for InlineParser emphasis and code constructs.
 *
 * Test naming convention: `<feature> - <description>`
 *
 * FAILING tests (no @Ignore) are marked with a "FAILING:" comment — they document known bugs
 * that should be fixed to make these tests pass.
 *
 * UNIMPLEMENTED features use @Ignore("P2: ...") with an empty body.
 */
class InlineEmphasisSpec {

    private fun parse(input: String) = InlineParser(input).parse()

    // -------------------------------------------------------------------------
    // Bold — **text** and __text__
    // -------------------------------------------------------------------------

    @Test
    fun `bold - double star produces BoldNode`() {
        val result = parse("**bold**")
        assertEquals(1, result.size)
        val node = result[0]
        assertTrue(node is BoldNode, "Expected BoldNode but got $node")
    }

    @Test
    fun `bold - double star content is preserved as child TextNode`() {
        val result = parse("**hello**")
        val bold = result.filterIsInstance<BoldNode>().firstOrNull()
        assertFalse(bold == null, "Expected a BoldNode")
        val text = bold!!.children.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertEquals("hello", text)
    }

    @Test
    fun `bold - double underscore produces BoldNode`() {
        val result = parse("__bold__")
        assertEquals(1, result.size)
        assertTrue(result[0] is BoldNode, "Expected BoldNode for __bold__")
    }

    @Test
    fun `bold - double underscore content is preserved`() {
        val result = parse("__world__")
        val bold = result.filterIsInstance<BoldNode>().firstOrNull()
        assertFalse(bold == null, "Expected a BoldNode for __world__")
        val text = bold!!.children.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertEquals("world", text)
    }

    @Test
    fun `bold - bold text surrounded by plain text`() {
        val result = parse("before **bold** after")
        assertTrue(result.any { it is BoldNode }, "Should contain BoldNode")
        val texts = result.filterIsInstance<TextNode>().map { it.content }
        val combined = texts.joinToString("")
        assertTrue("before" in combined, "Text before bold should be preserved")
        assertTrue("after" in combined, "Text after bold should be preserved")
    }

    @Test
    fun `bold - multiple bold spans in one line`() {
        val result = parse("**first** and **second**")
        val bolds = result.filterIsInstance<BoldNode>()
        assertEquals(2, bolds.size, "Should have exactly 2 BoldNodes")
    }

    @Test
    fun `bold - bold with whitespace inside`() {
        val result = parse("**hello world**")
        val bold = result.filterIsInstance<BoldNode>().firstOrNull()
        assertFalse(bold == null, "Expected BoldNode for **hello world**")
        val inner = bold!!.children.joinToString("") { node ->
            when (node) {
                is TextNode -> node.content
                else -> ""
            }
        }
        assertTrue("hello" in inner && "world" in inner)
    }

    // -------------------------------------------------------------------------
    // Italic — *text*
    // -------------------------------------------------------------------------

    @Test
    fun `italic - single star produces ItalicNode`() {
        val result = parse("*italic*")
        assertEquals(1, result.size)
        assertTrue(result[0] is ItalicNode, "Expected ItalicNode but got ${result[0]}")
    }

    @Test
    fun `italic - single star content is preserved`() {
        val result = parse("*slanted*")
        val italic = result.filterIsInstance<ItalicNode>().firstOrNull()
        assertFalse(italic == null, "Expected ItalicNode")
        val text = italic!!.children.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertEquals("slanted", text)
    }

    @Test
    fun `italic - italic surrounded by plain text`() {
        val result = parse("plain *italic* text")
        assertTrue(result.any { it is ItalicNode }, "Should contain ItalicNode")
        val texts = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue("plain" in texts)
        assertTrue("text" in texts)
    }

    @Test
    fun `italic - single underscore in isolation produces ItalicNode`() {
        // _word_ when isolated should be italic per CommonMark/mldoc rules
        val result = parse("_italic_")
        assertTrue(result.any { it is ItalicNode }, "Expected ItalicNode for _italic_")
    }

    @Test
    fun `italic - single underscore content preserved`() {
        val result = parse("_em_")
        val italic = result.filterIsInstance<ItalicNode>().firstOrNull()
        assertFalse(italic == null, "Expected ItalicNode for _em_")
        val text = italic!!.children.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertEquals("em", text)
    }

    // FAILING: mldoc rule — underscores mid-word should NOT be treated as emphasis.
    // The parser currently produces ItalicNode for the "world" portion of hello_world_test.
    @Test
    fun `italic - underscore in middle of word should not produce italic`() {
        val result = parse("hello_world_test")
        assertFalse(
            result.any { it is ItalicNode },
            "Underscore mid-word (hello_world_test) must not produce ItalicNode per mldoc rules"
        )
        // Full content must be preserved as plain text
        val text = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue("hello" in text && "world" in text && "test" in text,
            "All word parts must survive as plain text, got: $result")
    }

    // FAILING: variant of the mldoc mid-word underscore rule
    @Test
    fun `italic - underscore between words with no surrounding whitespace should not italicise`() {
        val result = parse("snake_case_variable")
        assertFalse(
            result.any { it is ItalicNode },
            "snake_case identifiers must not produce ItalicNode"
        )
    }

    // -------------------------------------------------------------------------
    // Strikethrough — ~~text~~
    // -------------------------------------------------------------------------

    @Test
    fun `strikethrough - double tilde produces StrikeNode`() {
        val result = parse("~~strike~~")
        assertEquals(1, result.size)
        assertTrue(result[0] is StrikeNode, "Expected StrikeNode but got ${result[0]}")
    }

    @Test
    fun `strikethrough - double tilde content preserved`() {
        val result = parse("~~deleted~~")
        val strike = result.filterIsInstance<StrikeNode>().firstOrNull()
        assertFalse(strike == null, "Expected StrikeNode")
        val text = strike!!.children.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertEquals("deleted", text)
    }

    @Test
    fun `strikethrough - strike surrounded by plain text`() {
        val result = parse("keep ~~remove~~ keep")
        assertTrue(result.any { it is StrikeNode }, "Should contain StrikeNode")
        val texts = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue("keep" in texts)
    }

    @Test
    fun `strikethrough - multiple strike spans`() {
        val result = parse("~~one~~ and ~~two~~")
        assertEquals(2, result.filterIsInstance<StrikeNode>().size)
    }

    // -------------------------------------------------------------------------
    // Inline Code — `text`
    // -------------------------------------------------------------------------

    @Test
    fun `inline code - backtick produces CodeNode`() {
        val result = parse("`code`")
        assertEquals(1, result.size)
        assertTrue(result[0] is CodeNode, "Expected CodeNode but got ${result[0]}")
    }

    @Test
    fun `inline code - content is literal string`() {
        val result = parse("`hello`")
        val code = result.filterIsInstance<CodeNode>().firstOrNull()
        assertFalse(code == null, "Expected CodeNode")
        assertEquals("hello", code!!.content)
    }

    @Test
    fun `inline code - special chars inside backticks are not parsed as markup`() {
        val result = parse("`**not bold**`")
        val code = result.filterIsInstance<CodeNode>().firstOrNull()
        assertFalse(code == null, "Expected CodeNode, markup inside backticks must not be parsed")
        assertEquals("**not bold**", code!!.content)
        assertFalse(result.any { it is BoldNode }, "BoldNode must not appear inside inline code")
    }

    @Test
    fun `inline code - code surrounded by plain text`() {
        val result = parse("run `ls -la` now")
        assertTrue(result.any { it is CodeNode })
        val code = result.filterIsInstance<CodeNode>().first()
        assertEquals("ls -la", code.content)
    }

    @Test
    fun `inline code - multiple code spans on one line`() {
        val result = parse("`foo` and `bar`")
        assertEquals(2, result.filterIsInstance<CodeNode>().size)
    }

    @Test
    fun `inline code - backtick content with tilde is not treated as strikethrough`() {
        val result = parse("`~~strike~~`")
        val code = result.filterIsInstance<CodeNode>().firstOrNull()
        assertFalse(code == null, "Expected CodeNode")
        assertEquals("~~strike~~", code!!.content)
        assertFalse(result.any { it is StrikeNode })
    }

    // -------------------------------------------------------------------------
    // Regression: single ~ is plain text (journal bug fix)
    // -------------------------------------------------------------------------

    @Test
    fun `single tilde is plain text not emphasis`() {
        // Regression: journal bug where ~8.5% caused content loss
        val result = parse("WA sales tax ~8.5-10.5% is correct")
        val text = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue("8.5-10.5%" in text, "Content after ~ must not be dropped, got: $result")
    }

    @Test
    fun `single tilde at start of string is plain text`() {
        val result = parse("~hello")
        assertFalse(result.any { it is StrikeNode }, "Single ~ must not produce StrikeNode")
        val text = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue("~" in text && "hello" in text, "Both tilde and following text must be preserved")
    }

    @Test
    fun `single tilde alone is plain text`() {
        val result = parse("~")
        assertEquals(1, result.size)
        assertTrue(result[0] is TextNode)
        assertEquals("~", (result[0] as TextNode).content)
    }

    @Test
    fun `single tilde between words is plain text`() {
        val result = parse("pH ~7 is neutral")
        assertFalse(result.any { it is StrikeNode }, "Single ~ must not produce StrikeNode")
        val text = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue("7" in text, "Text after single ~ must be preserved")
    }

    // -------------------------------------------------------------------------
    // Regression: unclosed delimiters backtrack and preserve content
    // -------------------------------------------------------------------------

    @Test
    fun `unclosed star backtracks and preserves content`() {
        // Regression: unclosed * consumed rest of line
        val result = parse("*unclosed emphasis content")
        val text = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue("unclosed emphasis content" in text,
            "Content after unclosed * must be preserved, got: $result")
        assertFalse(result.any { it is ItalicNode }, "Should not produce ItalicNode for unclosed marker")
    }

    @Test
    fun `unclosed double star backtracks and preserves content`() {
        val result = parse("**unclosed bold text")
        val text = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue("unclosed bold text" in text,
            "Content after unclosed ** must be preserved, got: $result")
        assertFalse(result.any { it is BoldNode }, "Should not produce BoldNode for unclosed **")
    }

    @Test
    fun `unclosed double tilde backtracks and preserves content`() {
        val result = parse("~~unclosed strike text")
        val text = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue("unclosed strike text" in text,
            "Content after unclosed ~~ must be preserved, got: $result")
        assertFalse(result.any { it is StrikeNode }, "Should not produce StrikeNode for unclosed ~~")
    }

    @Test
    fun `unclosed star before valid bold does not corrupt the bold`() {
        // An unclosed * followed by a properly closed **bold** must not corrupt parsing
        val result = parse("*start **bold**")
        assertTrue(result.any { it is BoldNode }, "Valid **bold** after unclosed * must still parse")
    }

    @Test
    fun `mismatched delimiter lengths do not produce emphasis node`() {
        // * opened but ** closed — mismatched, should not produce ItalicNode or BoldNode
        val result = parse("*text**")
        // The parser must not crash and text content must survive
        val text = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue(text.isNotEmpty(), "Text content must survive mismatched delimiters")
    }

    // FAILING: parseCode does not backtrack on unclosed backtick — it silently swallows content.
    // When fixed, unclosed ` should restore state and emit the backtick as plain text.
    @Test
    fun `unclosed backtick backtracks and preserves content`() {
        val result = parse("`unclosed code content")
        // The backtick itself and the following content must appear as TextNodes
        val text = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue("unclosed code content" in text,
            "Content after unclosed backtick must be preserved as text, got: $result")
        // Additionally, CodeNode should not silently consume the whole line
        val code = result.filterIsInstance<CodeNode>().firstOrNull()
        assertTrue(code == null || code.content.isEmpty(),
            "Unclosed backtick must not produce a non-empty CodeNode, got: $code")
    }

    // -------------------------------------------------------------------------
    // Unimplemented features (P2) — these are stubs for future work
    // -------------------------------------------------------------------------

    @Test
    fun `bold italic - triple star produces nested italic and bold`() {
        val result = parse("***text***")
        assertTrue(result.any { it is ItalicNode }, "Expected ItalicNode wrapping BoldNode for ***text***")
        val italic = result.filterIsInstance<ItalicNode>().first()
        assertTrue(italic.children.any { it is BoldNode }, "ItalicNode should contain BoldNode")
    }

    @Test
    fun `highlight - double equals produces HighlightNode`() {
        val result = parse("==highlight==")
        val h = result.filterIsInstance<HighlightNode>().single()
        assertEquals("highlight", (h.children[0] as TextNode).content)
    }

    @Test
    fun `subscript - tilde brace syntax produces SubscriptNode`() {
        val result = parse("~{sub}")
        assertTrue(result.any { it is SubscriptNode }, "Expected SubscriptNode for ~{sub}")
    }

    @Test
    fun `superscript - caret brace syntax produces SuperscriptNode`() {
        val result = parse("^{sup}")
        assertTrue(result.any { it is SuperscriptNode }, "Expected SuperscriptNode for ^{sup}")
    }

    @Test
    fun `hard line break - trailing double space before newline produces LineBreakNode`() {
        val result = InlineParser("text  \nmore").parse()
        assertTrue(result.any { it is HardBreakNode }, "Expected HardBreakNode for trailing double space + newline")
    }

    @Test
    fun `hard line break - backslash before newline produces LineBreakNode`() {
        val result = InlineParser("text\\\nmore").parse()
        assertTrue(result.any { it is HardBreakNode }, "Expected HardBreakNode for backslash + newline")
    }
}
