// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketchimport

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.sketchimport.PngSize
import dev.stapler.stelekit.sketchimport.RasterInputError
import dev.stapler.stelekit.sketchimport.RasterLine
import dev.stapler.stelekit.sketchimport.RasterSession
import dev.stapler.stelekit.sketchimport.RasterTag
import dev.stapler.stelekit.sketchimport.RasterTagging
import dev.stapler.stelekit.ui.annotate.AnnotationPlacementCommit
import dev.stapler.stelekit.ui.annotate.AnnotationPlacementSession
import dev.stapler.stelekit.ui.annotate.AnnotationPlacementSpace
import dev.stapler.stelekit.ui.annotate.confirmed
import dev.stapler.stelekit.ui.annotate.nudged
import dev.stapler.stelekit.ui.annotate.respaced
import dev.stapler.stelekit.ui.annotate.tapped
import dev.stapler.stelekit.ui.annotate.toggled
import dev.stapler.stelekit.ui.placement.NudgeDirection
import dev.stapler.stelekit.ui.placement.NudgeStep
import dev.stapler.stelekit.units.Rational

/** The line the user typed a real length for; it can later be bound to a dimension as the exact typed value. */
data class ReferenceLine(val line: RasterLine, val length: Rational)

/**
 * The PNG calibration step of the import (Story 4.5b). Points are placed with the non-drag crosshair, so a phone needs no precise
 * dragging: [placement] moves by taps and nudges and a point is added only on confirm. Before [calibration] is set the line plus
 * [typedLength] define the scale; afterwards each line is measured and bound to a dimension key.
 */
data class RasterState(
    val size: PngSize,
    val placement: AnnotationPlacementSession = AnnotationPlacementSession(),
    val points: List<NormalizedPoint> = emptyList(),
    val typedLength: String = "",
    val calibration: Calibration? = null,
    val reference: ReferenceLine? = null,
    val tags: List<RasterTag> = emptyList(),
    val error: RasterInputError? = null,
) {
    val line: RasterLine? get() = if (points.size == LINE_POINTS) RasterLine(points[0], points[1]) else null

    val isCalibrated: Boolean get() = calibration != null

    /** Real length of the current line once calibrated, in feet and inches; null before there is a scale and a line. */
    val measuredText: String?
        get() {
            val scale = calibration ?: return null
            val current = line ?: return null
            return RasterTagging.measure(scale, size, current.start, current.end)?.let(RasterTagging::format)
        }

    val canCalibrate: Boolean get() = !isCalibrated && line != null && typedLength.isNotBlank()

    val session: RasterSession? get() = calibration?.let { RasterSession(it, tags) }

    companion object {
        const val LINE_POINTS = 2
    }
}

fun RasterState.space() = AnnotationPlacementSpace(size.widthPx, size.heightPx)

/** Shows the crosshair at the image center, or hides it. */
fun RasterState.toggled(): RasterState = copy(placement = placement.toggled(space()))

fun RasterState.nudged(direction: NudgeDirection, step: NudgeStep): RasterState = copy(placement = placement.nudged(space(), direction, step))

fun RasterState.tapped(point: NormalizedPoint): RasterState = copy(placement = placement.tapped(space(), point))

fun RasterState.respaced(): RasterState = copy(placement = placement.respaced(space()))

/** Adds the crosshair as a point; a third point starts a fresh line. */
fun RasterState.placed(): RasterState {
    val (next, commit) = placement.confirmed(space())
    val point = (commit as? AnnotationPlacementCommit.Place)?.point ?: return this
    val base = if (points.size >= RasterState.LINE_POINTS) emptyList() else points
    return copy(placement = next, points = base + point, error = null)
}

fun RasterState.typed(text: String): RasterState = copy(typedLength = text, error = null)

fun RasterState.clearedLine(): RasterState = copy(points = emptyList(), error = null)

/** Sets the scale from the current line and the typed length; the line becomes the [RasterState.reference]. */
fun RasterState.calibrated(): RasterState {
    val current = line ?: return copy(error = RasterInputError.PointsTooClose)
    val length = RasterTagging.parseTypedLength(typedLength).fold({ return copy(error = it) }, { it })
    return RasterTagging.calibrate(size, current.start, current.end, typedLength).fold(
        { copy(error = it) },
        { copy(calibration = it, reference = ReferenceLine(current, length), points = emptyList(), tags = emptyList(), error = null) },
    )
}

/** Drops the scale and every tag measured with it, so a wrong reference is simply redone. */
fun RasterState.recalibrating(): RasterState = copy(calibration = null, reference = null, tags = emptyList(), points = emptyList(), error = null)

/** Binds the current line, measured with the scale, to [key] (a registered length in [registry]). */
fun RasterState.tagged(registry: DimensionRegistry, key: String): Either<RasterInputError, RasterState> {
    val current = line ?: return RasterInputError.PointsTooClose.left()
    return RasterTagging.tag(registry, calibration, size, key, current).map { copy(tags = RasterTagging.upsert(tags, it), points = emptyList(), error = null) }
}

/** Binds the reference line to [key] at the exact length the user typed (not a re-measured value). */
fun RasterState.referenceTagged(registry: DimensionRegistry, key: String): Either<RasterInputError, RasterState> {
    val ref = reference ?: return RasterInputError.NotCalibrated.left()
    if (registry.entry(key)?.kind != DimensionKind.LENGTH) return RasterInputError.NotALengthKey(key).left()
    val tag = RasterTag(key, ref.line.start, ref.line.end, ref.length, measured = false)
    return copy(tags = RasterTagging.upsert(tags, tag), error = null).right()
}

fun RasterState.untagged(key: String): RasterState = copy(tags = tags.filterNot { it.key == key })

fun RasterState.failed(error: RasterInputError): RasterState = copy(error = error)
