// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.sketch.v2.tools.su
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.units.Rational
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The dimension pills of a technical sheet in the real host, at phone size: they show for one selected shape, a tap opens the
 * inline field, Enter walks Width to Height and commits one undo step, a bad length says why and keeps the old size. Images
 * land in `build/outputs/roborazzi/pills-*.png` (record with `-Proborazzi.test.record=true`). jvmTest-reason: pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class DimensionPillsHostTest {
    @get:Rule
    val rule = createComposeRule()

    private class FakeSource(val bundle: ProjectBundle) : TechnicalDataSource {
        override suspend fun load(): ProjectBundle = bundle
        override suspend fun save(bundle: ProjectBundle): Boolean = true
    }

    private val rect = filledRectEl("r1", 60, 200, 160, 90)
    private val circle = EllipseElement(ElementBase(ElementId("c1")), su(140), su(300), su(50), su(50))
    private val line = LineElement(ElementBase(ElementId("l1")), listOf(sp(60, 300), sp(220, 400)))

    /** One dp per scene unit at xxhdpi (density 3), so a 160-unit shape is 160 dp wide. */
    private fun view(panX: Double = 0.0, panY: Double = 0.0, zoom: Double = 3.0) = SketchViewport.of(zoom, panX, panY)

    private fun mount(elements: List<SceneElement>, select: List<String> = listOf("r1"), technical: Boolean = true, viewport: SketchViewport = view()): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val deps = V2EditorDeps(repos = rig.repos, store = { rig.store }, leases = null, settings = rig.deps.settings, graphId = { V2EditorRig.GRAPH }, touchPresent = true)
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        val services = if (technical) HostServices(technical = FakeSource(bundleOf(sheet))) else HostServices()
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, sheet, services)
        if (technical) rule.runOnUiThread { session.controller.setSheetKind(SheetKind.Technical) }
        rule.runOnUiThread { elements.forEach { session.controller.submit(AddElement(it)) } }
        rule.setContent { MaterialTheme(colorScheme = lightColorScheme()) { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) } }
        rule.waitForIdle()
        rule.runOnUiThread {
            session.controller.ui.viewport = viewport
            session.controller.tools.select(select.map(::ElementId))
        }
        rule.waitForIdle()
        return session
    }

    private fun bundleOf(sheet: SheetUuid): ProjectBundle {
        val project = ProjectFactory.create("Kitchen", "proj-abc123", ProjectFactory.newUuid(), 1L)
        val width = SketchDimension("d1", project.uuid, "width", "length", Rational(32), null, "user")
        return ProjectBundle(project, listOf(SketchSheet(sheet, project.uuid, "plan", "top", "in", 16)), emptyList(), listOf(width))
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/pills-$name.png")

    private fun SketchEditorSession.rect(): RectElement = assertIs(controller.doc.scene[ElementId("r1")])

    private fun pill(kind: PillKind) = rule.onNodeWithTag(DimensionPillTags.pill(kind))

    @Test
    fun selectedRect_should_ShowWidthAndHeightPills_AtTheTouchFloor() {
        mount(listOf(rect))
        pill(PillKind.Width).assertIsDisplayed().assertHeightIsAtLeast(44.dp).assertWidthIsAtLeast(44.dp)
        pill(PillKind.Height).assertIsDisplayed().assertHeightIsAtLeast(44.dp)
        shot("1-rect")
    }

    @Test
    fun pills_should_BeAbsent_When_TheSheetIsNotTechnical_Nothing_OrManyAreSelected_OrPillsAreOff() {
        mount(listOf(rect), technical = false)
        rule.onAllNodesWithTag(DimensionPillTags.pill(PillKind.Width)).assertCountEquals(0)
    }

    @Test
    fun pills_should_BeAbsent_When_NothingIsSelected() {
        mount(listOf(rect), select = emptyList())
        rule.onAllNodesWithTag(DimensionPillTags.pill(PillKind.Width)).assertCountEquals(0)
    }

    @Test
    fun pills_should_BeAbsent_When_TwoShapesAreSelected() {
        mount(listOf(rect, circle), select = listOf("r1", "c1"))
        rule.onAllNodesWithTag(DimensionPillTags.pill(PillKind.Width)).assertCountEquals(0)
        rule.onAllNodesWithTag(DimensionPillTags.pill(PillKind.Diameter)).assertCountEquals(0)
    }

    @Test
    fun dimensionsChip_should_HideAndShowThePills_AndSitInTheChipRowWithTheTechnicalShortcuts() {
        mount(listOf(rect))
        rule.onNodeWithTag(DimensionPillTags.CHIP_CONSTRAINTS).assertIsDisplayed().assertHeightIsAtLeast(44.dp)
        rule.onNodeWithTag(DimensionPillTags.CHIP_TECHNICAL).assertExists()
        rule.onNodeWithTag(DimensionPillTags.CHIP_DIMENSIONS).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(DimensionPillTags.pill(PillKind.Width)).assertCountEquals(0)
        shot("7-off")
        rule.onNodeWithTag(DimensionPillTags.CHIP_DIMENSIONS).performClick()
        rule.waitForIdle()
        pill(PillKind.Width).assertIsDisplayed()
    }

    @Test
    fun theConstraintsChip_should_OpenTheConstraintsPanel() {
        val session = mount(listOf(rect))
        rule.onNodeWithTag(DimensionPillTags.CHIP_CONSTRAINTS).performClick()
        rule.waitForIdle()
        assertEquals(PanelTab.Constraints, session.controller.ui.panelTab)
    }

    @Test
    fun theTechnicalDataChip_should_OpenTheTechnicalPanel_AndBeReachableByScrollingTheRow() {
        val session = mount(listOf(rect))
        rule.onNodeWithTag(DimensionPillTags.CHIP_TECHNICAL).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(PanelTab.Technical, session.controller.ui.panelTab)
    }

    @Test
    fun chips_should_BeAbsent_OnADiagramSheet() {
        mount(listOf(rect), technical = false)
        rule.onAllNodesWithTag(DimensionPillTags.CHIP_DIMENSIONS).assertCountEquals(0)
    }

    @Test
    fun tappingAPill_should_OpenTheFieldWithTheCurrentValue_AndEnterWalksWidthToHeightAsOneUndoStep() {
        val session = mount(listOf(rect))
        pill(PillKind.Width).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.FIELD).assertIsDisplayed()
        shot("2-field")
        rule.onNodeWithTag(DimensionPillTags.FIELD).performTextReplacement("12 3/16\"")
        rule.onNodeWithTag(DimensionPillTags.FIELD).performImeAction()
        rule.waitForIdle()
        assertEquals(160.0, session.rect().width.value, "Width alone does not commit; the edit lands once, on the last field")
        rule.onNodeWithTag(DimensionPillTags.FIELD).performTextReplacement("6\"")
        shot("3-height")
        rule.onNodeWithTag(DimensionPillTags.FIELD).performImeAction()
        rule.waitForIdle()
        val resized = session.rect()
        assertEquals(listOf(60.0, 200.0, 195.0, 96.0), listOf(resized.x.value, resized.y.value, resized.width.value, resized.height.value))
        rule.onAllNodesWithTag(DimensionPillTags.EDITOR).assertCountEquals(0)
        rule.runOnUiThread { session.controller.undo() }
        rule.waitForIdle()
        assertEquals(listOf(160.0, 90.0), listOf(session.rect().width.value, session.rect().height.value), "one undo restores both sides")
    }

    @Test
    fun anInvalidLength_should_ShowTheParsersMessage_AndKeepTheOldSize() {
        val session = mount(listOf(rect))
        pill(PillKind.Height).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.FIELD).performTextReplacement("tall")
        rule.onNodeWithTag(DimensionPillTags.FIELD).performImeAction()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.ERROR, useUnmergedTree = true).assertIsDisplayed()
        shot("4-error")
        assertEquals(90.0, session.rect().height.value)
    }

    @Test
    fun theTypedValue_should_NotChange_When_TheViewIsPannedAfterwards() {
        val session = mount(listOf(rect))
        pill(PillKind.Height).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.FIELD).performTextReplacement("10\"")
        rule.onNodeWithTag(DimensionPillTags.FIELD).performImeAction()
        rule.waitForIdle()
        rule.runOnUiThread { session.controller.ui.viewport = view(panX = 40.0, panY = -30.0, zoom = 2.0) }
        rule.waitForIdle()
        assertEquals(160.0, session.rect().height.value)
        assertEquals(160.0, session.rect().width.value)
    }

    @Test
    fun anOutsideTap_should_CancelTheEdit_AndLeaveTheShapeAlone() {
        val session = mount(listOf(rect))
        pill(PillKind.Width).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.FIELD).performTextReplacement("40\"")
        rule.onNodeWithTag(DimensionPillTags.SCRIM).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(DimensionPillTags.EDITOR).assertCountEquals(0)
        assertEquals(160.0, session.rect().width.value)
    }

    @Test
    fun theEdit_should_Close_When_TheShapeChangesUnderIt() {
        val session = mount(listOf(rect))
        pill(PillKind.Width).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.EDITOR).assertIsDisplayed()
        rule.runOnUiThread { session.controller.submit(dev.stapler.stelekit.sketch.v2.commands.MoveElements(listOf(ElementId("r1")), 10.0, 0.0)) }
        rule.waitForIdle()
        rule.onAllNodesWithTag(DimensionPillTags.EDITOR).assertCountEquals(0)
    }

    @Test
    fun aLockedShape_should_SayWhyInsteadOfOfferingAField() {
        val session = mount(listOf(rect))
        rule.runOnUiThread { session.controller.submit(LockElements(listOf(ElementId("r1")), true)) }
        rule.waitForIdle()
        pill(PillKind.Width).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.LOCKED).assertIsDisplayed()
        rule.onAllNodesWithTag(DimensionPillTags.FIELD).assertCountEquals(0)
        shot("8-locked")
    }

    @Test
    fun aCircle_should_ShowOneDiameterPill_AndTypingResizesItKeepingTheTopLeft() {
        val session = mount(listOf(circle), select = listOf("c1"))
        rule.onAllNodesWithTag(DimensionPillTags.pill(PillKind.Width)).assertCountEquals(0)
        pill(PillKind.Diameter).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.FIELD).performTextReplacement("8\"")
        rule.onNodeWithTag(DimensionPillTags.FIELD).performImeAction()
        rule.waitForIdle()
        val e = assertIs<EllipseElement>(session.controller.doc.scene[ElementId("c1")])
        assertEquals(64.0, e.rx.value)
        assertEquals(90.0, e.cx.value - e.rx.value)
        shot("5-circle")
    }

    @Test
    fun aLine_should_ShowALengthPill() {
        val session = mount(listOf(line), select = listOf("l1"))
        pill(PillKind.Length).assertIsDisplayed()
        pill(PillKind.Length).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.FIELD).performTextReplacement("20\"")
        rule.onNodeWithTag(DimensionPillTags.FIELD).performImeAction()
        rule.waitForIdle()
        val scaled = assertIs<LineElement>(session.controller.doc.scene[ElementId("l1")])
        val (a, b) = scaled.points
        assertTrue(kotlin.math.abs(kotlin.math.hypot(b.x.value - a.x.value, b.y.value - a.y.value) - 320.0) < 1.0)
        shot("6-line")
    }

    @Test
    fun aSmallShape_should_KeepBothPillsReachableAndApart() {
        mount(listOf(filledRectEl("r1", 150, 300, 16, 16)))
        pill(PillKind.Width).assertIsDisplayed()
        pill(PillKind.Height).assertIsDisplayed()
        shot("9-small")
    }

    @Test
    fun aShapeAtTheScreenEdge_should_FlipItsPillsInsideTheCanvas() {
        mount(listOf(filledRectEl("r1", 190, 10, 160, 90)))
        pill(PillKind.Width).assertIsDisplayed()
        pill(PillKind.Height).assertIsDisplayed()
        shot("10-edge")
    }

    @Test
    fun aZoomedShape_should_KeepItsPillsOnScreen() {
        mount(listOf(rect), viewport = view(panX = -300.0, panY = -400.0, zoom = 6.0))
        pill(PillKind.Width).assertIsDisplayed()
        shot("11-zoomed")
    }

    @Test
    fun aDarkOrNarrowPhone_should_StillFitThePills() {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-xhdpi")
        mount(listOf(rect), viewport = view(zoom = 2.0))
        pill(PillKind.Width).assertIsDisplayed()
        shot("12-narrow")
    }
}
