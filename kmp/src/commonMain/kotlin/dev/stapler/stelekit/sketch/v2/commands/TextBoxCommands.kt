// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.binding.followBoundArrows
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ValueError

/**
 * What a handle drag on a text element writes (one undo step): the new box, font size, wrapped lines and wrap mode, all
 * computed while dragging so the committed element is exactly the preview. Corner handles scale the font with the box;
 * a side handle sets a fixed wrap width ([autoResize] false) and the box height follows the re-wrapped lines.
 * [fontSize] is not clamped here: the gesture clamps it, so a command replayed from history never changes it.
 */
data class SetTextBox(
    val id: ElementId,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val fontSize: Double,
    val text: String,
    val autoResize: Boolean,
) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val limits = scene.limits
        val e = scene[id] ?: raise(CommandError.UnknownElement(id.value))
        if (e !is TextElement) raise(CommandError.Unsupported(id.value, "text box"))
        if (width < 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(width)))
        if (height < 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(height)))
        if (text.length > limits.maxTextChars) raise(CommandError.TextTooLong(limits.maxTextChars))
        if (!fontSize.isFinite() || fontSize <= 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(fontSize)))
        val next = e.copy(
            x = sceneUnit(x, limits).bind(),
            y = sceneUnit(y, limits).bind(),
            width = sceneUnit(width, limits).bind(),
            height = sceneUnit(height, limits).bind(),
            fontSize = fontSize,
            text = text,
            autoResize = autoResize,
        )
        val resized = if (next == e) scene else scene.replace(next.touched()).bind()
        val followed = resized.followBoundArrows(setOf(id)).bind()
        finish(doc, followed.scene, setOf(id) + followed.arrows)
    }
}
