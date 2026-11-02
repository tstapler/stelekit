// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkProtectedSpansTest {

    private val names = listOf("GitHub", "Dragonboat", "Go", "Treacle", "Sorcery by Terry Pratchett", "Distributed Computing")
    private val matcher = AhoCorasickMatcher(names.associateBy { it.lowercase() })

    private fun scan(text: String) = ImportService.scan(text, matcher).linkedText
    private fun insert(text: String, vararg terms: String) = ImportService.insertWikiLinks(text, terms.toList())

    // ---- never rewrite inside a URL ----

    @Test
    fun scan_doesNotRewriteInsideBareUrl_andTagsAtEnd() {
        assertEquals(
            "https://github.com/PacktPublishing/Distributed-Computing-with-Go #GitHub #Go",
            scan("https://github.com/PacktPublishing/Distributed-Computing-with-Go"),
        )
        assertEquals("https://github.com/lni/dragonboat #GitHub #Dragonboat", scan("https://github.com/lni/dragonboat"))
    }

    @Test
    fun scan_urlWithEncodedSlashesUntouched() {
        val url = "https://github.com/lifesfun101/Offensive-Security/blob/master/Walkthroughs%2FStapler%2FStapler_Walkthrough.md"
        assertEquals("$url #GitHub", scan(url))
    }

    @Test
    fun scan_linksNameOutsideUrlButNotInside() {
        assertEquals(
            "[[Treacle]] is at https://github.com/x and [[GitHub]] too",
            scan("Treacle is at https://github.com/x and GitHub too"),
        )
    }

    @Test
    fun scan_markdownLinkTargetProtectedTextStillLinkable() {
        assertEquals(
            "[[GitHub]] [repo](https://github.com/lni/dragonboat) see [[GitHub]] #Dragonboat",
            scan("GitHub [repo](https://github.com/lni/dragonboat) see GitHub"),
        )
    }

    @Test
    fun scan_angleAutolinkProtected() {
        assertEquals("<https://github.com/lni> #GitHub", scan("<https://github.com/lni>"))
    }

    @Test
    fun scan_trailingPunctuationNotPartOfUrl() {
        assertEquals(
            "see https://github.com/lni, then [[GitHub]].",
            scan("see https://github.com/lni, then GitHub."),
        )
        assertEquals("(https://github.com/lni) [[Go]] #GitHub", scan("(https://github.com/lni) Go"))
    }

    @Test
    fun scan_doesNotDoubleWrapExistingLinksTagsOrCode() {
        assertEquals("[[GitHub]]", scan("[[GitHub]]"))
        assertEquals("#GitHub and #[[Go]]", scan("#GitHub and #[[Go]]"))
        assertEquals("`GitHub` and [[GitHub]]", scan("`GitHub` and GitHub"))
        assertEquals("```\nGitHub\n```", scan("```\nGitHub\n```"))
    }

    @Test
    fun insertWikiLinks_skipsUrlsAndKeepsPlainText() {
        assertEquals(
            "https://github.com/lni/dragonboat and [[GitHub]]",
            insert("https://github.com/lni/dragonboat and GitHub", "GitHub"),
        )
        assertEquals("www.github.com #GitHub", insert("www.github.com", "GitHub"))
    }

    // ---- URL-only match -> trailing hashtag ----

    @Test
    fun urlOnlyMatch_appendsHashtag_notInsideUrl() {
        val r = ImportService.scan("https://github.com/lni/dragonboat", matcher)
        assertEquals(listOf("GitHub", "Dragonboat"), r.matchedPageNames)
        assertTrue(r.linkedText.startsWith("https://github.com/lni/dragonboat"))
    }

    @Test
    fun urlOnlyMatch_multiWordNameUsesBracketTag() {
        // A multi-word name cannot occur inside a URL (no spaces), so it is never tagged from one.
        assertEquals("https://x.com/a", insert("https://x.com/a", "Distributed Computing"))
        assertEquals("#[[C++]]", ImportService.hashtagFor("C++"))
        assertEquals("#GitHub", ImportService.hashtagFor("GitHub"))
        assertEquals("#[[Terry Pratchett]]", ImportService.hashtagFor("Terry Pratchett"))
    }

    @Test
    fun urlOnlyMatch_notTaggedWhenAlsoLinkedOutsideOrAlreadyReferenced() {
        assertEquals("[[GitHub]] https://github.com/x", scan("GitHub https://github.com/x"))
        assertEquals("https://github.com/x #github", scan("https://github.com/x #github"))
        assertEquals("https://github.com/x [[GitHub]]", scan("https://github.com/x [[GitHub]]"))
        assertEquals("https://github.com/x #[[GitHub]]", scan("https://github.com/x #[[GitHub]]"))
    }

    @Test
    fun urlOnlyMatch_tagGoesBeforeTrailingPropertyLinesAndWhitespace() {
        assertEquals(
            "https://github.com/x #GitHub\nsource:: web\n",
            scan("https://github.com/x\nsource:: web\n"),
        )
        assertEquals("https://github.com/x #GitHub  \n\n", scan("https://github.com/x  \n\n"))
    }

    @Test
    fun urlOnlyMatch_propertyOnlyTextIsLeftAlone() {
        assertEquals("source:: https://github.com/x", scan("source:: https://github.com/x"))
    }

    @Test
    fun propertyLinesAreNotLinked() {
        assertEquals("alias:: GitHub\n[[Treacle]] here", scan("alias:: GitHub\nTreacle here"))
        assertEquals("std::vector [[GitHub]]", scan("std::vector GitHub"))
    }

    // ---- case handling ----

    @Test
    fun insertWikiLinks_matchesAnyCaseAndWritesCanonicalName() {
        assertEquals("[[GitHub]] and [[GitHub]]", insert("github and GITHUB", "GitHub"))
        assertEquals("[[GitHub]]", scan("GITHUB"))
    }

    // ---- unlinked-reference helpers: only URLs are excluded ----

    @Test
    fun containsOutsideUrls_ignoresUrlOnlyMentionsButKeepsTagsAndCode() {
        val re = Regex("""\bgithub\b""", RegexOption.IGNORE_CASE)
        assertFalse(LinkProtectedSpans.containsOutsideUrls(re, "https://github.com/x"))
        assertTrue(LinkProtectedSpans.containsOutsideUrls(re, "https://github.com/x and GitHub"))
        assertTrue(LinkProtectedSpans.containsOutsideUrls(re, "tagged #GitHub"))
        assertEquals(listOf("GitHub"), LinkProtectedSpans.findOutsideUrls(matcher, "see https://github.com/x then GitHub").map { it.canonicalName })
    }

    // ---- masking ----

    @Test
    fun mask_blanksProtectedTextKeepingOffsets() {
        val text = "see https://github.com/x and `Code` here"
        val masked = LinkProtectedSpans.mask(text)
        assertEquals(text.length, masked.length)
        assertFalse(masked.contains("github"))
        assertFalse(masked.contains("Code"))
        assertTrue(masked.contains("see") && masked.contains("here"))
    }

    // ---- properties ----

    @Test
    fun property_outputNeverDiffersInsideAnyUrl() = runTest {
        val words = listOf("GitHub", "go", "dragonboat", "Treacle", "foo", "bar")
        val seps = listOf(" ", "/", ".", "-", "%2F", "?q=", "#", ",", ".", ")")
        val urlPart = Arb.list(Arb.element(words + seps), 1..8)
        val pieces = Arb.list(Arb.element(words + seps + listOf("https://", "http://", "<", ">", "(", "[x](")), 1..14)
        checkAll(pieces, urlPart) { a, u ->
            val text = a.joinToString("") + " https://" + u.joinToString("") + " " + a.joinToString("")
            for (out in listOf(scan(text), insert(text, "GitHub", "Go", "Dragonboat", "Treacle"))) {
                for (span in LinkProtectedSpans.find(text)) {
                    val original = text.substring(span.first, span.last + 1)
                    assertTrue(out.contains(original), "protected span '$original' altered: '$text' -> '$out'")
                }
            }
        }
    }

    @Test
    fun property_applyingTwiceIsIdempotent() = runTest {
        val words = listOf("GitHub", "go", "dragonboat", "Treacle", "foo", "bar", "\n", "key:: v\n")
        val seps = listOf(" ", "/", ".", "-", "?q=", ",")
        val pieces = Arb.list(Arb.element(words + seps + listOf("https://", "<", ">", "#", "[[", "]]", "`")), 1..16)
        checkAll(pieces) { a ->
            val text = a.joinToString("")
            val once = scan(text)
            assertEquals(once, scan(once), "scan not idempotent for '$text'")
            val terms = listOf("GitHub", "Go", "Dragonboat", "Treacle")
            val ins = insert(text, *terms.toTypedArray())
            assertEquals(ins, insert(ins, *terms.toTypedArray()), "insertWikiLinks not idempotent for '$text'")
        }
    }

    @Test
    fun property_noMatchesMeansIdentity() = runTest {
        checkAll(Arb.string(0..60)) { s ->
            if (matcher.findAll(s).isEmpty()) assertEquals(s, scan(s))
        }
    }
}
