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
import kotlin.test.assertTrue

class LinkProtectedSpansTest {

    private val names = listOf("GitHub", "Dragonboat", "Go", "Treacle", "Sorcery by Terry Pratchett")
    private val matcher = AhoCorasickMatcher(names.associateBy { it.lowercase() })

    private fun scan(text: String) = ImportService.scan(text, matcher).linkedText
    private fun insert(text: String, vararg terms: String) = ImportService.insertWikiLinks(text, terms.toList())

    @Test
    fun scan_doesNotTagInsideBareUrl() {
        val url = "https://github.com/PacktPublishing/Distributed-Computing-with-Go"
        assertEquals(url, scan(url))
        val url2 = "https://github.com/lni/dragonboat"
        assertEquals(url2, scan(url2))
    }

    @Test
    fun scan_urlWithEncodedSlashesUntouched() {
        val url = "https://github.com/lifesfun101/Offensive-Security/blob/master/Walkthroughs%2FStapler%2FStapler_Walkthrough.md"
        assertEquals(url, scan(url))
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
            "[[GitHub]] [repo](https://github.com/lni/dragonboat) see [[GitHub]]",
            scan("GitHub [repo](https://github.com/lni/dragonboat) see GitHub"),
        )
    }

    @Test
    fun scan_angleAutolinkProtected() {
        assertEquals("<https://github.com/lni>", scan("<https://github.com/lni>"))
    }

    @Test
    fun scan_trailingPunctuationNotPartOfUrl() {
        assertEquals(
            "see https://github.com/lni, then [[GitHub]].",
            scan("see https://github.com/lni, then GitHub."),
        )
        assertEquals("(https://github.com/lni) [[Go]]", scan("(https://github.com/lni) Go"))
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
        assertEquals("www.github.com", insert("www.github.com", "GitHub"))
    }

    @Test
    fun matchedNamesExcludeUrlOnlyMatches() {
        val r = ImportService.scan("https://github.com/lni/dragonboat", matcher)
        assertTrue(r.matchedPageNames.isEmpty())
    }

    @Test
    fun property_outputNeverDiffersInsideAnyUrl() = runTest {
        val words = listOf("GitHub", "go", "dragonboat", "Treacle", "foo", "bar")
        val seps = listOf(" ", "/", ".", "-", "%2F", "?q=", "#", ",", ".", ")")
        val urlPart = Arb.list(Arb.element(words + seps), 1..8)
        checkAll(Arb.list(Arb.element(words + seps + listOf("https://", "http://", "<", ">", "(", "[x](")), 1..14), urlPart) { a, u ->
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
    fun property_noMatchesMeansIdentity() = runTest {
        checkAll(Arb.string(0..60)) { s ->
            if (matcher.findAll(s).isEmpty()) assertEquals(s, scan(s))
        }
    }
}
