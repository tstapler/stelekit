// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import io.kotest.property.Arb
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Epic E5: the two-point calibration recovers a known scale and rotation, and refuses degenerate input. */
class UnderlayCalibrationTest {
    private val widthPx = 4000
    private val heightPx = 3000
    private val tolerance = 1.0e-6

    private fun finite(min: Double, max: Double) = Arb.numericDouble(min, max)

    private fun placement(x: Double, y: Double, w: Double, angle: Double) = UnderlayPlacement(x, y, w, w * heightPx / widthPx, angle)

    private fun sceneDistance(p: UnderlayPlacement, a: ImagePx, b: ImagePx): Double {
        val (ax, ay) = p.toScene(a, widthPx, heightPx)
        val (bx, by) = p.toScene(b, widthPx, heightPx)
        return hypot(bx - ax, by - ay)
    }

    @Test
    fun solve_should_MakeTheReferenceLengthTrue_ForAnyPlacementAndPoints_Property() = runTest {
        checkAll(
            400,
            finite(-5_000.0, 5_000.0), finite(-5_000.0, 5_000.0), finite(100.0, 4_000.0), finite(-PI, PI),
            finite(0.0, widthPx.toDouble()), finite(0.0, heightPx.toDouble()), finite(0.0, widthPx.toDouble()), finite(0.0, heightPx.toDouble()),
            Arb.long(16L, 200_000L),
        ) { x, y, w, angle, au, av, bu, bv, len ->
            val ref = CalibrationRef(ImagePx(au, av), ImagePx(bu, bv), len)
            val start = placement(x, y, w, angle)
            val solved = UnderlayCalibration.solve(start, widthPx, heightPx, listOf(ref), level = false)
            if (ref.pixelLength < UnderlayCalibration.MIN_REF_PIXELS) {
                assertIs<arrow.core.Either.Left<*>>(solved)
            } else {
                val result = solved.getOrNull()
                assertNotNull(result)
                assertEquals(len.toDouble(), sceneDistance(result.placement, ref.a, ref.b), len * tolerance)
                assertEquals(len / ref.pixelLength, result.unitsPerPixel, tolerance)
                // The photo is turned by nothing when levelling is off, and its midpoint on the sheet stays put.
                assertEquals(start.angle, result.placement.angle, 1.0e-9)
                val mid = ImagePx((au + bu) / 2, (av + bv) / 2)
                val before = start.toScene(mid, widthPx, heightPx)
                val after = result.placement.toScene(mid, widthPx, heightPx)
                assertEquals(before.first, after.first, 1.0e-6)
                assertEquals(before.second, after.second, 1.0e-6)
            }
        }
    }

    @Test
    fun solve_should_RecoverTheScaleOfAnyOtherPair_When_ThePhotoIsAUniformScaleOfTheWorld_Property() = runTest {
        // A world with the true scale s units per pixel: every pair of pixels is s * pixelDistance apart, so one reference fixes all of them.
        checkAll(300, finite(0.05, 60.0), finite(40.0, 1_500.0), finite(-PI, PI), finite(0.0, 3_000.0), finite(0.0, 2_000.0), finite(0.0, 3_000.0), finite(0.0, 2_000.0)) { trueScale, refPx, refHeading, u, v, u2, v2 ->
            val a = ImagePx(500.0, 500.0)
            val b = ImagePx(500.0 + refPx * kotlin.math.cos(refHeading), 500.0 + refPx * sin(refHeading))
            val len = (trueScale * refPx).let { if (it < 1.0) 1L else it.toLong() }
            val exactScale = len / refPx
            val result = UnderlayCalibration.solve(placement(0.0, 0.0, 800.0, 0.3), widthPx, heightPx, listOf(CalibrationRef(a, b, len)), level = false).getOrNull()
            assertNotNull(result)
            val p = ImagePx(u, v)
            val q = ImagePx(u2, v2)
            assertEquals(exactScale * hypot(u2 - u, v2 - v), sceneDistance(result.placement, p, q), 1.0e-5 * (1.0 + hypot(u2 - u, v2 - v) * exactScale))
        }
    }

    @Test
    fun level_should_TurnTheReferenceOntoTheNearestAxis_When_WithinReach_Property() = runTest {
        checkAll(300, finite(-PI, PI), finite(-0.3, 0.3), Arb.int(0..3)) { angle, wobble, quarter ->
            // A reference drawn along an axis plus a small wobble, in a photo turned by `angle`.
            val heading = quarter * PI / 2 + wobble
            val a = ImagePx(1000.0, 1000.0)
            val b = ImagePx(1000.0 + 600 * kotlin.math.cos(heading - angle), 1000.0 + 600 * sin(heading - angle))
            val ref = CalibrationRef(a, b, 1_920L)
            val result = UnderlayCalibration.solve(placement(0.0, 0.0, 1_000.0, angle), widthPx, heightPx, listOf(ref), level = true).getOrNull()
            assertNotNull(result)
            val (ax, ay) = result.placement.toScene(a, widthPx, heightPx)
            val (bx, by) = result.placement.toScene(b, widthPx, heightPx)
            val turned = atan2(by - ay, bx - ax)
            val offAxis = abs(sin(2 * turned))
            assertTrue(offAxis < 1.0e-9, "reference heading $turned should sit on an axis (off by $offAxis)")
        }
    }

    @Test
    fun level_should_LeaveADiagonalReferenceAlone() {
        val ref = CalibrationRef(ImagePx(0.0, 0.0), ImagePx(500.0, 500.0), 960L)
        val start = placement(0.0, 0.0, 1_000.0, 0.0)
        val result = UnderlayCalibration.solve(start, widthPx, heightPx, listOf(ref), level = true).getOrNull()
        assertNotNull(result)
        assertEquals(0.0, result.placement.angle, 1.0e-12)
    }

    @Test
    fun solve_should_RejectDegeneratePoints_AndNonsense() {
        val start = placement(0.0, 0.0, 1_000.0, 0.0)
        val same = CalibrationRef(ImagePx(10.0, 10.0), ImagePx(10.0, 10.0), 192L)
        assertEquals(CalibrationError.PointsTooClose, UnderlayCalibration.solve(start, widthPx, heightPx, listOf(same), level = true).leftOrNull())
        val close = CalibrationRef(ImagePx(10.0, 10.0), ImagePx(14.0, 10.0), 192L)
        assertEquals(CalibrationError.PointsTooClose, UnderlayCalibration.solve(start, widthPx, heightPx, listOf(close), level = false).leftOrNull())
        assertEquals(CalibrationError.NoReferences, UnderlayCalibration.solve(start, widthPx, heightPx, emptyList(), level = false).leftOrNull())
        assertFailsWith<IllegalArgumentException> { CalibrationRef(ImagePx(0.0, 0.0), ImagePx(100.0, 0.0), 0L) }
        assertFailsWith<IllegalArgumentException> { ImagePx(Double.NaN, 0.0) }
        val huge = CalibrationRef(ImagePx(0.0, 0.0), ImagePx(100.0, 0.0), Long.MAX_VALUE / 2)
        assertEquals(CalibrationError.OutOfRange, UnderlayCalibration.solve(start, widthPx, heightPx, listOf(huge), level = false).leftOrNull())
    }

    @Test
    fun residual_should_FlagPerspective_When_ReferencesDisagreeByMoreThanFivePercent() {
        val ref = CalibrationRef(ImagePx(0.0, 0.0), ImagePx(100.0, 0.0), 1_000L)
        val agrees = CalibrationRef(ImagePx(0.0, 100.0), ImagePx(0.0, 300.0), 2_000L)
        val slightly = CalibrationRef(ImagePx(0.0, 100.0), ImagePx(0.0, 300.0), 2_060L)
        val off = CalibrationRef(ImagePx(0.0, 100.0), ImagePx(0.0, 300.0), 2_400L)
        assertEquals(0.0, UnderlayCalibration.residual(listOf(ref)))
        assertEquals(0.0, UnderlayCalibration.residual(listOf(ref, agrees)), 1.0e-12)
        assertTrue(!UnderlayCalibration.hasPerspective(listOf(ref, slightly)))
        assertTrue(UnderlayCalibration.hasPerspective(listOf(ref, off)))
    }

    @Test
    fun meta_should_RoundTripThroughExtras_Property() = runTest {
        checkAll(200, Arb.int(1..10_000), Arb.int(1..10_000), finite(0.0, 9_999.0), finite(0.0, 9_999.0), Arb.long(1L, 1_000_000L)) { w, h, u, v, len ->
            val meta = UnderlayMeta(w, h, listOf(CalibrationRef(ImagePx(u + 0.0, v + 0.0), ImagePx(u + 10.0, v + 0.0), len)))
            val written = UnderlayMetaCodec.write(JsonObject(emptyMap()), meta)
            assertEquals(meta, UnderlayMetaCodec.read(written))
            assertEquals(JsonObject(emptyMap()), UnderlayMetaCodec.write(written, null))
        }
        assertEquals(null, UnderlayMetaCodec.read(JsonObject(emptyMap())))
    }

    @Test
    fun note_should_BeOutOfDate_When_ThePhotoWasRescaledAfterCalibrating() {
        val ref = CalibrationRef(ImagePx(0.0, 0.0), ImagePx(1_000.0, 0.0), 576L)
        val calibrated = UnderlayCalibration.solve(placement(0.0, 0.0, 1_000.0, 0.0), widthPx, heightPx, listOf(ref), level = false).getOrNull()
        assertNotNull(calibrated)
        val layer = Layer(UnderlayIds.LAYER, UnderlayIds.LAYER_NAME)
        fun stateAt(p: UnderlayPlacement) = UnderlayState(
            ImageElement(ElementBase(UnderlayIds.ELEMENT, layerId = UnderlayIds.LAYER), SceneUnit.of(p.x).getOrNull()!!, SceneUnit.of(p.y).getOrNull()!!, SceneUnit.of(p.width).getOrNull()!!, SceneUnit.of(p.height).getOrNull()!!, p.angle, fileId = "f"),
            UnderlayMeta(widthPx, heightPx, listOf(ref)),
            layer,
        )
        assertEquals(CalibrationNote.Calibrated(576L, perspective = false), CalibrationNote.of(stateAt(calibrated.placement)))
        val resized = calibrated.placement.copy(width = calibrated.placement.width * 1.2, height = calibrated.placement.height * 1.2)
        assertEquals(CalibrationNote.OutOfDate(576L), CalibrationNote.of(stateAt(resized)))
        assertEquals(CalibrationNote.None, CalibrationNote.of(null))
    }

    @Test
    fun gesture_should_KeepEachPhotoPointUnderItsPointer_Property() = runTest {
        checkAll(
            300,
            finite(-2_000.0, 2_000.0), finite(-2_000.0, 2_000.0), finite(300.0, 3_000.0), finite(-PI, PI),
            finite(-1_000.0, 1_000.0), finite(-1_000.0, 1_000.0), finite(50.0, 600.0), finite(-PI, PI),
            finite(-1_000.0, 1_000.0), finite(-1_000.0, 1_000.0), finite(50.0, 600.0), finite(-PI, PI),
        ) { x, y, w, angle, fx, fy, fSpan, fHeading, tx, ty, tSpan, tHeading ->
            val base = placement(x, y, w, angle)
            val from = listOf(fx to fy, fx + fSpan * kotlin.math.cos(fHeading) to fy + fSpan * sin(fHeading))
            val to = listOf(tx to ty, tx + tSpan * kotlin.math.cos(tHeading) to ty + tSpan * sin(tHeading))
            val moved = UnderlayGesture.transform(base, from, to, scale = true, sizeRange = 1.0..1.0e9)
            for (i in 0..1) {
                val under = base.toImage(from[i].first, from[i].second, widthPx, heightPx)
                val (sx, sy) = moved.toScene(under, widthPx, heightPx)
                assertEquals(to[i].first, sx, 1.0e-6 * (1 + abs(to[i].first)))
                assertEquals(to[i].second, sy, 1.0e-6 * (1 + abs(to[i].second)))
            }
        }
    }

    @Test
    fun gesture_should_KeepTheSize_When_ScaleIsOff_AndOnlyMove_WithOnePointer() {
        val base = placement(0.0, 0.0, 800.0, 0.2)
        val pinched = UnderlayGesture.transform(base, listOf(0.0 to 0.0, 100.0 to 0.0), listOf(0.0 to 0.0, 300.0 to 0.0), scale = false, sizeRange = 1.0..1.0e9)
        assertEquals(base.width, pinched.width, 1.0e-9)
        val dragged = UnderlayGesture.transform(base, listOf(10.0 to 10.0), listOf(60.0 to -20.0), scale = true, sizeRange = 1.0..1.0e9)
        assertEquals(base.width, dragged.width, 1.0e-12)
        assertEquals(base.angle, dragged.angle, 1.0e-12)
        assertEquals(base.x + 50.0, dragged.x, 1.0e-9)
        assertEquals(base.y - 30.0, dragged.y, 1.0e-9)
    }
}
