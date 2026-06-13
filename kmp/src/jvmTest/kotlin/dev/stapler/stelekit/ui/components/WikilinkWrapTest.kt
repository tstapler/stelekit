package dev.stapler.stelekit.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [[wikilink]] wrapping when the user types `[` with text selected.
 *
 * Two paths exist:
 * - Hardware keyboard: handled in handleKeyEvent via Key.LeftBracket (tested via Compose
 *   test rules in KeyboardShortcutTest).
 * - Soft keyboard: handled by detectSoftKeyboardBracketWrap in the onValueChange callback;
 *   testable as a pure function without a Compose harness.
 */
class WikilinkWrapTest {

    // ── detectSoftKeyboardBracketWrap ─────────────────────────────────────────

    @Test
    fun `wraps selected text with double brackets`() {
        val oldText = "See Java for details"
        val oldSelection = TextRange(4, 8)  // "Java"
        val afterTypingBracket = TextFieldValue(
            text = "See [ for details",
            selection = TextRange(5),
        )

        val result = detectSoftKeyboardBracketWrap(oldText, oldSelection, afterTypingBracket)!!

        assertEquals("See [[Java]] for details", result.text)
        assertEquals(TextRange(6, 10), result.selection) // selection stays on "Java"
    }

    @Test
    fun `wraps selection at start of text`() {
        val oldText = "Hello world"
        val oldSelection = TextRange(0, 5)  // "Hello"
        val afterBracket = TextFieldValue("[ world", TextRange(1))

        val result = detectSoftKeyboardBracketWrap(oldText, oldSelection, afterBracket)!!

        assertEquals("[[Hello]] world", result.text)
        assertEquals(TextRange(2, 7), result.selection)
    }

    @Test
    fun `wraps selection at end of text`() {
        val oldText = "Read the docs"
        val oldSelection = TextRange(9, 13)  // "docs"
        val afterBracket = TextFieldValue("Read the [", TextRange(10))

        val result = detectSoftKeyboardBracketWrap(oldText, oldSelection, afterBracket)!!

        assertEquals("Read the [[docs]]", result.text)
        assertEquals(TextRange(11, 15), result.selection)
    }

    @Test
    fun `wraps multi-word selection`() {
        val oldText = "click here to open"
        val oldSelection = TextRange(6, 18)  // "here to open"
        val afterBracket = TextFieldValue("click [", TextRange(7))

        val result = detectSoftKeyboardBracketWrap(oldText, oldSelection, afterBracket)!!

        assertEquals("click [[here to open]]", result.text)
        assertEquals(TextRange(8, 20), result.selection)
    }

    @Test
    fun `returns null when selection is collapsed (cursor, not selection)`() {
        val oldText = "Hello world"
        val collapsed = TextRange(5)  // cursor at position 5, no selection
        val afterBracket = TextFieldValue("Hello[ world", TextRange(6))

        val result = detectSoftKeyboardBracketWrap(oldText, collapsed, afterBracket)

        assertNull(result, "No wrapping when there is no text selection")
    }

    @Test
    fun `returns null when replacement character is not a single open bracket`() {
        val oldText = "Hello world"
        val selection = TextRange(6, 11)  // "world"
        // User typed 'x', not '[', replacing the selection
        val afterTypingX = TextFieldValue("Hello x", TextRange(7))

        val result = detectSoftKeyboardBracketWrap(oldText, selection, afterTypingX)

        assertNull(result, "Should not wrap when a non-bracket character replaces the selection")
    }

    @Test
    fun `returns null when new text is longer than single bracket replacement`() {
        // If the user typed "[x" instead of just "[", it's not the wrapping pattern
        val oldText = "Hello world"
        val selection = TextRange(6, 11)  // "world"
        val notSingleBracket = TextFieldValue("Hello [x", TextRange(8))

        val result = detectSoftKeyboardBracketWrap(oldText, selection, notSingleBracket)

        assertNull(result, "Should not wrap when more than one character replaced the selection")
    }

    @Test
    fun `preserves text outside the selection unchanged`() {
        val oldText = "prefix SELECTED suffix"
        val oldSelection = TextRange(7, 15)  // "SELECTED"
        val afterBracket = TextFieldValue("prefix [ suffix", TextRange(8))

        val result = detectSoftKeyboardBracketWrap(oldText, oldSelection, afterBracket)!!

        assertEquals("prefix [[SELECTED]] suffix", result.text)
    }
}
