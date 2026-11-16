// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.clipboard

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.PasteElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.StyleElements
import dev.stapler.stelekit.sketch.v2.commands.StylePatch
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource

/** Where pasted text goes: our own format, an Excalidraw scene (Story 5.1.4 imports it), or nothing we read. */
enum class PasteRoute { SceneInternal, ExcalidrawJson, Unknown }

/**
 * What was copied (Story 3.5.3b): the elements with their bound labels and group and frame closure, serialisable to
 * text so it can sit on the system clipboard. The text is the v2 sheet encoding behind a marker line, so it round
 * trips every field the sheet does and is read back by the same codec.
 */
class ClipboardPayload private constructor(val elements: List<SceneElement>) {
    val ids: Set<ElementId> get() = elements.mapTo(LinkedHashSet()) { it.id }

    /** The clipboard text. Fails only when an element holds a value the codec refuses (it cannot, for a scene that loaded). */
    fun toText(limits: SceneLimits = SceneLimits.Default): Either<CommandError, String> {
        var scene = SketchSceneV2.empty(limits)
        for (e in elements) scene = scene.add(e).fold({ return it.left() }, { it })
        val bytes = SheetCodecV2.encode(SketchDocumentV2(scene = scene), limits)
            .fold({ return CommandError.Unsupported("clipboard", "encode").left() }, { it })
        return (MARKER + bytes.decodeToString()).right()
    }

    companion object {
        const val MARKER = "stelekit-sketch-v2\n"

        fun of(elements: List<SceneElement>): ClipboardPayload = ClipboardPayload(elements)

        fun fromText(text: String, limits: SceneLimits = SceneLimits.Default): ClipboardPayload? {
            if (!text.startsWith(MARKER)) return null
            val doc = SheetCodecV2.decode(text.removePrefix(MARKER).encodeToByteArray(), limits).getOrNull() ?: return null
            return ClipboardPayload(doc.scene.elementsInOrder)
        }

        /** Routes clipboard text: the marker means ours; a leading `{` or BOM is a candidate Excalidraw scene (D-47). */
        fun route(text: String): PasteRoute = when {
            text.startsWith(MARKER) -> PasteRoute.SceneInternal
            text.startsWith("{") || text.startsWith("﻿") -> PasteRoute.ExcalidrawJson
            else -> PasteRoute.Unknown
        }
    }
}

/** Copy, paste and duplicate on a scene; every paste lands [PASTE_OFFSET_UNITS] further from the source than the last. */
object SceneClipboard {
    /** Repeated pastes step by this many scene units on both axes (Story 3.5.3). */
    const val PASTE_OFFSET_UNITS = 10.0

    /**
     * The payload for [ids]: each element, the text labels bound to it, the members of a copied frame and the other
     * members of a group a copied element belongs to, in paint order.
     */
    fun copy(scene: SketchSceneV2, ids: Collection<ElementId>): ClipboardPayload {
        val wanted = LinkedHashSet<ElementId>()
        for (id in ids) if (id in scene) wanted += id
        val all = scene.elementsInOrder
        val groups = all.filter { it.id in wanted }.flatMap { it.base.groupIds }.toSet()
        for (e in all) {
            val inGroup = e.base.groupIds.any { it in groups }
            val member = e.base.frameId?.let { it in wanted } == true
            val label = e is TextElement && e.containerId?.let { it in wanted } == true
            if (inGroup || member || label) wanted += e.id
        }
        return ClipboardPayload.of(all.filter { it.id in wanted })
    }

    /** The command that pastes [payload] for the [pasteIndex]-th time in a row (0 for the first), with fresh ids from [ids]. */
    fun paste(payload: ClipboardPayload, pasteIndex: Int, ids: ElementIdSource): Command {
        val map = payload.elements.associate { it.id to ids.next() }
        val offset = PASTE_OFFSET_UNITS * (pasteIndex + 1)
        return PasteElements(payload.elements, map, offset, offset)
    }

    /** Copy style (FB-SP "copy styles"): the look of [element] as a patch. */
    fun copyStyle(element: SceneElement): StylePatch = element.base.style.let { s ->
        StylePatch(
            strokeColor = s.strokeColor, backgroundColor = s.backgroundColor, fillStyle = s.fillStyle,
            strokeWidth = s.strokeWidth, strokeStyle = s.strokeStyle, roughness = s.roughness,
            roundness = s.roundness, sharpCorners = s.roundness == null, opacity = element.base.opacity,
        )
    }

    fun pasteStyle(patch: StylePatch, targets: List<ElementId>): Command = StyleElements(targets, patch)
}
