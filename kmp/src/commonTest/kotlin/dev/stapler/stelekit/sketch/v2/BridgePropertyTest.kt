// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.SheetProps
import dev.stapler.stelekit.sketch.SketchLayer
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.v2.bridge.DropReason
import dev.stapler.stelekit.sketch.v2.bridge.LiftError
import dev.stapler.stelekit.sketch.v2.bridge.ProjectionResult
import dev.stapler.stelekit.sketch.v2.bridge.V1ToV2
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.units.Inches
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BridgePropertyTest {
    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun p(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))

    private fun <L, R> arrow.core.Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun <L, R> arrow.core.Either<L, R>.left(): L = fold({ it }, { error("unexpected Right: $it") })

    private fun sceneOf(vararg primitives: SketchPrimitive): SketchScene =
        primitives.fold(SketchScene.EMPTY) { s, prim -> s.add(prim).right() }

    private val bound = SceneLimits.MAX_COORD.toLong()

    @Test
    fun lift_should_ProjectBackToTheOriginal_When_LineOnNamedLayer() {
        val line = SketchPrimitive.Line("l1", p(16, 32), p(48, 32), "Walls")
        val sheet = SheetProps(layers = listOf(SketchLayer("Walls")))
        val lifted = V1ToV2.lift(sceneOf(line), sheet).right()
        assertIs<LineElement>(lifted[ElementId("l1")])
        val projection = V2ToV1.projectScene(lifted)
        assertEquals(listOf<SketchPrimitive>(line), projection.primitives)
        assertEquals(0, projection.droppedCount)
        assertEquals(listOf(SketchLayer("Walls")), projection.layers)
    }

    @Test
    fun project_should_DropWithReason_When_RotatedOrUnsupportedKinds() {
        val base = { id: String -> ElementBase(id = ElementId(id)) }
        val layers = mapOf(dev.stapler.stelekit.sketch.v2.scene.LayerId.Default to "")
        val rotated = RectElement(base("r"), u(0), u(0), u(10), u(10), angle = 0.3)
        val ellipse = EllipseElement(base("e"), u(0), u(0), u(5), u(5))
        val point = PointElement(base("p"), u(1), u(1))
        val arc = ArcElement(base("a"), u(0), u(0), u(4), 0.0, 1.0)
        assertEquals(ProjectionResult.Dropped(DropReason.Rotated), V2ToV1.project(rotated, layers))
        assertEquals(ProjectionResult.Dropped(DropReason.UnsupportedKind), V2ToV1.project(ellipse, layers))
        assertEquals(ProjectionResult.Dropped(DropReason.UnsupportedKind), V2ToV1.project(point, layers))
        assertEquals(ProjectionResult.Dropped(DropReason.UnsupportedKind), V2ToV1.project(arc, layers))
    }

    @Test
    fun projectScene_should_ReportDroppedCount_When_MixedKinds() {
        val scene = V1ToV2.lift(sceneOf(SketchPrimitive.Line("l", p(0, 0), p(16, 0)))).right()
            .add(RectElement(ElementBase(id = ElementId("r")), u(0), u(0), u(10), u(10), angle = 0.3)).right()
            .add(EllipseElement(ElementBase(id = ElementId("e")), u(0), u(0), u(5), u(6))).right()
            .add(LineElement(ElementBase(id = ElementId("poly")), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(1), u(1)), ScenePoint(u(2), u(0))))).right()
        val projection = V2ToV1.projectScene(scene)
        assertEquals(1, projection.primitives.size)
        assertEquals(
            listOf(DropReason.Rotated, DropReason.UnsupportedKind, DropReason.NotTwoPointLine),
            projection.dropped.map { it.reason },
        )
        assertEquals(3, projection.droppedCount)
    }

    @Test
    fun project_should_RoundOnceHalfAwayFromZero_When_CoordinatesAreFractional() {
        val line = LineElement(ElementBase(id = ElementId("l")), listOf(ScenePoint(SceneUnit.of(2.5).right(), SceneUnit.of(-2.5).right()), ScenePoint(SceneUnit.of(0.49).right(), u(0))))
        val result = V2ToV1.project(line, mapOf(dev.stapler.stelekit.sketch.v2.scene.LayerId.Default to ""))
        assertEquals(ProjectionResult.Projected(SketchPrimitive.Line("l", p(3, -3), p(0, 0))), result)
    }

    @Test
    fun lift_should_PreserveZOrder_When_ManyPrimitives() {
        val prims = (0 until 20).map { SketchPrimitive.Line("id$it", p(it.toLong(), 0), p(it.toLong(), 16)) }
        val lifted = V1ToV2.lift(sceneOf(*prims.toTypedArray())).right()
        assertEquals(prims, V2ToV1.projectScene(lifted).primitives)
        val zKeys = lifted.elementsInOrder.map { it.base.zKey }
        assertEquals(zKeys.sorted(), zKeys)
    }

    @Test
    fun lift_should_ReturnTooManyElements_When_V1SceneExceedsV2Capacity() {
        var scene = SketchScene.EMPTY
        repeat(SketchScene.MAX_PRIMITIVES) { scene = scene.add(SketchPrimitive.Line("n$it", p(0, 0), p(1, 1))).right() }
        assertEquals(SketchScene.MAX_PRIMITIVES, scene.size)
        val error = V1ToV2.lift(scene).left()
        assertEquals(LiftError.TooManyElements(SketchScene.MAX_PRIMITIVES, SceneLimits.Default.maxElements), error)
    }

    @Test
    fun lift_should_ReturnTooManyLayers_When_SheetNeedsMoreThanMaxLayers() {
        val limits = SceneLimits(maxLayers = 3)
        val sheet = SheetProps(layers = listOf(SketchLayer("a"), SketchLayer("b"), SketchLayer("c")))
        assertEquals(LiftError.TooManyLayers(4, 3), V1ToV2.lift(SketchScene.EMPTY, sheet, limits).left())
        val implicit = sceneOf(SketchPrimitive.Line("l", p(0, 0), p(1, 1), "x"), SketchPrimitive.Line("m", p(0, 0), p(1, 1), "y"), SketchPrimitive.Line("n", p(0, 0), p(1, 1), "z"))
        assertEquals(LiftError.TooManyLayers(4, 3), V1ToV2.lift(implicit, SheetProps(), limits).left())
        assertTrue(V1ToV2.lift(implicit, SheetProps(), SceneLimits(maxLayers = 4)).isRight())
    }

    @Test
    fun lift_should_ReturnInvalidCoordinate_When_RectWiderThanMaxCoord() {
        val wide = SketchPrimitive.Rect("w", p(-bound, 0), p(bound, 1))
        assertIs<LiftError.InvalidCoordinate>(V1ToV2.lift(sceneOf(wide)).left())
    }

    @Test
    fun lift_should_KeepLayerVisibilityAndLock_When_SheetHasFlags() {
        val sheet = SheetProps(layers = listOf(SketchLayer("Hidden", visible = false), SketchLayer("Locked", locked = true)))
        val projection = V2ToV1.projectScene(V1ToV2.lift(SketchScene.EMPTY, sheet).right())
        assertEquals(sheet.layers, projection.layers)
    }

    @Test
    fun liftThenProject_should_BeIdentity_When_AnyIntegerSixteenths() = runTest {
        val coord = Arb.long(-bound..bound)
        val layer = Arb.element("", "Walls", "default", "v1:x")
        checkAll(coord, coord, coord, coord, layer) { x1, y1, x2, y2, layerName ->
            // Rect corners are limited by what one v2 box can hold; lines have no such limit.
            val line = SketchPrimitive.Line("l", p(x1, y1), p(x2, y2), layerName)
            val rect = SketchPrimitive.Rect("r", p(x1, y1), p(x2, y2), layerName)
            val sheet = SheetProps(layers = if (layerName.isEmpty()) emptyList() else listOf(SketchLayer(layerName)))
            val original = sceneOf(line, rect)
            val lifted = V1ToV2.lift(original, sheet)
            val tooWide = kotlin.math.abs(x2 - x1) > bound || kotlin.math.abs(y2 - y1) > bound
            if (tooWide) {
                assertIs<LiftError.InvalidCoordinate>(lifted.left())
            } else {
                val projection = V2ToV1.projectScene(lifted.right())
                assertEquals(original.primitives, projection.primitives)
                assertEquals(0, projection.droppedCount)
            }
        }
    }
}
