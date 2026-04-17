package dev.stapler.stelekit.parsing

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
    // Markdown links  [text](url)  — KNOWN BUG P0 (intentionally failing)
    // Currently parseLink() returns TextNode("[") for non-wiki bracket sequences.
    // -------------------------------------------------------------------------

    @Test
    fun `markdown-link - basic link does not reduce to bare bracket TextNode (BUG P0)`() {
        // Bug P0: parseLink returns TextNode("[") for non-wiki links.
        // Expected: a link node carrying both label "Visit Google" and url.
        val result = parse("[Visit Google](https://google.com)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "[",
            "Bug P0: parser must not reduce a full markdown link to a bare '[' TextNode"
        )
    }

    @Test
    fun `markdown-link - label and url are preserved in link node (BUG P0)`() {
        // When implemented, expect a node (UrlLinkNode or similar) that carries
        // the label "Click here" and the url "https://example.com".
        val result = parse("[Click here](https://example.com)")
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size, "Bug P0: no UrlLinkNode produced for markdown link")
        assertEquals("https://example.com", urlNodes[0].url)
        val labelText = urlNodes[0].text.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertEquals("Click here", labelText)
    }

    @Test
    fun `markdown-link - https url scheme (BUG P0)`() {
        val result = parse("[Docs](https://docs.example.com/page)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "[",
            "Bug P0: https link collapsed to bare '['"
        )
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size, "Bug P0: no UrlLinkNode for https link")
    }

    @Test
    fun `markdown-link - http url scheme (BUG P0)`() {
        val result = parse("[Legacy](http://old.example.com)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "[",
            "Bug P0: http link collapsed to bare '['"
        )
    }

    @Test
    fun `markdown-link - unicode label (BUG P0)`() {
        val result = parse("[日本語ページ](https://ja.example.com)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "[",
            "Bug P0: unicode-label link collapsed to bare '['"
        )
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size, "Bug P0: no UrlLinkNode for unicode-label link")
    }

    @Test
    fun `markdown-link - inline code in label (BUG P0)`() {
        // Label contains backtick-quoted code: [`fn()`](https://api.example.com)
        val result = parse("[`fn()`](https://api.example.com)")
        assertFalse(
            result.size == 1 && result[0] is TextNode && (result[0] as TextNode).content == "[",
            "Bug P0: code-label link collapsed to bare '['"
        )
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size, "Bug P0: no UrlLinkNode for code-label link")
        // When implemented, label should contain a CodeNode child
        assertTrue(
            urlNodes.firstOrNull()?.text?.any { it is CodeNode } == true,
            "Bug P0: label CodeNode not preserved inside UrlLinkNode"
        )
    }

    @Test
    fun `markdown-link - embedded in surrounding text (BUG P0)`() {
        val result = parse("Read [the guide](https://example.com/guide) for more.")
        assertFalse(
            result.all { it is TextNode },
            "Bug P0: entire string reduced to TextNodes — markdown link not parsed"
        )
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(1, urlNodes.size, "Bug P0: no UrlLinkNode when link is mid-sentence")
    }

    @Test
    fun `markdown-link - two links on one line (BUG P0)`() {
        val result = parse("[A](https://a.com) and [B](https://b.com)")
        val urlNodes = result.filterIsInstance<UrlLinkNode>()
        assertEquals(2, urlNodes.size, "Bug P0: expected two UrlLinkNodes for two markdown links")
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
