// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.GroupElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.sketch.v2.tools.SelectionAware
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.runScenario
import dev.stapler.stelekit.sketch.v2.tools.sceneOfElements
import dev.stapler.stelekit.sketch.v2.tools.testToolContext
import dev.stapler.stelekit.ui.sketch.v2.ArrangeActionId
import dev.stapler.stelekit.ui.sketch.v2.ArrangeModel
import dev.stapler.stelekit.ui.sketch.v2.KeyAction
import dev.stapler.stelekit.ui.sketch.v2.KeyActionPlanner
import dev.stapler.stelekit.ui.sketch.v2.KeymapEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Stories 3.5.1 (UX-11): nested group selection and descent, ungroup one level, z-order and the arrange bar model. */
class GroupFrameTest {
    private val env = KeymapEnv(isMac = false, isWeb = false)

    /** r1 and r2 are in G1; G1 and r3 are in G2 (group ids are innermost first). */
    private fun nested(): List<SceneElement> {
        fun rect(id: String, x: Int, groups: List<String>) =
            filledRectEl(id, x, 0, 40, 40).let { it.withBase(it.base.copy(groupIds = groups.map(::GroupId))) }
        return listOf(rect("r1", 0, listOf("G1", "G2")), rect("r2", 60, listOf("G1", "G2")), rect("r3", 120, listOf("G2")))
    }

    private fun selected(tool: dev.stapler.stelekit.sketch.v2.tools.Tool) = (tool as SelectionAware).selection.map { it.value }

    @Test
    fun click_should_SelectOutermostGroup_And_DoubleClickDescendsOneLevelAtATime() {
        val ctx = testToolContext(sceneOfElements(*nested().toTypedArray()))
        val outer = runScenario(SelectTool(), ctx) { down(10, 10); up(10, 10) }
        assertEquals(listOf("r1", "r2", "r3"), selected(outer.finalTool))
        val inner = runScenario(outer.finalTool, ctx) { down(10, 10, clicks = 2); up(10, 10) }
        assertEquals(listOf("r1", "r2"), selected(inner.finalTool))
        val leaf = runScenario(inner.finalTool, ctx) { down(10, 10, clicks = 2); up(10, 10) }
        assertEquals(listOf("r1"), selected(leaf.finalTool))
        val stays = runScenario(leaf.finalTool, ctx) { down(10, 10, clicks = 2); up(10, 10) }
        assertEquals(listOf("r1"), selected(stays.finalTool))
        assertTrue(leaf.commands.isEmpty() && inner.commands.isEmpty(), "descending selects only, it commits no command")
    }

    @Test
    fun doubleClick_should_SelectWholeGroupFirst_When_NothingInTheGroupIsSelected() {
        val ctx = testToolContext(sceneOfElements(*nested().toTypedArray()))
        val r = runScenario(SelectTool(), ctx) { down(10, 10, clicks = 2); up(10, 10) }
        assertEquals(listOf("r1", "r2", "r3"), selected(r.finalTool))
    }

    @Test
    fun ungroup_should_RemoveOnlyTheOutermostLevel_When_NestedGroupsAreSelected() {
        val doc = SketchDocumentV2(sceneOfElements(*nested().toTypedArray()))
        val plan = KeyActionPlanner.plan(KeyAction.Ungroup, doc.scene, listOf(ElementId("r1"), ElementId("r2"), ElementId("r3")), W6Kit.SeqIds())
        val after = assertNotNull(plan.command).apply(doc).get().doc.scene
        assertEquals(listOf(GroupId("G1")), after[ElementId("r1")]!!.base.groupIds)
        assertEquals(emptyList(), after[ElementId("r3")]!!.base.groupIds)
    }

    @Test
    fun bringToFront_should_SortAfterTheLastKey_And_KeepTheRestInOrder() {
        val doc = W6Kit.run(SketchDocumentV2(), AddElement(W6Kit.rect("a", 0, 0)), AddElement(W6Kit.rect("b", 20, 0)), AddElement(W6Kit.rect("c", 40, 0)))
        val plan = ArrangeModel.plan(ArrangeActionId.BringToFront, doc.scene, listOf(ElementId("a")), W6Kit.SeqIds())
        val out = assertNotNull(plan.command).apply(doc).get().doc.scene
        val keyOf = { id: String -> out[ElementId(id)]!!.base.zKey }
        assertTrue(keyOf("a") > keyOf("c"), "a sorts after c")
        assertTrue(keyOf("b") < keyOf("c"))
        assertEquals(doc.scene[ElementId("b")]!!.base.zKey, keyOf("b"))
        assertEquals(doc.scene[ElementId("c")]!!.base.zKey, keyOf("c"))
    }

    @Test
    fun arrangeActions_should_BeEnabledOnlyWhenTheyWouldChangeSomething() {
        val doc = W6Kit.run(SketchDocumentV2(), AddElement(W6Kit.rect("a", 0, 0)), AddElement(W6Kit.rect("b", 20, 0)))
        fun state(selection: List<String>, scene: dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2 = doc.scene) =
            ArrangeModel.actions(scene, selection.map(::ElementId), env).associateBy { it.id }
        val none = state(emptyList())
        assertTrue(none.values.none { it.enabled })
        assertEquals(ArrangeModel.NOTHING_SELECTED, none.getValue(ArrangeActionId.Group).disabledReason)
        val one = state(listOf("b"))
        assertFalse(one.getValue(ArrangeActionId.BringToFront).enabled, "b is already in front")
        assertEquals(ArrangeModel.ALREADY_THERE, one.getValue(ArrangeActionId.BringToFront).disabledReason)
        assertTrue(one.getValue(ArrangeActionId.SendToBack).enabled)
        assertEquals(ArrangeModel.NEEDS_TWO, one.getValue(ArrangeActionId.Group).disabledReason)
        assertEquals(ArrangeModel.NOT_GROUPED, one.getValue(ArrangeActionId.Ungroup).disabledReason)
        val both = state(listOf("a", "b"))
        assertTrue(both.getValue(ArrangeActionId.Group).enabled)
        val grouped = W6Kit.run(doc, GroupElements(listOf(ElementId("a"), ElementId("b")), GroupId("g")))
        assertTrue(state(listOf("a", "b"), grouped.scene).getValue(ArrangeActionId.Ungroup).enabled)
        assertEquals("Ctrl+Shift+]", both.getValue(ArrangeActionId.BringToFront).shortcut)
        assertTrue(both.getValue(ArrangeActionId.BringToFront).description.contains("shortcut"))
    }

    @Test
    fun arrangeActions_should_SayWhy_When_EveryElementIsLocked() {
        val locked = filledRectEl("l", 0, 0, 40, 40, dev.stapler.stelekit.sketch.v2.tools.RectSpec(locked = true))
        val scene = sceneOfElements(locked)
        val actions = ArrangeModel.actions(scene, listOf(ElementId("l")), env).associateBy { it.id }
        assertEquals(ArrangeModel.ALL_LOCKED, actions.getValue(ArrangeActionId.SendToBack).disabledReason)
    }
}
