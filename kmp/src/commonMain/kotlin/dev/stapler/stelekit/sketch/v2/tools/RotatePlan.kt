// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.commands.RotateSelection
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlin.math.PI

/** The point a typed or stepped rotation turns about: the selection's centre or a corner of its bounding box. */
enum class RotatePivot { Centre, TopLeft, TopRight, BottomRight, BottomLeft }

/**
 * Pure rules of the Rotate dialog: reading a typed angle and building the one command. A positive angle turns clockwise on screen.
 */
object RotatePlan {
    private const val HALF = 2.0
    private const val HALF_TURN_DEGREES = 180.0
    private const val FULL_TURN_DEGREES = 360.0

    /** The most turns a typed angle may ask for (a few full turns is a typo, not an intent). */
    private const val MAX_DEGREES = FULL_TURN_DEGREES * 10

    private val SUFFIXES = listOf("degrees", "degree", "deg", "°")

    /** Degrees from text such as `45`, `-12.5`, `90 deg` or `30°`; null when it is not a finite number within range. */
    fun parseDegrees(text: String): Double? {
        var t = text.trim().lowercase()
        SUFFIXES.firstOrNull { t.endsWith(it) }?.let { t = t.removeSuffix(it).trim() }
        val v = CanonicalJson.parseDouble(t) ?: return null
        return v.takeIf { it.isFinite() && kotlin.math.abs(it) <= MAX_DEGREES }
    }

    fun radians(degrees: Double): Double = degrees * PI / HALF_TURN_DEGREES

    /** The pivot of [ids] (those that exist and are not an opening on its own): the joint bounds' centre or corner; null when none. */
    fun pivotOf(scene: SketchSceneV2, ids: List<ElementId>, pivot: RotatePivot): Pair<Double, Double>? {
        val boxes = ids.mapNotNull { scene[it] }.filter { it !is OpeningElement }.map { ElementBounds.geometry(it) }
        if (boxes.isEmpty()) return null
        val minX = boxes.minOf { it.minX }
        val maxX = boxes.maxOf { it.maxX }
        val minY = boxes.minOf { it.minY }
        val maxY = boxes.maxOf { it.maxY }
        return when (pivot) {
            RotatePivot.Centre -> (minX + maxX) / HALF to (minY + maxY) / HALF
            RotatePivot.TopLeft -> minX to minY
            RotatePivot.TopRight -> maxX to minY
            RotatePivot.BottomRight -> maxX to maxY
            RotatePivot.BottomLeft -> minX to maxY
        }
    }

    /** The command that turns [ids] by [degrees] about [pivot]; null when the angle is zero or nothing can be turned. */
    fun command(scene: SketchSceneV2, ids: List<ElementId>, degrees: Double, pivot: RotatePivot): RotateSelection? {
        if (degrees == 0.0) return null
        val (px, py) = pivotOf(scene, ids, pivot) ?: return null
        return RotateSelection(ids, radians(degrees), px, py)
    }
}
