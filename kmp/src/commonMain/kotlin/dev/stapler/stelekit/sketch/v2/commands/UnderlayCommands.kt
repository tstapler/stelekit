// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.scene.CalibrationRef
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.UnderlayCalibration
import dev.stapler.stelekit.sketch.v2.scene.UnderlayIds
import dev.stapler.stelekit.sketch.v2.scene.UnderlayMeta
import dev.stapler.stelekit.sketch.v2.scene.UnderlayMetaCodec
import dev.stapler.stelekit.sketch.v2.scene.UnderlayPlacement
import dev.stapler.stelekit.sketch.v2.scene.UnderlayState
import dev.stapler.stelekit.sketch.v2.scene.underlay
import kotlinx.serialization.json.JsonObject

// Underlay commands (epic E5). Lock, visibility and opacity of the underlay are those of its layer, so the Layers panel and
// `SetLayerLocked` / `SetLayerOpacity` already change them; these commands cover what a layer cannot: placing the photo,
// moving it, calibrating it and removing it.

private fun SketchSceneV2.requireUnderlay(): Either<CommandError, UnderlayState> =
    underlay?.let { Either.Right(it) } ?: CommandError.UnknownElement(UnderlayIds.ELEMENT.value).left()

private fun placedElement(old: ImageElement, p: UnderlayPlacement, limits: SceneLimits): Either<CommandError, ImageElement> = either {
    val x = sceneUnit(p.x, limits).bind()
    val y = sceneUnit(p.y, limits).bind()
    val w = sceneUnit(p.width, limits).bind()
    val h = sceneUnit(p.height, limits).bind()
    old.copy(x = x, y = y, width = w, height = h, angle = p.angle, base = old.base.copy(version = old.base.version + 1))
}

/**
 * Puts the photo [fileId] on the sheet as the underlay: on the layer [UnderlayIds.LAYER], below every other layer, locked, at
 * [UnderlayIds.DEFAULT_OPACITY]. A sheet holds one underlay; setting another replaces the image and forgets its calibration but
 * keeps the layer's lock and opacity.
 */
data class SetUnderlay(val fileId: String, val widthPx: Int, val heightPx: Int, val placement: UnderlayPlacement) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val limits = scene.limits
        val withLayer = if (scene.layer(UnderlayIds.LAYER) != null) scene else {
            if (scene.layers.size >= limits.maxLayers) raise(CommandError.TooManyLayers(limits.maxLayers))
            val order = (scene.layers.minOfOrNull { it.order } ?: 0) - 1
            scene.withLayers(scene.layers + Layer(UnderlayIds.LAYER, UnderlayIds.LAYER_NAME, locked = true, opacity = UnderlayIds.DEFAULT_OPACITY, order = order))
        }
        val extras = UnderlayMetaCodec.write(JsonObject(emptyMap()), UnderlayMeta(widthPx, heightPx))
        val base = ElementBase(UnderlayIds.ELEMENT, layerId = UnderlayIds.LAYER, name = UnderlayIds.ELEMENT_NAME, extras = extras)
        val fresh = ImageElement(base, SceneUnit.ZERO, SceneUnit.ZERO, SceneUnit.ofInt(1), SceneUnit.ofInt(1), fileId = fileId)
        val placed = placedElement(fresh, placement, limits).bind()
        val old = withLayer[UnderlayIds.ELEMENT]
        val next = if (old != null) {
            withLayer.replace(placed.copy(base = placed.base.copy(version = old.base.version + 1))).bind()
        } else {
            withLayer.add(placed).bind()
        }
        finish(doc, next, setOf(UnderlayIds.ELEMENT))
    }
}

/** Moves, turns or resizes the underlay (a finished one- or two-finger adjust). */
data class SetUnderlayPlacement(val placement: UnderlayPlacement) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val state = doc.scene.requireUnderlay().bind()
        val moved = placedElement(state.element, placement, doc.scene.limits).bind()
        finish(doc, doc.scene.replace(moved).bind(), setOf(UnderlayIds.ELEMENT))
    }
}

/**
 * Scales (and, with [level], turns) the underlay so the scene distance from ([aX], [aY]) to ([bX], [bY]) is [lengthUnits]
 * sixteenths of an inch. With [addReference] the length is kept as a further reference (a check against perspective) and the
 * photo's scale becomes the mean of all references; otherwise it replaces every earlier reference.
 */
@Suppress("LongParameterList")
data class CalibrateUnderlay(
    val aX: Double,
    val aY: Double,
    val bX: Double,
    val bY: Double,
    val lengthUnits: Long,
    val level: Boolean = true,
    val addReference: Boolean = false,
) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val state = doc.scene.requireUnderlay().bind()
        if (lengthUnits <= 0L || !listOf(aX, aY, bX, bY).all { it.isFinite() }) raise(CommandError.Unsupported(UnderlayIds.ELEMENT.value, "calibrate: bad input"))
        val p = state.placement
        val w = state.meta.widthPx
        val h = state.meta.heightPx
        val ref = CalibrationRef(p.toImage(aX, aY, w, h), p.toImage(bX, bY, w, h), lengthUnits)
        val refs = if (addReference) state.meta.refs + ref else listOf(ref)
        val solved = UnderlayCalibration.solve(p, w, h, refs, level).mapLeft { CommandError.Unsupported(UnderlayIds.ELEMENT.value, "calibrate: $it") }.bind()
        val moved = placedElement(state.element, solved.placement, doc.scene.limits).bind()
        val stored = moved.copy(base = moved.base.copy(extras = UnderlayMetaCodec.write(moved.base.extras, state.meta.copy(refs = refs))))
        finish(doc, doc.scene.replace(stored).bind(), setOf(UnderlayIds.ELEMENT))
    }
}

/** Removes the underlay and its layer, so a sheet that never had one is what it was before. */
data object RemoveUnderlay : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val removed = if (UnderlayIds.ELEMENT in doc.scene) doc.scene.remove(UnderlayIds.ELEMENT).bind() else doc.scene
        finish(doc, removed.withLayers(removed.layers.filter { it.id != UnderlayIds.LAYER }), setOf(UnderlayIds.ELEMENT))
    }
}
