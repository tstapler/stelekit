// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2

// Scene builders and a commit helper shared by the selection-family tool tests (Stories 3.2.2 to 3.2.4, 3.4.2).

internal fun elId(s: String) = ElementId(s)

internal fun su(i: Int) = SceneUnit.ofInt(i)

internal fun sp(x: Int, y: Int) = ScenePoint(su(x), su(y))

/** Where a filled rectangle sits and what else it belongs to. */
internal data class RectSpec(
    val locked: Boolean = false,
    val layer: LayerId = LayerId.Default,
    val group: String? = null,
    val frame: String? = null,
)

/** A filled rectangle (so its interior is hit), optionally locked, grouped, framed or on another layer. */
internal fun filledRectEl(id: String, x: Int, y: Int, w: Int, h: Int, spec: RectSpec = RectSpec()): RectElement = RectElement(
    ElementBase(
        elId(id), layerId = spec.layer, groupIds = listOfNotNull(spec.group?.let { GroupId(it) }), frameId = spec.frame?.let { elId(it) },
        locked = spec.locked, style = ElementStyle(backgroundColor = "#cccccc"),
    ),
    su(x), su(y), su(w), su(h),
)

internal fun sceneOfElements(vararg elements: SceneElement): SketchSceneV2 =
    elements.fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull() ?: error("could not add ${e.id}") }

/** Applies [command] to [doc] as one transaction and returns the history holding that one step. */
internal fun commitToolCommand(doc: SketchDocumentV2, command: ToolCommand): HistoryV2 {
    val cmd = command.toCommand().getOrNull() ?: error("no mapping for $command")
    val tx = Transaction.begin(doc).apply(cmd).getOrNull() ?: error("command failed: $command")
    return HistoryV2.of(doc).commit(tx.commit().getOrNull() ?: error("no change from $command")).getOrNull() ?: error("history refused the step")
}
