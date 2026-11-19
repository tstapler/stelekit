// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.4.1 (UX-21, UX-23, UX-124): click starts an edit, commit makes one TextElement, blank commits nothing, over-long text is refused. */
class TextToolTest {
    private val metrics = FixedAdvanceTextMetricsProvider()
    private val ctx = testToolContext()

    private fun editingAfterClick(tool: TextTool = TextTool(metrics)): TextTool.Editing {
        val r = runScenario(tool, ctx) { down(10, 10); up(10, 10) }
        return assertIs<TextTool.Editing>(r.finalTool)
    }

    @Test
    fun click_should_StartEditingAtThePoint_WithNoCommand() {
        val r = runScenario(TextTool(metrics), ctx) { down(10, 10); up(10, 10) }
        assertTrue(r.commands.isEmpty())
        val editing = assertIs<TextTool.Editing>(r.finalTool)
        assertEquals(10.0, editing.session.origin.x.value)
        assertEquals(10.0, editing.session.origin.y.value)
    }

    @Test
    fun commit_should_EmitOneTextElement_WithMultilineOriginalText() {
        val editing = editingAfterClick()
        editing.session.replace("Hi\nThere")
        val result = editing.commit(ctx)
        val text = assertIs<TextElement>(assertIs<AddElementCommand>(result.command).element)
        assertEquals("Hi\nThere", text.originalText)
        assertEquals(10.0, text.x.value)
        assertEquals("There".length * 8.0, text.width.value)
        assertEquals(2 * 20.0, text.height.value)
        assertIs<TextTool>(result.next)
    }

    @Test
    fun blank_should_LeaveNothing_When_CommittedOnBlurOrEscape() {
        val blur = editingAfterClick()
        blur.session.replace("  \n ")
        assertNull(blur.commit(ctx).command)
        val esc = editingAfterClick()
        val r = esc.onEvent(InputEvent.escape(), ctx)
        assertNull(r.command)
        assertIs<TextTool>(r.next)
    }

    @Test
    fun escapeAndOutsidePress_should_Commit_AndPlainMovesShouldNot() {
        val esc = editingAfterClick().also { it.session.replace("a") }
        assertIs<AddElementCommand>(esc.onEvent(InputEvent.escape(), ctx).command)
        val outside = editingAfterClick().also { it.session.replace("b") }
        val down = InputEvent(InputType.Down, InputEvent.Origin)
        assertIs<AddElementCommand>(outside.onEvent(down, ctx).command)
        val move = editingAfterClick().also { it.session.replace("c") }
        assertEquals(ToolResult.Nothing, move.onEvent(InputEvent(InputType.Move, InputEvent.Origin), ctx))
    }

    @Test
    fun commit_should_BeRefused_And_EditingContinue_WhileFontIsNotLoaded() {
        val loading = TextTool(FixedAdvanceTextMetricsProvider(fontStatus = FontStatus.Loading))
        val editing = editingAfterClick(loading)
        editing.session.replace("hello")
        assertTrue(editing.fontPending)
        assertEquals(ToolResult.Nothing, editing.commit(ctx))
    }

    @Test
    fun overLongText_should_BeRefusedNotTruncated_WithCapInTheMessage() {
        val session = TextEditSession(InputEvent.Origin, limits = SceneLimits.Default)
        assertIs<TextEditResult.Accepted>(session.replace("Hello"))
        val tooLong = session.replace("x".repeat(SceneLimits.Default.maxTextChars + 1))
        val refused = assertIs<TextEditResult.TooLong>(tooLong)
        assertEquals("Text too long (max 10,000 characters)", refused.message)
        assertEquals("Hello", session.text)
        assertIs<TextEditResult.Accepted>(session.replace("y".repeat(SceneLimits.Default.maxTextChars)))
    }

    @Test
    fun secondPointerOrEscape_should_AbandonTheClick() {
        val tool = TextTool(metrics)
        val second = runScenario(tool, ctx) { down(1, 1); down(5, 5, pointer = 1); up(1, 1) }
        assertTrue(second.finalTool === tool)
        val esc = runScenario(tool, ctx) { down(1, 1); escape(); up(1, 1) }
        assertTrue(esc.finalTool === tool)
    }

    @Test
    fun limitMessage_should_UseThousandsSeparators() {
        assertEquals("Text too long (max 999 characters)", TextLimitMessage.tooLong(999))
        assertEquals("Text too long (max 1,234,567 characters)", TextLimitMessage.tooLong(1_234_567))
    }
}
