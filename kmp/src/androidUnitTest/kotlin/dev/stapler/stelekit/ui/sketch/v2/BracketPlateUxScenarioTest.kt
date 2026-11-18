// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 6.2.4f: the bracket plate of requirements metric 3 over the real host. Four lines and a hole are drawn with real pointer
 * input on the mounted canvas; the pins and dimensions go through the controller's own solve gate with the same pickers, chips
 * and label editor the pure `SmartDimensionPathBoundTest` drives, and the plate must reach 0 degrees of freedom in at most 34
 * counted actions with the smart-dimension chips (ux.md section 9; the counting convention is that test's). The keyboard variant
 * picks the tools with the keymap's keys. Limits: the value is entered through the label editor model, not by typing into its
 * Compose field, and Crosshair mode is not mounted in the host, so no variant draws without a pointer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalTestApi::class)
class BracketPlateUxScenarioTest {
    @get:Rule
    val rule = createComposeRule()

    private class Plate(val session: SketchEditorSession) {
        val controller get() = session.controller
        var actions = 0
        private var ids = 0
        val newId = { ConstraintId("k${++ids}") }
        val editor = DimensionEditor(technical = false)

        fun lines(): List<LineElement> = controller.doc.scene.elementsInOrder.filterIsInstance<LineElement>()

        fun circle(): EllipseElement = controller.doc.scene.elementsInOrder.filterIsInstance<EllipseElement>().single()

        fun dof(): Int? = controller.constraintState.analysis.dof

        fun at(element: ElementId, part: TargetPart) = ConstraintTarget(element, part)

        fun commit(constraint: Constraint): SolveOutcome.Done =
            assertIs<SolveOutcome.Done>(runBlocking { controller.runLabelEdit { runner -> runner.commitEdit(controller.doc, AddConstraint(constraint)) } })

        fun typeValue(text: String) {
            actions += 1
            editor.typed(text)
            assertIs<SolveOutcome.Done>(assertNotNull(runBlocking { controller.runLabelEdit { runner -> editor.submit(controller.doc, runner) } }))
        }

        fun chipDimension(selection: List<ElementId>, selectActions: Int, text: String) {
            val suggestion = assertNotNull(DimensionChips.suggestion(controller.doc, selection, null, SolverTier.Full, newId), "no chip for $selection")
            actions += selectActions + 1
            assertTrue(editor.openSuggestion(controller.doc, suggestion))
            typeValue(text)
        }
    }

    private fun mount(): Plate {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val session = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        return Plate(session).also { mounted = it }
    }

    private var mounted: Plate? = null

    private fun drag(from: Offset, to: Offset) {
        rule.onNodeWithTag(HostTags.CANVAS_INPUT).performMouseInput {
            moveTo(from)
            press()
            moveTo(Offset((from.x + to.x) / 2, (from.y + to.y) / 2))
            moveTo(to)
            release()
        }
        rule.waitForIdle()
        // Once the outline has constraints a drawn shape is committed through the async solve gate; waitForIdle does not wait for it.
        mounted?.let { runBlocking { it.controller.pendingSolves.first { n -> n == 0 } } }
    }

    private fun key(key: Key) {
        rule.onNodeWithTag(ShellTags.CANVAS).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun drawOutline(plate: Plate, chooseTool: () -> Unit, lockTool: () -> Unit) {
        chooseTool()
        lockTool()
        plate.actions += TOOL_AND_LOCK_ACTIONS
        for ((from, to) in listOf(P00 to P10, P11 to P01, P10 to P11, P01 to P00)) {
            drag(from, to)
            plate.actions += 1
        }
        assertEquals(4, plate.lines().size, "four lines were drawn with the Line tool")
    }

    private fun constrainOutline(plate: Plate) {
        // Drawn bottom, top, right, left: a drag never starts where the last line ended (the Line tool reads that as a double click).
        val l = plate.lines().map { it.id }
        val whole = { id: ElementId -> ConstraintTarget(id) }
        listOf(
            Constraint(plate.newId(), ConstraintKind.Coincident, listOf(plate.at(l[0], TargetPart.End), plate.at(l[2], TargetPart.Start))),
            Constraint(plate.newId(), ConstraintKind.Coincident, listOf(plate.at(l[2], TargetPart.End), plate.at(l[1], TargetPart.Start))),
            Constraint(plate.newId(), ConstraintKind.Coincident, listOf(plate.at(l[1], TargetPart.End), plate.at(l[3], TargetPart.Start))),
            Constraint(plate.newId(), ConstraintKind.Coincident, listOf(plate.at(l[3], TargetPart.End), plate.at(l[0], TargetPart.Start))),
            Constraint(plate.newId(), ConstraintKind.Horizontal, listOf(whole(l[0]))),
            Constraint(plate.newId(), ConstraintKind.Horizontal, listOf(whole(l[1]))),
            Constraint(plate.newId(), ConstraintKind.Vertical, listOf(whole(l[2]))),
            Constraint(plate.newId(), ConstraintKind.Vertical, listOf(whole(l[3]))),
        ).forEach(plate::commit)
        assertEquals(OUTLINE_DOF, plate.dof(), "the outline alone leaves the plate free to move, size and stretch")
    }

    private fun pinCorner(plate: Plate) {
        plate.actions += PIN_ACTIONS
        val armed = TargetPicker.arm(ConstraintKind.Fixed)
        val corner = TargetPicker.candidates(plate.controller.doc, armed, plate.controller.doc.scene.order).first { it.target == plate.at(plate.lines()[0].id, TargetPart.Start) }
        plate.commit(assertIs<PickStep.Complete>(TargetPicker.pick(plate.controller.doc, armed, corner.target, plate.newId)).constraint)
        assertEquals(PINNED_DOF, plate.dof())
    }

    private fun dimensionTheSheet(plate: Plate, chooseCircleTool: () -> Unit) {
        val l = plate.lines().map { it.id }
        plate.chipDimension(listOf(l[0]), selectActions = 1, text = "96")
        plate.chipDimension(listOf(l[3]), selectActions = 1, text = "64")
        assertEquals(0, plate.dof(), "the outline is fully constrained")
        chooseCircleTool()
        drag(HOLE_FROM, HOLE_TO)
        plate.actions += HOLE_DRAW_ACTIONS
        val hole = plate.circle().id
        plate.chipDimension(listOf(hole), selectActions = 0, text = "8")
        plate.chipDimension(listOf(hole, l[3]), selectActions = 1, text = "16")
        plate.chipDimension(listOf(hole, l[0]), selectActions = 2, text = "16")
        assertEquals(0, plate.dof(), "the sketch is fully constrained")
    }

    private fun assertPlateSolved(plate: Plate) {
        val l = plate.lines()
        fun length(line: LineElement) = hypot(line.points[1].x.value - line.points[0].x.value, line.points[1].y.value - line.points[0].y.value)
        assertEquals(PLATE_WIDTH, length(l[0]), TOLERANCE, "the bottom edge is the typed width")
        assertEquals(PLATE_HEIGHT, length(l[3]), TOLERANCE, "the left edge is the typed height")
        assertEquals(HOLE_RADIUS, plate.circle().rx.value, TOLERANCE, "the hole has the typed radius")
        assertEquals(SaveState.Saved, runBlocking { plate.controller.flush() }, "the solved plate was saved")
        assertTrue(plate.actions <= SMART_BOUND, "${plate.actions} counted actions; the bound with the smart-dimension chips is $SMART_BOUND")
    }

    @Test
    fun bracketPlate_should_ReachZeroDofWithin34Actions_When_DrawnWithThePointerAndDimensionedWithChips() {
        val plate = mount()
        drawOutline(plate, { rule.runOnUiThread { plate.controller.tools.chooseTool(dev.stapler.stelekit.sketch.v2.tools.ToolId.Line) } }, { rule.runOnUiThread { plate.controller.ui.toolLock = true } })
        constrainOutline(plate)
        pinCorner(plate)
        dimensionTheSheet(plate) { rule.runOnUiThread { plate.controller.tools.chooseTool(dev.stapler.stelekit.sketch.v2.tools.ToolId.Ellipse) } }
        assertPlateSolved(plate)
        plate.session.close()
    }

    @Test
    fun bracketPlate_should_ReachZeroDofWithin34Actions_When_TheToolsAreChosenWithTheKeys() {
        val plate = mount()
        drawOutline(plate, { key(Key.L) }, { key(Key.Q) })
        constrainOutline(plate)
        pinCorner(plate)
        dimensionTheSheet(plate) { key(Key.O) }
        assertPlateSolved(plate)
        plate.session.close()
    }

    private companion object {
        val P00 = Offset(100f, 100f)
        val P10 = Offset(196f, 100f)
        val P11 = Offset(196f, 164f)
        val P01 = Offset(100f, 164f)
        val HOLE_FROM = Offset(130f, 130f)
        val HOLE_TO = Offset(140f, 140f)
        const val TOOL_AND_LOCK_ACTIONS = 2
        const val PIN_ACTIONS = 4
        const val HOLE_DRAW_ACTIONS = 2
        const val OUTLINE_DOF = 4
        const val PINNED_DOF = 2
        const val PLATE_WIDTH = 96.0
        const val PLATE_HEIGHT = 64.0
        const val HOLE_RADIUS = 8.0
        const val TOLERANCE = 1e-3
        const val SMART_BOUND = 34
    }
}
