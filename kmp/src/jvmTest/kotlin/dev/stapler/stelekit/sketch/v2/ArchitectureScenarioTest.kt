// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task 6.2.4a, requirements metric 2 (a): the 15-element architecture diagram (9 labelled boxes, 4 bound arrows, 1 frame, 1 group)
 * authored by an event script through the real tools and commands, in at most 60 steps of the one counting convention.
 * The golden is the canonical sheet JSON of the result; the same script runs under Robolectric in `ArchitectureScenarioAndroidTest`.
 */
class ArchitectureScenarioTest {
    private fun sceneJson(diagram: ArchitectureDiagram): String = ArchitectureScenario.sceneJson(diagram.editor)

    @Test
    fun architectureScenario_should_MatchGoldenScene_When_15ElementEventScriptRun() {
        val diagram = ArchitectureScenario.run()
        val scene = diagram.editor.scene
        assertEquals(9, scene.elementsInOrder.count { it is RectElement })
        assertEquals(9, scene.elementsInOrder.count { it is TextElement })
        assertEquals(4, scene.elementsInOrder.count { it is ArrowElement })
        assertEquals(1, scene.elementsInOrder.count { it is FrameElement })
        assertTrue(diagram.arrows.all { id -> (scene[id] as ArrowElement).let { it.startBinding != null && it.endBinding != null } }, "all four arrows are bound at both ends")
        assertEquals(1, scene.elementsInOrder.flatMap { it.base.groupIds }.toSet().size, "one group")
        assertTrue(reconcile(scene).isClean)
        assertEquals(ArchitectureScenarioGolden.SCENE_JSON, sceneJson(diagram))
    }

    @Test
    fun architectureScenario_should_MatchGoldenDrawList_InCleanAndSketchy() {
        val scene = ArchitectureScenario.run().editor.scene
        assertEquals(ArchitectureScenarioGolden.CLEAN_DRAW_LIST_DIGEST, ArchitectureScenario.drawListDigest(scene, RenderStyle.Clean))
        assertEquals(ArchitectureScenarioGolden.SKETCHY_DRAW_LIST_DIGEST, ArchitectureScenario.drawListDigest(scene, RenderStyle.Sketchy))
    }

    @Test
    fun architectureScenario_should_MoveArrowEndpoints_When_BoxMoved() {
        val diagram = ArchitectureScenario.run()
        val editor = diagram.editor
        val arrowId = diagram.arrows.first()
        val before = (editor.scene[arrowId] as ArrowElement).points
        val depth = editor.history.undoDepth

        editor.move(diagram.boxes[1], dx = 0, dy = 40)

        val after = (editor.scene[arrowId] as ArrowElement).points
        assertTrue(before.last() != after.last(), "the end bound to the moved box followed it")
        assertEquals(before.first(), after.first(), "the other end stayed")
        assertEquals(depth + 1, editor.history.undoDepth, "the move and the arrow follow are one history step")
        editor.undo()
        assertEquals(before, (editor.scene[arrowId] as ArrowElement).points, "one undo restores the arrow")
    }

    @Test
    fun architectureScenario_should_RefuseNewElement_When_SceneAtCapacity() {
        val cap = 12
        val editor = ScenarioEditor(SceneLimits.Default.copy(maxElements = cap))
        ArchitectureScenario.run(editor)
        assertEquals(cap, editor.scene.size, "the scene stops at the cap")
        assertIs<CommandError.CapacityExceeded>(editor.refusal)
        assertEquals(cap, (editor.refusal as CommandError.CapacityExceeded).limit)
    }

    @Test
    fun architectureScenario_should_CompleteInAtMost60Actions_When_15ElementScriptRun() {
        val diagram = ArchitectureScenario.run()
        assertEquals(ArchitectureScenario.STEPS_EXPECTED, diagram.editor.steps, "9 boxes x 4 + 4 arrows x 3 + frame 2 + group 2")
        assertTrue(diagram.editor.steps <= ArchitectureScenario.STEP_BUDGET, "${diagram.editor.steps} steps")
    }

    @Test
    fun architectureScenario_should_BeDeterministic_When_RunTwice() {
        assertEquals(sceneJson(ArchitectureScenario.run()), sceneJson(ArchitectureScenario.run()))
    }
}
