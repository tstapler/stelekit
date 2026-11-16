// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.bridge

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.SheetProps
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ValueError
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/** Why a v1 sheet cannot be lifted into a v2 scene within [SceneLimits]. */
sealed interface LiftError {
    /** The v1 scene holds more primitives than a v2 scene may (v1 allows 10,000, v2 `maxElements`). */
    data class TooManyElements(val count: Int, val limit: Int) : LiftError

    /** The sheet (plus layers only primitives name) needs more layers than `maxLayers`. */
    data class TooManyLayers(val count: Int, val limit: Int) : LiftError

    data class LayerIdTooLong(val name: String, val limit: Int) : LiftError

    data class InvalidCoordinate(val id: String, val error: ValueError) : LiftError

    data class Rejected(val error: CommandError) : LiftError
}

/** `extras` key marking a lifted rectangle whose v1 corner `a` was the larger x and/or y; see [V1ToV2.lift]. */
internal const val RECT_FLIP_KEY = "v1RectFlip"

/**
 * Lifts v1 sheets into the v2 model without losing anything (ADR-002): a v1 `Long` below 2^53 is an exact `Double`, so
 * the number is unchanged and 1 unit is still 1/16 inch. Paint order becomes the order of the scene, and each layer
 * name becomes a layer with id `v1:<name>` (the name `""` is the default layer). A rectangle's corners are stored as
 * the box (`x`, `y`, positive `width`, `height`) plus the [RECT_FLIP_KEY] marker (`x`, `y` or `xy`) that records which
 * way the drawn corner `a` pointed, so [V2ToV1.projectScene] restores the exact corners.
 */
object V1ToV2 {
    private const val LAYER_ID_PREFIX = "v1:"
    private const val Z_KEY_DIGITS = 6

    fun lift(scene: SketchScene, sheet: SheetProps = SheetProps(), limits: SceneLimits = SceneLimits.Default): Either<LiftError, SketchSceneV2> = either {
        if (scene.size > limits.maxElements) raise(LiftError.TooManyElements(scene.size, limits.maxElements))
        val names = (sheet.layers.map { it.name } + scene.primitives.map { it.layer }.filter { it.isNotEmpty() }).distinct()
        if (names.size + 1 > limits.maxLayers) raise(LiftError.TooManyLayers(names.size + 1, limits.maxLayers))
        names.firstOrNull { layerId(it).value.length > limits.maxLayerIdChars }?.let { raise(LiftError.LayerIdTooLong(it, limits.maxLayerIdChars)) }

        val layers = listOf(Layer.DefaultLayer) + names.mapIndexed { i, name ->
            val props = sheet.layer(name)
            Layer(layerId(name), name, visible = props?.visible ?: true, locked = props?.locked ?: false, order = i + 1)
        }
        var out = SketchSceneV2.empty(limits).withLayers(layers)
        scene.primitives.forEachIndexed { index, p ->
            out = out.add(liftPrimitive(p, index, limits).bind()).mapLeft { LiftError.Rejected(it) }.bind()
        }
        out
    }

    internal fun layerId(name: String): LayerId = if (name.isEmpty()) LayerId.Default else LayerId(LAYER_ID_PREFIX + name)

    internal fun layerName(id: LayerId): String? = when {
        id == LayerId.Default -> ""
        id.value.startsWith(LAYER_ID_PREFIX) -> id.value.removePrefix(LAYER_ID_PREFIX)
        else -> null
    }

    private fun baseFor(p: SketchPrimitive, index: Int, extras: JsonObject = JsonObject(emptyMap())) = ElementBase(
        id = ElementId(p.id),
        layerId = layerId(p.layer),
        zKey = ZKey("a" + index.toString().padStart(Z_KEY_DIGITS, '0')),
        extras = extras,
    )

    private fun unit(id: String, v: Long, limits: SceneLimits): Either<LiftError, SceneUnit> =
        SceneUnit.of(v.toDouble(), limits).mapLeft { LiftError.InvalidCoordinate(id, it) }

    private fun point(id: String, p: SketchPoint, limits: SceneLimits): Either<LiftError, ScenePoint> = either {
        ScenePoint(unit(id, p.x.sixteenths, limits).bind(), unit(id, p.y.sixteenths, limits).bind())
    }

    private fun liftPrimitive(p: SketchPrimitive, index: Int, limits: SceneLimits): Either<LiftError, SceneElement> = either {
        when (p) {
            is SketchPrimitive.Line -> LineElement(baseFor(p, index), listOf(point(p.id, p.a, limits).bind(), point(p.id, p.b, limits).bind()))
            is SketchPrimitive.Rect -> {
                val ax = p.a.x.sixteenths
                val ay = p.a.y.sixteenths
                val bx = p.b.x.sixteenths
                val by = p.b.y.sixteenths
                val flip = (if (ax > bx) "x" else "") + (if (ay > by) "y" else "")
                val extras = if (flip.isEmpty()) JsonObject(emptyMap()) else JsonObject(mapOf(RECT_FLIP_KEY to JsonPrimitive(flip)))
                RectElement(
                    baseFor(p, index, extras),
                    x = unit(p.id, minOf(ax, bx), limits).bind(),
                    y = unit(p.id, minOf(ay, by), limits).bind(),
                    width = unit(p.id, abs(bx - ax), limits).bind(),
                    height = unit(p.id, abs(by - ay), limits).bind(),
                )
            }
        }
    }
}
