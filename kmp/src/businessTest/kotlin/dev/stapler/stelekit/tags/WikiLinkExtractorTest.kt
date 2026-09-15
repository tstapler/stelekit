// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WikiLinkExtractorTest {

    @Test
    fun `returns empty set for plain text with no wiki links`() {
        val result = WikiLinkExtractor.extractPageNames("This is a plain text block")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extracts single wiki link`() {
        val result = WikiLinkExtractor.extractPageNames("See [[Kotlin]] for details")
        assertEquals(setOf("Kotlin"), result)
    }

    @Test
    fun `extracts multiple distinct wiki links`() {
        val result = WikiLinkExtractor.extractPageNames("[[Kotlin]] and [[Python]] are languages")
        assertEquals(setOf("Kotlin", "Python"), result)
    }

    @Test
    fun `deduplicates repeated wiki links`() {
        val result = WikiLinkExtractor.extractPageNames("[[Kotlin]] is great, I love [[Kotlin]]")
        assertEquals(setOf("Kotlin"), result)
    }

    @Test
    fun `extracts wiki link with spaces in page name`() {
        val result = WikiLinkExtractor.extractPageNames("Read [[Clean Code]] by Robert C. Martin")
        assertEquals(setOf("Clean Code"), result)
    }

    @Test
    fun `handles empty string`() {
        val result = WikiLinkExtractor.extractPageNames("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles adjacent valid links correctly`() {
        // Two well-formed links on the same line
        val result = WikiLinkExtractor.extractPageNames("See [[Kotlin]] and [[Python]] both")
        assertEquals(setOf("Kotlin", "Python"), result)
    }

    @Test
    fun `extracts page name from alias syntax`() {
        val result = WikiLinkExtractor.extractPageNames("See [[Kotlin|programming language]]")
        assertEquals(setOf("Kotlin"), result)
    }

    @Test
    fun `handles empty brackets`() {
        val result = WikiLinkExtractor.extractPageNames("[[]]")
        assertTrue(result.isEmpty())
    }
}
