// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.4.2 and Story 2.1.4's 64 KB bound: the Freedraw tool. */
class FreedrawToolTest {
    private fun stroke(r: ScenarioResult) = assertIs<FreedrawElement>(assertIs<AddElementCommand>(r.commands.single()).element)

    @Test
    fun stylusStroke_should_StoreOnePressurePerPoint_And_NotSimulate() {
        val r = runScenario(FreedrawTool()) {
            down(0, 0, kind = PointerKind.Stylus, pressure = 0.2)
            move(40, 30, kind = PointerKind.Stylus, pressure = 0.6)
            up(80, 0, kind = PointerKind.Stylus, pressure = 0.9)
        }
        val s = stroke(r)
        assertEquals(3, s.points.size)
        assertEquals(listOf(0.2, 0.6, 0.9), s.pressures)
        assertEquals(false, s.simulatePressure)
    }

    @Test
    fun mouseStroke_should_SimulatePressure() {
        val s = stroke(runScenario(FreedrawTool()) { down(0, 0); move(40, 30); up(80, 0) })
        assertTrue(s.simulatePressure)
        assertTrue(s.pressures.isEmpty())
    }

    @Test
    fun anyPointWithoutAReading_should_MakeTheWholeStrokeSimulated() {
        val s = stroke(
            runScenario(FreedrawTool()) {
                down(0, 0, kind = PointerKind.Stylus, pressure = 0.2)
                move(40, 30, kind = PointerKind.Stylus)
                up(80, 0, kind = PointerKind.Stylus, pressure = 0.9)
            },
        )
        assertTrue(s.simulatePressure)
        assertTrue(s.pressures.isEmpty())
    }

    @Test
    fun dot_should_CommitOnePointStroke() {
        val s = stroke(runScenario(FreedrawTool()) { down(7, 9); up(7, 9) })
        assertEquals(1, s.points.size)
    }

    @Test
    fun stroke_should_ShowRawPreviewWhileDrawing_AndCommitNothingUntilRelease() {
        val r = runScenario(FreedrawTool()) { down(0, 0); move(10, 10); move(20, 5) }
        assertTrue(r.commands.isEmpty())
        assertEquals(3, assertNotNull(r.previews.last()).elements.filterIsInstance<FreedrawElement>().single().points.size)
    }

    @Test
    fun cancelRules_should_AbandonTheStrokeWithNoCommand() {
        val esc = runScenario(FreedrawTool()) { down(0, 0); move(10, 10); escape(); up(20, 20) }
        val second = runScenario(FreedrawTool()) { down(0, 0); move(10, 10); down(5, 5, pointer = 1) }
        val platform = runScenario(FreedrawTool()) { down(0, 0); move(10, 10); cancel() }
        for (r in listOf(esc, second, platform)) {
            assertTrue(r.commands.isEmpty())
            assertNull(r.previews.last())
        }
    }

    @Test
    fun twentyThousandPointStroke_should_StayUnderTheCapAndUnder64Kb() {
        val limits = SceneLimits.Default
        val n = 20_000
        val ctx = testToolContext()
        var tool: Tool = FreedrawTool()
        var committed: ToolCommand? = null
        fun feed(type: InputType, x: Int, y: Int, pressure: Double) {
            val e = InputEvent(type, sp(x, y), pressure, PointerKind.Stylus)
            val result = tool.onEvent(e, ctx)
            val previewSize = (result.preview?.elements?.single() as? FreedrawElement)?.points?.size ?: 0
            assertTrue(previewSize <= limits.maxPointsPerElement + 1, "preview of $previewSize points")
            result.next?.let { tool = it }
            if (result.command != null) committed = result.command
        }
        feed(InputType.Down, 0, 0, 0.5)
        for (i in 1 until n) feed(InputType.Move, i % 4_000, (sin(i / 7.0) * 900).toInt(), 0.3 + (i % 70) / 100.0)
        feed(InputType.Up, 10, 10, 0.5)
        val s = assertIs<FreedrawElement>(assertIs<AddElementCommand>(committed).element)
        assertTrue(s.points.size <= limits.maxPointsPerElement)
        assertEquals(s.points.size, s.pressures.size)
        val bytes = SheetCodecV2.encode(SketchDocumentV2().let { doc -> AddElement(s).apply(doc).getOrNull()!!.doc }).getOrNull()!!
        assertTrue(bytes.size < SIXTY_FOUR_KB, "encoded sheet with one worst-case stroke was ${bytes.size} bytes")
    }

    @Test
    fun coordinates_should_BeQuantized_And_NeverRepeatConsecutively() {
        val s = stroke(runScenario(FreedrawTool()) { down(1, 1); move(5, 17); move(5, 17); up(30, 2) })
        val cells = s.points.map { it.x.value * FreedrawTool.DECIMAL_SCALE }
        assertTrue(cells.all { kotlin.math.abs(it - kotlin.math.round(it)) < 1e-6 })
        assertEquals(FreedrawTool.QUANTUM, 1.0 / FreedrawTool.DECIMAL_SCALE)
        assertTrue(s.points.zipWithNext().none { (p, q) -> p == q })
    }

    private companion object {
        const val SIXTY_FOUR_KB = 64 * 1024
    }
}
