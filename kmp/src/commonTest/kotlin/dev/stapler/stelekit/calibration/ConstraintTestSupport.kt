// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import arrow.core.Either
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.test.fail

fun GoldenSegment.toConstraint() = CalibrationConstraint(id, p1, p2, trueMeters)

fun GoldenScene.frame(exifFocal35mmEq: Double? = null) = CaptureFrameInfo(widthPx, heightPx, exifFocal35mmEq)

fun constraint(id: String, x1: Double, y1: Double, x2: Double, y2: Double, meters: Double) =
    CalibrationConstraint(id, NormalizedPoint(x1, y1), NormalizedPoint(x2, y2), meters)

fun <A, B> Either<A, B>.right(): B = when (this) {
    is Either.Right -> value
    is Either.Left -> fail("expected Right but was Left($value)")
}

fun <A, B> Either<A, B>.left(): A = when (this) {
    is Either.Left -> value
    is Either.Right -> fail("expected Left but was Right($value)")
}

val FRAME_4000x3000 = CaptureFrameInfo(4000.0, 3000.0)
