package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parser.MarkdownParser
import dev.stapler.stelekit.parsing.ast.*
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specification-driven tests for the InlineParser covering links and references.
 *
 * Test naming convention: `<feature> - <scenario>`
 *
 * Status markers in test names / bodies:
 *   (no marker)  = fully implemented, assertions must pass
 *   (BUG)        = known regression, test is intentionally failing — no @Ignore
 *   @Ignore      = feature not yet implemented (P-level in reason string)
 */
class InlineLinksSpec {

    private fun parse(input: String): List<InlineNode> = InlineParser(input).parse()

    // -------------------------------------------------------------------------
    // Wiki-links  [[page]]  — IMPLEMENTED
    // -------------------------------------------------------------------------

    @Test
    fun `wiki-link - basic page name produces WikiLinkNode`() {
        val result = parse("[[Page Name]]")
        val links = result.filterIsInstance<WikiLinkNode>()
        assertEquals(1, links.size, "Expected exactly one WikiLinkNode")
        assertEquals("Page Name", links[0].target)
        assertEquals(null, links[0].alias)
    }

    @Test
    fun `wiki-link - single word page name`() {
        val result = parse("[[Logseq]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals("Logseq", link.target)
        assertEquals(null, link.alias)
    }

    @Test
    fun `wiki-link - colon in page name`() {
        val result = parse("[[page:name]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals("page:name", link.target)
    }

    @Test
    fun `wiki-link - namespace slash in page name`() {
        val result = parse("[[parent/child]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals("parent/child", link.target)
    }

    @Test
    fun `wiki-link - deep namespace path`() {
        val result = parse("[[a/b/c/d]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals("a/b/c/d", link.target)
    }

    @Test
    fun `wiki-link - adjacent to leading text`() {
        val result = parse("See [[Page]] for details")
        assertTrue(result.any { it is WikiLinkNode }, "Should contain a WikiLinkNode")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals("Page", link.target)
    }

    @Test
    fun `wiki-link - surrounded by text produces mixed node list`() {
        val result = parse("Go to [[Home]] now")
        val links = result.filterIsInstance<WikiLinkNode>()
        assertEquals(1, links.size)
        assertEquals("Home", links[0].target)
        // There should also be TextNodes for the surrounding text
        val texts = result.filterIsInstance<TextNode>()
        assertTrue(texts.isNotEmpty(), "Expected TextNodes alongside WikiLinkNode")
    }

    @Test
    fun `wiki-link - multiple wiki-links on one line`() {
        val result = parse("[[Alpha]] and [[Beta]]")
        val links = result.filterIsInstance<WikiLinkNode>()
        assertEquals(2, links.size, "Expected two WikiLinkNodes")
        assertEquals("Alpha", links[0].target)
        assertEquals("Beta", links[1].target)
    }

    @Test
    fun `wiki-link - three wiki-links on one line`() {
        val result = parse("[[A]] [[B]] [[C]]")
        val links = result.filterIsInstance<WikiLinkNode>()
        assertEquals(3, links.size)
        assertEquals(listOf("A", "B", "C"), links.map { it.target })
    }

    @Test
    fun `wiki-link - page name with numbers`() {
        val result = parse("[[2024-01-15]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals("2024-01-15", link.target)
    }

    @Test
    fun `wiki-link - page name with spaces and colon (namespace colon)`() {
        val result = parse("[[Category:Articles]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals("Category:Articles", link.target)
    }

    @Test
    fun `wiki-link - only node in input`() {
        val result = parse("[[Solo]]")
        assertEquals(1, result.filterIsInstance<WikiLinkNode>().size)
        // No stray TextNodes expected for a clean single-link input
        val nonLinks = result.filterNot { it is WikiLinkNode }
        assertTrue(
            nonLinks.all { it is TextNode && (it as TextNode).content.isBlank() },
            "Only whitespace TextNodes expected alongside a bare wiki-link"
        )
    }

    // -------------------------------------------------------------------------
    // Wiki-link alias  [[page|alias]]  — KNOWN BUG (intentionally failing)
    // -------------------------------------------------------------------------

    @Test
    fun `wiki-link alias - alias is parsed correctly (BUG)`() {
        // Bug: the pipe and alias text are captured as part of target instead of
        // being split into WikiLinkNode.alias.  Both assertions below currently fail.
        val result = parse("[[Logseq|the app]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals(
            "Logseq", link.target,
            "Bug: target currently contains 'Logseq|the app' instead of 'Logseq'"
        )
        assertEquals(
            "the app", link.alias,
            "Bug: alias is currently null instead of 'the app'"
        )
    }

    @Test
    fun `wiki-link alias - single-word alias (BUG)`() {
        val result = parse("[[HomePage|Home]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals("HomePage", link.target, "Bug: target swallows alias segment")
        assertEquals("Home", link.alias, "Bug: alias is null instead of 'Home'")
    }

    @Test
    fun `wiki-link alias - namespace page with alias (BUG)`() {
        val result = parse("[[parent/child|Child Page]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals("parent/child", link.target, "Bug: namespace target corrupted by alias")
        assertEquals("Child Page", link.alias, "Bug: alias not extracted")
    }

    @Test
    fun `wiki-link alias - alias does not bleed into surrounding text (BUG)`() {
        // Even while the alias bug exists, the surrounding text should not be corrupted.
        val result = parse("See [[Logseq|the app]] here")
        val links = result.filterIsInstance<WikiLinkNode>()
        assertEquals(1, links.size, "Still exactly one WikiLinkNode")
        // When the bug is fixed these will also start passing:
        assertEquals("Logseq", links[0].target, "Bug: target includes alias segment")
        assertEquals("the app", links[0].alias, "Bug: alias is null")
    }

    // -------------------------------------------------------------------------
    // Block references  ((uuid))  — IMPLEMENTED
    // -------------------------------------------------------------------------

    @Test
    fun `block-ref - basic short id produces BlockRefNode`() {
        val result = parse("((abc-123))")
        val refs = result.filterIsInstance<BlockRefNode>()
        assertEquals(1, refs.size)
        assertEquals("abc-123", refs[0].blockUuid)
    }

    @Test
    fun `block-ref - full UUID with hyphens`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val result = parse("(($uuid))")
        val ref = result.filterIsInstance<BlockRefNode>().single()
        assertEquals(uuid, ref.blockUuid)
    }

    @Test
    fun `block-ref - embedded in text`() {
        val result = parse("See ((abc-123)) for context")
        val refs = result.filterIsInstance<BlockRefNode>()
        assertEquals(1, refs.size)
        assertEquals("abc-123", refs[0].blockUuid)
        assertTrue(result.filterIsInstance<TextNode>().isNotEmpty())
    }

    @Test
    fun `block-ref - multiple block refs on one line`() {
        val result = parse("((ref-1)) and ((ref-2))")
        val refs = result.filterIsInstance<BlockRefNode>()
        assertEquals(2, refs.size)
        assertEquals("ref-1", refs[0].blockUuid)
        assertEquals("ref-2", refs[1].blockUuid)
    }

    @Test
    fun `block-ref - alphanumeric id without hyphens`() {
        val result = parse("((deadbeef))")
        val ref = result.filterIsInstance<BlockRefNode>().single()
        assertEquals("deadbeef", ref.blockUuid)
    }

    // -------------------------------------------------------------------------
    // Markdown links  [text](url)  — IMPLEMENTED
    // -------------------------------------------------------------------------

    @Test
    fun `markdown-link - basic link does not reduce to bare bracket TextNode`() {
        val result = parse("[Visit Google](https://google.com)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "[",
            "parser must not reduce a full markdown link to a bare '[' TextNode"
        )
    }

    @Test
    fun `markdown-link - label and url are preserved in link node`() {
        val result = parse("[Click here](https://example.com)")
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size, "no UrlLinkNode produced for markdown link")
        assertEquals("https://example.com", urlNodes[0].url)
        val labelText = urlNodes[0].text.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertEquals("Click here", labelText)
    }

    @Test
    fun `markdown-link - https url scheme`() {
        val result = parse("[Docs](https://docs.example.com/page)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "[",
            "https link collapsed to bare '['"
        )
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size, "no UrlLinkNode for https link")
    }

    @Test
    fun `markdown-link - http url scheme`() {
        val result = parse("[Legacy](http://old.example.com)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "[",
            "http link collapsed to bare '['"
        )
        assertEquals("http://old.example.com", result.filterIsInstance<UrlLinkNode>().single().url)
    }

    @Test
    fun `markdown-link - unicode label`() {
        val result = parse("[日本語ページ](https://ja.example.com)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "[",
            "unicode-label link collapsed to bare '['"
        )
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size, "no UrlLinkNode for unicode-label link")
    }

    @Test
    fun `markdown-link - inline code in label`() {
        // Label contains backtick-quoted code: [`fn()`](https://api.example.com)
        val result = parse("[`fn()`](https://api.example.com)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "[",
            "code-label link collapsed to bare '['"
        )
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size, "no UrlLinkNode for code-label link")
        assertTrue(
            urlNodes.firstOrNull()?.text?.any { it is CodeNode } == true,
            "label CodeNode not preserved inside UrlLinkNode"
        )
    }

    @Test
    fun `markdown-link - embedded in surrounding text`() {
        val result = parse("Read [the guide](https://example.com/guide) for more.")
        assertFalse(
            result.all { it is TextNode },
            "entire string reduced to TextNodes — markdown link not parsed"
        )
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size, "no UrlLinkNode when link is mid-sentence")
    }

    @Test
    fun `markdown-link - two links on one line`() {
        val result = parse("[A](https://a.com) and [B](https://b.com)")
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(2, urlNodes.size, "expected two UrlLinkNodes for two markdown links")
    }

    // -------------------------------------------------------------------------
    // Parentheses around and inside a link destination
    // -------------------------------------------------------------------------

    private val kichler = "https://www.kichler.com/customer-care/newsroom/landscape-lighting-just-got-reinvented"

    private fun link(label: String, url: String) = UrlLinkNode(url = url, text = listOf(TextNode(label)))

    @Test
    fun `markdown-link - bare Kichler link`() {
        assertEquals(listOf<InlineNode>(link("Kichler", kichler)), parse("[Kichler]($kichler)"))
    }

    @Test
    fun `markdown-link - wrapped in parentheses`() {
        // The two closing parens lex as one BLOCK_REF_CLOSE token.
        assertEquals(
            listOf(TextNode("("), link("Kichler", kichler), TextNode(")")),
            parse("([Kichler]($kichler))")
        )
    }

    @Test
    fun `markdown-link - balanced parentheses stay in the url`() {
        for (url in listOf("https://en.wikipedia.org/wiki/Foo_(bar)", "https://x.org/Foo_(bar)_baz", "https://x.org/((a))")) {
            assertEquals(listOf<InlineNode>(link("W", url)), parse("[W]($url)"), url)
            assertEquals(listOf(TextNode("("), link("W", url), TextNode(")")), parse("([W]($url))"), "wrapped $url")
        }
    }

    @Test
    fun `markdown-link - unbalanced open paren in url still closes at the first paren`() {
        val result = parse("[a](https://x.org/(foo) tail")
        assertEquals(link("a", "https://x.org/(foo"), result.first())
    }

    @Test
    fun `markdown-link - unclosed destination stays plain text`() {
        assertTrue(parse("[a](https://x.org\n)").none { it is UrlLinkNode })
    }

    @Test
    fun `markdown-link - stray closing paren later in the line does not swallow a second link`() {
        val result = parse("[a](x/(foo) then [b](y) :)")
        assertEquals(listOf("x/(foo", "y"), result.filterIsInstance<UrlLinkNode>().map { it.url })
    }

    @Test
    fun `markdown-link - quoted title with parentheses stays inside the destination`() {
        val link = parse("[a](u \"t (x y)\")").single() as UrlLinkNode
        assertEquals("u \"t (x y)\"", link.url)
    }

    @Test
    fun `markdown-link - block ref destination is still not a link`() {
        val result = parse("[a](((uuid-1)))")
        assertTrue(result.none { it is UrlLinkNode })
        assertEquals(1, result.filterIsInstance<BlockRefNode>().size)
    }

    @Test
    fun `markdown-link - source ranges split the double closing paren`() {
        val ranges = InlineParser("([a](u))").parseWithRanges().map { it.second }
        assertEquals(listOf(0 until 1, 1 until 7, 7 until 8), ranges)
    }

    @Test
    fun `markdown-link - wrapped link survives the load-time content rebuild`() {
        val source = "([Kichler]($kichler)) and ([W](https://x.org/Foo_(bar)_baz))"
        val block = MarkdownParser().parsePage("- $source").blocks.single()
        assertEquals(source, block.content)
    }

    @Test
    fun `image - wrapped in parentheses`() {
        assertEquals(
            listOf(TextNode("("), ImageNode(alt = "a", url = "https://x.org/i.png"), TextNode(")")),
            parse("(![a](https://x.org/i.png))")
        )
    }

    @Test
    fun `markdown-link - title is kept whole after a url with parentheses`() {
        for (dest in listOf("x_(b) \"t\"", "x_(b) \"My title\"", "x \"My (nice) title\"", "x \"a ) b\"")) {
            assertEquals(listOf<InlineNode>(link("a", dest)), parse("[a]($dest)"), dest)
        }
    }

    @Test
    fun `markdown-link - unbalanced paren at end of line closes at the first paren`() {
        assertEquals(listOf<InlineNode>(link("a", "x/(foo")), parse("[a](x/(foo)"))
        assertEquals("x((1", (parse("[a](x((1)(2) t").first() as UrlLinkNode).url)
    }

    @Test
    fun `markdown-link - unbalanced paren does not swallow an adjacent link`() {
        val urls = parse("[a](x/(foo),see[b](y):)").filterIsInstance<UrlLinkNode>().map { it.url }
        assertEquals(listOf("x/(foo", "y"), urls)
    }

    @Test
    fun `markdown-link - text right after a link is not read as a bullet`() {
        assertTrue(TextNode("-") in parse("[a](u)- x"))
    }

    @Test
    fun `image - unclosed destination is not an image`() {
        assertTrue(parse("![a](x").none { it is ImageNode })
    }

    @Test
    fun `markdown-link - many nested open brackets parse without exponential backtracking`() {
        // Each of these takes minutes or more if failed labels are retried.
        for (tail in listOf("](u))", "](f())", "](u)", "`](u))`")) {
            parse("[a ".repeat(60) + tail)
        }
    }

    @Test
    fun `markdown-link - label equal to url is not rewritten as an autolink on load`() {
        for (source in listOf("(see [README.md](README.md))", "see (the [spec](spec)).", "[a](a)", "see <https://x.org>")) {
            assertEquals(source, MarkdownParser().parsePage("- $source").blocks.single().content)
        }
    }

    @Test
    fun `markdown-link - wrapped page alias keeps its page reference`() {
        val block = MarkdownParser().parsePage("- ([my label]([[Page]]))").blocks.single()
        assertEquals("([my label]([[Page]]))", block.content)
        assertEquals(listOf("Page"), block.references)
    }

    // -------------------------------------------------------------------------
    // Auto-links  <url>  — NOT IMPLEMENTED (P2)
    // -------------------------------------------------------------------------

    @Test
    fun `autolink - https URL in angle brackets produces link node`() {
        val result = parse("<https://example.com>")
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size)
        assertEquals("https://example.com", urlNodes[0].url)
    }

    @Test
    fun `autolink - email address in angle brackets produces link node`() {
        val result = parse("<user@example.com>")
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size)
        assertEquals("user@example.com", urlNodes[0].url)
    }

    @Test
    fun `autolink - embedded in surrounding text`() {
        val result = parse("Contact us at <hello@example.com> today")
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size)
    }

    // -------------------------------------------------------------------------
    // Images  ![alt](url)  — IMPLEMENTED
    // -------------------------------------------------------------------------

    @Test
    fun `image - basic image with alt text`() {
        val result = parse("![Alt text](https://example.com/img.png)")
        assertEquals(1, result.filterIsInstance<ImageNode>().size)
        val img = result.filterIsInstance<ImageNode>().single()
        assertEquals("Alt text", img.alt)
        assertEquals("https://example.com/img.png", img.url)
    }

    @Test
    fun `image - empty alt text`() {
        val result = parse("![](https://example.com/img.png)")
        assertEquals(1, result.filterIsInstance<ImageNode>().size)
        val img = result.filterIsInstance<ImageNode>().single()
        assertEquals("", img.alt)
    }

    @Test
    fun `image - embedded in surrounding text`() {
        val result = parse("Here is an image: ![Logo](https://example.com/logo.png) enjoy")
        val imgs = result.filterIsInstance<ImageNode>()
        assertEquals(1, imgs.size)
    }

    @Test
    fun `image - does not collapse to bare exclamation TextNode`() {
        val result = parse("![Alt](https://example.com/img.png)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "!",
            "Image syntax must not reduce to a bare '!' TextNode"
        )
    }
}
