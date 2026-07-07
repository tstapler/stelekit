package dev.stapler.stelekit.ui

import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.ui.components.applyFormatAction
import dev.stapler.stelekit.ui.screens.FormatAction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the line-prefix toggle logic used by applyFormatAction for
 * FormatAction.QUOTE, FormatAction.NUMBERED_LIST, and FormatAction.HEADING.
 *
 * All three are "line-prefix actions": suffix == "" and prefix is non-empty.
 *
 * The algorithm (see applyLinePrefixToggle in BlockEditor.kt):
 *   if text.startsWith(prefix) → remove prefix (toggle off)
 *   else → strip any other line-prefix, then prepend the new prefix (toggle on)
 *
 * These tests call the real production `applyFormatAction` function directly
 * (not a hand-copied mirror) so a regression in the actual implementation —
 * including its TODO-marker-splice interaction — is caught here.
 */
class ToolbarActionTest {

    /**
     * Drives the real applyFormatAction line-prefix branch and returns the resulting text.
     */
    private fun applyLinePrefix(action: FormatAction, content: String): String {
        var result = content
        applyFormatAction(
            action = action,
            textFieldValue = TextFieldValue(content),
            onTextFieldValueChange = { result = it.text },
            onLocalVersionIncrement = { 1L },
            onContentChange = { _, _ -> },
        )
        return result
    }

    // ---- QUOTE tests ----

    @Test
    fun `QUOTE prefix added to plain block content`() {
        val content = "hello world"

        val result = applyLinePrefix(FormatAction.QUOTE, content)

        assertEquals("> hello world", result)
    }

    @Test
    fun `QUOTE prefix removed when block already starts with quote marker (toggle off)`() {
        val content = "> hello world"

        val result = applyLinePrefix(FormatAction.QUOTE, content)

        assertEquals("hello world", result)
    }

    @Test
    fun `QUOTE prefix added to empty content`() {
        val content = ""

        val result = applyLinePrefix(FormatAction.QUOTE, content)

        assertEquals("> ", result)
    }

    @Test
    fun `QUOTE toggled off on content that is only the prefix`() {
        val content = "> "

        val result = applyLinePrefix(FormatAction.QUOTE, content)

        assertEquals("", result)
    }

    // ---- NUMBERED_LIST tests ----

    @Test
    fun `NUMBERED_LIST prefix added to plain block content`() {
        val content = "task item"

        val result = applyLinePrefix(FormatAction.NUMBERED_LIST, content)

        assertEquals("1. task item", result)
    }

    @Test
    fun `NUMBERED_LIST prefix removed when block already starts with numbered-list marker`() {
        val content = "1. task item"

        val result = applyLinePrefix(FormatAction.NUMBERED_LIST, content)

        assertEquals("task item", result)
    }

    @Test
    fun `NUMBERED_LIST prefix added to empty content`() {
        val content = ""

        val result = applyLinePrefix(FormatAction.NUMBERED_LIST, content)

        assertEquals("1. ", result)
    }

    // ---- HEADING tests ----

    @Test
    fun `HEADING prefix added to plain block content`() {
        val content = "My Heading"

        val result = applyLinePrefix(FormatAction.HEADING, content)

        assertEquals("# My Heading", result)
    }

    @Test
    fun `HEADING prefix removed when block already starts with heading marker`() {
        val content = "# My Heading"

        val result = applyLinePrefix(FormatAction.HEADING, content)

        assertEquals("My Heading", result)
    }

    @Test
    fun `HEADING prefix added to empty content`() {
        val content = ""

        val result = applyLinePrefix(FormatAction.HEADING, content)

        assertEquals("# ", result)
    }

    @Test
    fun `HEADING toggled off on content that is only the prefix`() {
        val content = "# "

        val result = applyLinePrefix(FormatAction.HEADING, content)

        assertEquals("", result)
    }

    // ---- Conflicting-prefix replacement tests ----

    @Test
    fun `QUOTE replaces HEADING prefix stripping hash and adding chevron`() {
        val content = "# My Heading"

        val result = applyLinePrefix(FormatAction.QUOTE, content)

        assertEquals("> My Heading", result)
    }

    @Test
    fun `HEADING replaces QUOTE prefix stripping chevron and adding hash`() {
        val content = "> some quoted text"

        val result = applyLinePrefix(FormatAction.HEADING, content)

        assertEquals("# some quoted text", result)
    }

    @Test
    fun `NUMBERED_LIST replaces HEADING prefix`() {
        val content = "# Task as heading"

        val result = applyLinePrefix(FormatAction.NUMBERED_LIST, content)

        assertEquals("1. Task as heading", result)
    }

    @Test
    fun `HEADING replaces NUMBERED_LIST prefix`() {
        val content = "1. numbered item"

        val result = applyLinePrefix(FormatAction.HEADING, content)

        assertEquals("# numbered item", result)
    }

    @Test
    fun `QUOTE replaces NUMBERED_LIST prefix`() {
        val content = "1. numbered item"

        val result = applyLinePrefix(FormatAction.QUOTE, content)

        assertEquals("> numbered item", result)
    }

    @Test
    fun `NUMBERED_LIST replaces QUOTE prefix`() {
        val content = "> quoted text"

        val result = applyLinePrefix(FormatAction.NUMBERED_LIST, content)

        assertEquals("1. quoted text", result)
    }
}
