// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertContentDescriptionEquals
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
 * Measurement input on a phone: the units chips cycle every length, the inline field reads typed text back and says when it rounds,
 * and the symbol keys edit the field at the caret without closing the keyboard. Images land in `build/outputs/roborazzi/units-*.png`
 * (record with `-Proborazzi.test.record=true`). jvmTest-reason: pixels.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class MeasurementUnitsHostTest {
    @get:Rule
    val rule = createComposeRule()

    private class FakeSource(val bundle: ProjectBundle) : TechnicalDataSource {
        override suspend fun load(): ProjectBundle = bundle
        override suspend fun save(bundle: ProjectBundle): Boolean = true
    }

    private val rect = filledRectEl("r1", 60, 200, 160, 90)

    /** One dp per scene unit at xxhdpi (density 3), so a 160-unit shape is 160 dp wide. */
    private fun view(panX: Double = 0.0, panY: Double = 0.0, zoom: Double = 3.0) = SketchViewport.of(zoom, panX, panY)

    private fun mount(elements: List<SceneElement>, select: List<String> = listOf("r1"), technical: Boolean = true, viewport: SketchViewport = view(), keyboardDp: Float? = null): SketchEditorSession {
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
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            session.extras?.pills?.imeOverlapOverrideDp = keyboardDp
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

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/units-$name.png")

    private fun pill(kind: PillKind) = rule.onNodeWithTag(DimensionPillTags.pill(kind))

    private fun openWidth(keyboardDp: Float = 300f): SketchEditorSession {
        val session = mount(listOf(rect), keyboardDp = keyboardDp)
        pill(PillKind.Width).performClick()
        rule.waitForIdle()
        return session
    }

    private fun field() = rule.onNodeWithTag(DimensionPillTags.FIELD)

    @Test
    fun chipRowUnitsChip_should_CycleThePillsThroughAllThreeFormats() {
        mount(listOf(rect))
        pill(PillKind.Height).assertContentDescriptionContains("5 5/8", substring = true)
        rule.onNodeWithTag(LengthUnitTags.CHIP_ROW).assertIsDisplayed().assertHeightIsAtLeast(44.dp).performClick()
        rule.waitForIdle()
        shot("1-pills-inches")
        rule.onNodeWithTag(LengthUnitTags.CHIP_ROW).performClick()
        rule.waitForIdle()
        pill(PillKind.Height).assertContentDescriptionContains("5.625", substring = true)
        shot("2-pills-decimal")
    }

    @Test
    fun inlineField_should_ReadTypedTextBack_AndSayWhenItRounds() {
        openWidth()
        field().performTextReplacement("3'7 3/4")
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.READING, useUnmergedTree = true).assertTextContains("= 3' 7 3/4\" (43 3/4\")")
        shot("3-field-feet-inches")
        field().performTextReplacement("0.3")
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.ROUNDED, useUnmergedTree = true).assertTextContains("Rounded to 5/16\"", substring = true)
        shot("4-field-rounded")
    }

    @Test
    fun inlineUnitChip_should_RewriteTheTextInTheNewUnits_AndKeepTheFieldOpen() {
        openWidth()
        field().performTextReplacement("43 3/4\"")
        rule.onNodeWithTag(LengthUnitTags.INLINE_CHIP).assertHeightIsAtLeast(44.dp).performClick()
        rule.waitForIdle()
        field().assertTextContains("43 3/4\"")
        rule.onNodeWithTag(DimensionPillTags.EDITOR).assertIsDisplayed()
        rule.onNodeWithTag(LengthUnitTags.INLINE_CHIP).performClick()
        rule.waitForIdle()
        field().assertTextContains("43.75\"")
        shot("5-field-decimal")
    }

    @Test
    fun symbolKeys_should_TypeAtTheCaret_AtTheTouchFloor_AndKeepTheFieldOnScreen() {
        openWidth()
        field().performTextReplacement("3")
        rule.onNodeWithTag(LengthKeyTags.key(LengthKey.FeetMark)).assertIsDisplayed().assertHeightIsAtLeast(44.dp).assertWidthIsAtLeast(44.dp).performClick()
        rule.onNodeWithTag(LengthKeyTags.key(LengthKey.InchMark)).assertHeightIsAtLeast(44.dp).assertWidthIsAtLeast(44.dp)
        rule.waitForIdle()
        field().assertTextContains("3' ")
        field().performTextReplacement("3' 7")
        rule.onNodeWithTag(LengthKeyTags.key(LengthKey.InchMark)).performClick()
        rule.waitForIdle()
        field().assertTextContains("3' 7\"")
        rule.onNodeWithTag(LengthKeyTags.ROW).assertIsDisplayed()
        shot("6-keys-360")
    }

    @Test
    fun symbolKeys_should_BeReadableAsButtons() {
        openWidth()
        rule.onNodeWithTag(LengthKeyTags.key(LengthKey.FeetMark)).assertContentDescriptionEquals("feet mark")
        rule.onNodeWithTag(LengthKeyTags.key(LengthKey.InchMark)).assertContentDescriptionEquals("inch mark")
        rule.onNodeWithTag(LengthKeyTags.key(LengthKey.Slash)).assertContentDescriptionEquals("fraction slash")
    }

    @Test
    @Config(sdk = [29], qualifiers = "w320dp-h640dp-xxhdpi")
    fun symbolKeys_should_FitA320dpScreen_WithTheKeyboardUp() {
        openWidth(keyboardDp = 260f)
        for (key in LengthKey.entries) rule.onNodeWithTag(LengthKeyTags.key(key)).assertIsDisplayed().assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp)
        shot("7-keys-320")
    }

    @Test
    fun typingTheDisplayedValue_should_MakeAFractionalShapeExactlyThatSize() {
        val fractional = rect.copy(width = dev.stapler.stelekit.sketch.v2.scene.SceneUnit.of(160.4).getOrNull()!!)
        val session = mount(listOf(fractional))
        pill(PillKind.Width).performClick()
        rule.waitForIdle()
        field().assertTextContains("10\"")
        field().performTextReplacement("1")
        field().performTextReplacement("10\"")
        field().performImeAction()
        rule.waitForIdle()
        field().performImeAction()
        rule.waitForIdle()
        assertEquals(160.0, (session.controller.doc.scene[ElementId("r1")] as RectElement).width.value)
    }

    @Test
    fun typedRoundNumber_should_CommitExactly_ThroughTheRealField() {
        val session = openWidth()
        field().performTextReplacement("44")
        field().performImeAction()
        rule.waitForIdle()
        field().performTextReplacement("90")
        field().performImeAction()
        rule.waitForIdle()
        val after = session.controller.doc.scene[ElementId("r1")] as RectElement
        assertEquals(704.0, after.width.value)
        assertEquals(1440.0, after.height.value)
    }

    private fun chipTo(format: dev.stapler.stelekit.units.LengthFormat) {
        var guard = 0
        while (guard++ < dev.stapler.stelekit.units.LengthFormat.entries.size && !chipShows(format)) {
            rule.onNodeWithTag(LengthUnitTags.INLINE_CHIP).performClick()
            rule.waitForIdle()
        }
    }

    private fun chipShows(format: dev.stapler.stelekit.units.LengthFormat): Boolean =
        runCatching { rule.onNodeWithTag(LengthUnitTags.INLINE_CHIP).assertContentDescriptionContains("Length units: ${format.displayName}.", substring = true) }.isSuccess

    @Test
    fun metricField_should_ShowMetricKeys_ReadTheValueBack_AndSayWhenItRounds() {
        openWidth()
        chipTo(dev.stapler.stelekit.units.LengthFormat.METRIC_MM)
        rule.onAllNodesWithTag(LengthKeyTags.ROW).assertCountEquals(0)
        for (key in MeasureKey.METRIC) rule.onNodeWithTag(LengthKeyTags.key(key)).assertIsDisplayed().assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp)
        rule.onNodeWithTag(LengthKeyTags.key(MeasureKey.Millimetre)).assertContentDescriptionEquals("millimetres")
        rule.onNodeWithTag(LengthKeyTags.key(MeasureKey.Comma)).assertContentDescriptionEquals("decimal comma")
        rule.onNodeWithTag(LengthKeyTags.ADVANCE).assertIsDisplayed().assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp).assertContentDescriptionEquals("Next field")
        field().performTextReplacement("12,34")
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionPillTags.READING, useUnmergedTree = true).assertTextContains("= 12.3 mm (about 1/2\")")
        rule.onNodeWithTag(DimensionPillTags.ROUNDED, useUnmergedTree = true).assertTextContains("12,34 is finer than 0.1 mm. Rounded to 12.3 mm")
        shot("8-metric-field")
    }

    @Test
    fun fractionKeys_should_TypeAMixedNumber_AtTheTouchFloor() {
        openWidth()
        field().performTextReplacement("3")
        for (key in MeasureKey.FRACTIONS) rule.onNodeWithTag(LengthKeyTags.key(key)).assertIsDisplayed().assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp)
        rule.onNodeWithTag(LengthKeyTags.key(MeasureKey.Half)).assertContentDescriptionEquals("one half").performClick()
        rule.waitForIdle()
        field().assertTextContains("3 1/2")
        shot("9-fraction-keys")
    }

    @Test
    fun metricEntry_should_StoreTenthsOfAMillimetreExactly_StickAUnit_AndAdvanceWithTheKey() {
        val session = openWidth()
        chipTo(dev.stapler.stelekit.units.LengthFormat.METRIC_MM)
        field().performTextReplacement("1,23 cm")
        rule.onNodeWithTag(LengthKeyTags.ADVANCE).performClick()
        rule.waitForIdle()
        field().performTextReplacement("4")
        rule.onNodeWithTag(LengthKeyTags.ADVANCE).assertContentDescriptionEquals("Done, apply the size").performClick()
        rule.waitForIdle()
        val after = session.controller.doc.scene[ElementId("r1")] as RectElement
        assertEquals(dev.stapler.stelekit.units.MetricLength.toUnits(123), after.width.value)
        assertEquals(dev.stapler.stelekit.units.MetricLength.toUnits(400), after.height.value)
    }
}
