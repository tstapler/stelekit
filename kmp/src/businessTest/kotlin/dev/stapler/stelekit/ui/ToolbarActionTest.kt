package dev.stapler.stelekit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the line-prefix toggle logic used by applyFormatAction for
 * FormatAction.QUOTE, FormatAction.NUMBERED_LIST, and FormatAction.HEADING.
 *
 * All three are "line-prefix actions": suffix == "" and prefix is non-empty.
 *
 * The algorithm from applyFormatAction:
 *   if text.startsWith(prefix) → remove prefix (toggle off)
 *   else → strip any other line-prefix, then prepend the new prefix (toggle on)
 *
 * Known line-prefix actions (in declaration order):
 *   QUOTE        → "> "
 *   NUMBERED_LIST → "1. "
 *   HEADING      → "# "
 */
class ToolbarActionTest {

    // ---- prefixes matching FormatAction declarations ----

    private val QUOTE_PREFIX          = "> "
    private val NUMBERED_LIST_PREFIX  = "1. "
    private val HEADING_PREFIX        = "# "

    /** All known line-prefix markers, mirroring FormatAction.entries with suffix=="". */
    private val ALL_LINE_PREFIXES = listOf(QUOTE_PREFIX, NUMBERED_LIST_PREFIX, HEADING_PREFIX)

    /**
     * Mirror of the line-prefix branch in applyFormatAction.
     * Returns the new content string after toggling [prefix] on or off.
     */
    private fun toggleLinePrefix(content: String, prefix: String): String {
        return if (content.startsWith(prefix)) {
            // Toggle off — remove the prefix
            content.removePrefix(prefix)
        } else {
            // Strip any conflicting line-prefix, then prepend the new one
            val stripped = ALL_LINE_PREFIXES
                .filter { it != prefix && content.startsWith(it) }
                .fold(content) { acc, other -> acc.removePrefix(other) }
            prefix + stripped
        }
    }

    // ---- QUOTE tests ----

    @Test
    fun `QUOTE prefix added to plain block content`() {
        val content = "hello world"

        val result = toggleLinePrefix(content, QUOTE_PREFIX)

        assertEquals("> hello world", result)
    }

    @Test
    fun `QUOTE prefix removed when block already starts with quote marker (toggle off)`() {
        val content = "> hello world"

        val result = toggleLinePrefix(content, QUOTE_PREFIX)

        assertEquals("hello world", result)
    }

    @Test
    fun `QUOTE prefix added to empty content`() {
        val content = ""

        val result = toggleLinePrefix(content, QUOTE_PREFIX)

        assertEquals("> ", result)
    }

    @Test
    fun `QUOTE toggled off on content that is only the prefix`() {
        val content = "> "

        val result = toggleLinePrefix(content, QUOTE_PREFIX)

        assertEquals("", result)
    }

    // ---- NUMBERED_LIST tests ----

    @Test
    fun `NUMBERED_LIST prefix added to plain block content`() {
        val content = "task item"

        val result = toggleLinePrefix(content, NUMBERED_LIST_PREFIX)

        assertEquals("1. task item", result)
    }

    @Test
    fun `NUMBERED_LIST prefix removed when block already starts with numbered-list marker`() {
        val content = "1. task item"

        val result = toggleLinePrefix(content, NUMBERED_LIST_PREFIX)

        assertEquals("task item", result)
    }

    @Test
    fun `NUMBERED_LIST prefix added to empty content`() {
        val content = ""

        val result = toggleLinePrefix(content, NUMBERED_LIST_PREFIX)

        assertEquals("1. ", result)
    }

    // ---- HEADING tests ----

    @Test
    fun `HEADING prefix added to plain block content`() {
        val content = "My Heading"

        val result = toggleLinePrefix(content, HEADING_PREFIX)

        assertEquals("# My Heading", result)
    }

    @Test
    fun `HEADING prefix removed when block already starts with heading marker`() {
        val content = "# My Heading"

        val result = toggleLinePrefix(content, HEADING_PREFIX)

        assertEquals("My Heading", result)
    }

    @Test
    fun `HEADING prefix added to empty content`() {
        val content = ""

        val result = toggleLinePrefix(content, HEADING_PREFIX)

        assertEquals("# ", result)
    }

    @Test
    fun `HEADING toggled off on content that is only the prefix`() {
        val content = "# "

        val result = toggleLinePrefix(content, HEADING_PREFIX)

        assertEquals("", result)
    }

    // ---- Conflicting-prefix replacement tests ----

    @Test
    fun `QUOTE replaces HEADING prefix stripping hash and adding chevron`() {
        val content = "# My Heading"

        val result = toggleLinePrefix(content, QUOTE_PREFIX)

        assertEquals("> My Heading", result)
    }

    @Test
    fun `HEADING replaces QUOTE prefix stripping chevron and adding hash`() {
        val content = "> some quoted text"

        val result = toggleLinePrefix(content, HEADING_PREFIX)

        assertEquals("# some quoted text", result)
    }

    @Test
    fun `NUMBERED_LIST replaces HEADING prefix`() {
        val content = "# Task as heading"

        val result = toggleLinePrefix(content, NUMBERED_LIST_PREFIX)

        assertEquals("1. Task as heading", result)
    }

    @Test
    fun `HEADING replaces NUMBERED_LIST prefix`() {
        val content = "1. numbered item"

        val result = toggleLinePrefix(content, HEADING_PREFIX)

        assertEquals("# numbered item", result)
    }

    @Test
    fun `QUOTE replaces NUMBERED_LIST prefix`() {
        val content = "1. numbered item"

        val result = toggleLinePrefix(content, QUOTE_PREFIX)

        assertEquals("> numbered item", result)
    }

    @Test
    fun `NUMBERED_LIST replaces QUOTE prefix`() {
        val content = "> quoted text"

        val result = toggleLinePrefix(content, NUMBERED_LIST_PREFIX)

        assertEquals("1. quoted text", result)
    }
}
