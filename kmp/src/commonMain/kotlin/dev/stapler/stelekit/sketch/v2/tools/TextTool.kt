// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.EditLayoutResult
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextLayoutPolicy
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle

/** What an edit of the text did. */
sealed interface TextEditResult {
    data class Accepted(val text: String) : TextEditResult

    /** The new text is over `SceneLimits.maxTextChars`; the old text is kept and the user is told with [message] (UX-23). */
    data class TooLong(val maxChars: Int) : TextEditResult {
        val message: String get() = TextLimitMessage.tooLong(maxChars)
    }
}

object TextLimitMessage {
    /** "Text too long (max 10,000 characters)": the cap is always stated, with thousands separators. */
    fun tooLong(maxChars: Int): String = "Text too long (max ${withThousands(maxChars)} characters)"

    private const val GROUP = 3

    private fun withThousands(n: Int): String = n.toString().reversed().chunked(GROUP).joinToString(",").reversed()
}

/**
 * The text being typed, before it is committed. Pure: the overlay hands every edit to [replace], which refuses a
 * value over the limit without truncating it (UX-23), so pasting 10,001 characters changes nothing.
 */
class TextEditSession(val origin: ScenePoint, initial: String = "", private val limits: SceneLimits = SceneLimits.Default) {
    var text: String = initial
        private set

    fun replace(newText: String): TextEditResult =
        if (newText.length > limits.maxTextChars) {
            TextEditResult.TooLong(limits.maxTextChars)
        } else {
            text = newText
            TextEditResult.Accepted(newText)
        }
}

/**
 * Text tool (Story 3.4.1). A click starts an [Editing] state at that point; the overlay edits its [TextEditSession]
 * and calls [Editing.commit] on blur, [InputType.Escape] or a pointer press outside the overlay (UX-124). Blank text
 * commits nothing (UX-21): no element, no error, no undo entry. Measuring needs the bundled font, so while the font
 * is not [loaded][TextMetricsProvider.fontStatus] a commit is refused and editing continues (nothing unmeasured is persisted).
 */
class TextTool(private val metrics: TextMetricsProvider, private val limits: SceneLimits = SceneLimits.Default) : Tool {
    override val id: ToolId = ToolId.Text

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult =
        if (event.type == InputType.Down) ToolResult(next = Pressed(this, event.pointerId, event.point)) else ToolResult.Nothing

    private class Pressed(private val owner: TextTool, private val pointerId: Int, private val at: ScenePoint) : Tool {
        override val id: ToolId get() = owner.id

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
            event.type == InputType.Escape || event.type == InputType.Cancel -> ToolResult(next = owner)
            event.type == InputType.Down && event.pointerId != pointerId -> ToolResult(next = owner)
            event.pointerId != pointerId -> ToolResult.Nothing
            event.type == InputType.Up -> ToolResult(next = Editing(owner, TextEditSession(at, limits = owner.limits)))
            else -> ToolResult.Nothing
        }
    }

    class Editing internal constructor(private val owner: TextTool, val session: TextEditSession) : Tool {
        override val id: ToolId get() = owner.id

        /** True while committing would be refused because the bundled font has not loaded. */
        val fontPending: Boolean get() = owner.metrics.fontStatus != FontStatus.Loaded

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult =
            if (event.type == InputType.Escape || event.type == InputType.Down) commit(ctx) else ToolResult.Nothing

        /** Blur, Esc, [Done] or a tap outside: finish the edit. */
        fun commit(ctx: ToolContext): ToolResult {
            val raw = session.text
            if (raw.isBlank()) return ToolResult(next = owner)
            val style = TextStyle(FONT_FAMILY, TextElement.DEFAULT_FONT_SIZE)
            val layout = when (val r = TextLayoutPolicy.forEdit(raw, null, style, owner.metrics)) {
                EditLayoutResult.FontNotLoaded -> return ToolResult.Nothing
                is EditLayoutResult.Measured -> r.layout
            }
            val element = TextElement(
                base = ElementBase(ctx.ids.next(), style = ctx.style),
                x = session.origin.x,
                y = session.origin.y,
                width = unit(layout.width),
                height = unit(layout.height),
                text = layout.lines.joinToString("\n"),
                originalText = raw,
                fontSize = style.fontSize,
                fontFamily = style.fontFamily,
            )
            return ToolResult(AddElementCommand(element), null, owner)
        }

        private fun unit(v: Double): SceneUnit = SceneUnit.of(v).getOrNull() ?: SceneUnit.ZERO
    }

    private companion object {
        /** Excalidraw's default font family id. */
        const val FONT_FAMILY = 1
    }
}
