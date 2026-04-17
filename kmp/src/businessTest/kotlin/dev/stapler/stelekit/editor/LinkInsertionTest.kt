package dev.stapler.stelekit.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the link-insertion string logic used by applyFormatAction(LINK) and
 * BlockStateManager.insertLinkAtCursor.
 *
 * FormatAction.LINK has prefix="[[" and suffix="]]".
 *
 * applyFormatAction (collapsed, no selection):
 *   newText = text[0..pos] + "[[" + "]]" + text[pos..]
 *   newCursor = pos + 2   (between the brackets)
 *
 * applyFormatAction (selection start..end):
 *   newText = text[0..start] + "[[" + selected + "]]" + text[end..]
 *   newCursorRange = (start + 2) .. (end + 2)  (selection covers wrapped text)
 *
 * insertLinkAtCursor (BlockStateManager):
 *   linkText = "[[$pageName]]"
 *   safePos  = cursor.coerceIn(0, content.length)
 *   newContent = content[0..safePos] + linkText + content[safePos..]
 *   newCursor  = safePos + linkText.length
 */
class LinkInsertionTest {

    // ---- helpers mirroring the production string logic ----

    private val LINK_PREFIX = "[["
    private val LINK_SUFFIX = "]]"

    /** Mirror of the collapsed-cursor branch in applyFormatAction for LINK. */
    private fun applyLinkAtCursor(content: String, cursor: Int): Pair<String, Int> {
        val newText = content.substring(0, cursor) + LINK_PREFIX + LINK_SUFFIX + content.substring(cursor)
        val newCursor = cursor + LINK_PREFIX.length
        return newText to newCursor
    }

    /** Mirror of the selection-wrap branch in applyFormatAction for LINK. */
    private fun applyLinkWrappingSelection(
        content: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): Triple<String, Int, Int> {
        val selected = content.substring(selectionStart, selectionEnd)
        val newText = content.substring(0, selectionStart) + LINK_PREFIX + selected + LINK_SUFFIX + content.substring(selectionEnd)
        val newSelStart = selectionStart + LINK_PREFIX.length
        val newSelEnd   = selectionEnd   + LINK_PREFIX.length
        return Triple(newText, newSelStart, newSelEnd)
    }

    /** Mirror of the string operation in BlockStateManager.insertLinkAtCursor. */
    private fun insertLinkAtCursor(content: String, cursor: Int, pageName: String): Pair<String, Int> {
        val linkText = "[[$pageName]]"
        val safePos = cursor.coerceIn(0, content.length)
        val newContent = content.substring(0, safePos) + linkText + content.substring(safePos)
        val newCursor = safePos + linkText.length
        return newContent to newCursor
    }

    // ---- applyFormatAction(LINK) collapsed-cursor tests ----

    @Test
    fun `link inserted at cursor mid-word places brackets and positions cursor between them`() {
        val content = "Hello world"
        val cursor = 5

        val (newText, newCursor) = applyLinkAtCursor(content, cursor)

        assertEquals("Hello[[]] world", newText)
        assertEquals(7, newCursor)
    }

    @Test
    fun `link inserted at start of content places brackets before existing text`() {
        val content = "Hello"
        val cursor = 0

        val (newText, newCursor) = applyLinkAtCursor(content, cursor)

        assertEquals("[[]]Hello", newText)
        assertEquals(2, newCursor)
    }

    @Test
    fun `link inserted at end of content appends brackets after existing text`() {
        val content = "Hello"
        val cursor = 5

        val (newText, newCursor) = applyLinkAtCursor(content, cursor)

        assertEquals("Hello[[]]", newText)
        assertEquals(7, newCursor)
    }

    // ---- applyFormatAction(LINK) selection-wrap test ----

    @Test
    fun `link wraps selected text with double brackets and selection covers wrapped word`() {
        val content = "Hello world"
        // "world" occupies indices 6..11
        val selStart = 6
        val selEnd = 11

        val (newText, newSelStart, newSelEnd) = applyLinkWrappingSelection(content, selStart, selEnd)

        assertEquals("Hello [[world]]", newText)
        // Selection shifts by prefix length (2) on both ends
        assertEquals(8, newSelStart)
        assertEquals(13, newSelEnd)
    }

    // ---- insertLinkAtCursor string operation tests ----

    @Test
    fun `insertLinkAtCursor inserts page link at given cursor position`() {
        val content = "Hello world"
        val cursor = 5
        val pageName = "Notes"

        val (newContent, newCursor) = insertLinkAtCursor(content, cursor, pageName)

        assertEquals("Hello[[Notes]] world", newContent)
        // "[[Notes]]" is 9 chars; safePos=5; newCursor = 5 + 9 = 14
        assertEquals(14, newCursor)
    }

    @Test
    fun `insertLinkAtCursor at position 0 prepends link`() {
        val content = "World"
        val cursor = 0
        val pageName = "Home"

        val (newContent, newCursor) = insertLinkAtCursor(content, cursor, pageName)

        assertEquals("[[Home]]World", newContent)
        assertEquals(8, newCursor)
    }

    @Test
    fun `insertLinkAtCursor at end of content appends link`() {
        val content = "See also"
        val cursor = content.length
        val pageName = "References"

        val (newContent, newCursor) = insertLinkAtCursor(content, cursor, pageName)

        assertEquals("See also[[References]]", newContent)
        assertEquals(22, newCursor)
    }

    @Test
    fun `insertLinkAtCursor clamps out-of-bounds cursor to end of content`() {
        val content = "Short"
        val cursor = 999
        val pageName = "Page"

        val (newContent, newCursor) = insertLinkAtCursor(content, cursor, pageName)

        // safePos clamped to content.length = 5
        assertEquals("Short[[Page]]", newContent)
        assertEquals(13, newCursor)
    }

    @Test
    fun `insertLinkAtCursor on empty content inserts link only`() {
        val content = ""
        val cursor = 0
        val pageName = "Empty"

        val (newContent, newCursor) = insertLinkAtCursor(content, cursor, pageName)

        assertEquals("[[Empty]]", newContent)
        assertEquals(9, newCursor)
    }
}
