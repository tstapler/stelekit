// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.units.LengthFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.semantics.CustomAccessibilityAction
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E12: the gesture-free routes (Sketch outline, Add shape at...) expose the semantics a screen reader and a switch need, and the
 * controls added for them meet the 48 dp target at 1.0, 1.5 and 2.0 font scale. Asserts the semantics tree and layout, never pixels (a text field in a native-graphics Compose test never idles, so this class runs
 * in the default graphics mode).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class OutlineAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private fun scene(): SketchSceneV2 = listOf(
        filledRectEl("low", 100, 400, 192, 96),
        filledRectEl("right", 600, 0, 192, 96),
        filledRectEl("left", 0, 0, 192, 96),
    ).fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull()!! }

    private var fontScale by mutableStateOf(1f)

    private fun withFontScale(scale: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        fontScale = scale
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) { content() }
        }
    }

    /** Sets a field through its semantics action: typing would focus it, and a focused field blinks forever. */
    private fun SemanticsNodeInteraction.setText(text: String) = performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }

    private fun customActionLabels(tag: String): List<String> =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    @Test
    fun outline_should_ListRowsInReadingOrder_WithSpokenPositionAndSelectRenameActions() {
        val entries = SketchOutlineModel.build(scene(), setOf(ElementId("right")))
        var selected: String? = null
        withFontScale(1f) { SketchOutlineDialog(entries, onSelect = { selected = it.id.value }, onRename = { _, _ -> null }, onDismiss = {}) }
        val spoken = (1..3).map { rule.onNodeWithTag(OutlineTags.row(it)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single() }
        assertTrue(spoken[0]!!.startsWith("Item 1 of 3: ") && spoken[1]!!.startsWith("Item 2 of 3: ") && spoken[1]!!.endsWith(", selected"))
        assertTrue(spoken[2]!!.startsWith("Item 3 of 3: "))
        assertEquals(listOf("Select", "Rename"), customActionLabels(OutlineTags.row(1)))
        rule.onNodeWithTag(OutlineTags.row(1)).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Select" }.action()
        rule.waitForIdle()
        assertEquals("left", selected)
    }

    @Test
    fun outline_should_OfferRename_AsACustomAction_OnEveryRow() {
        val entries = SketchOutlineModel.build(scene(), emptySet())
        withFontScale(1f) { SketchOutlineDialog(entries, onSelect = {}, onRename = { _, _ -> null }, onDismiss = {}) }
        for (position in 1..entries.size) assertEquals(listOf("Select", "Rename"), customActionLabels(OutlineTags.row(position)))
    }

    // A text field inside a dialog window never idles under Robolectric, so the field blocks are composed on their own.
    @Test
    fun renameBar_should_SaveTheTypedName_And_ShowARefusalInline() {
        var saved = ""
        var error by mutableStateOf<String?>(null)
        var text by mutableStateOf("Rectangle")
        withFontScale(1f) {
            Column {
                RenameBar(text, error, onText = { text = it }, onSave = { saved = text; error = "Name taken" }, onCancel = {}, autoFocus = false)
            }
        }
        rule.onNodeWithTag(OutlineTags.RENAME_FIELD).setText("Kitchen")
        rule.onNodeWithTag(OutlineTags.RENAME_SAVE).assertHeightIsAtLeast(48.dp).performClick()
        rule.waitForIdle()
        assertEquals("Kitchen", saved)
        rule.onNodeWithText("Name taken").assertIsDisplayed()
    }

    @Test
    fun addShapeFields_should_FeedThePlan_And_NameTheRefusedFieldInline() {
        var form by mutableStateOf(ShapeAtForm())
        var error by mutableStateOf<ShapeAtError?>(null)
        withFontScale(1f) { Column { AddShapeAtFields(form, error, onForm = { form = it }, firstField = FocusRequester()) } }
        rule.onNodeWithTag(ShapeAtTags.field(ShapeAtField.X)).setText("10")
        rule.onNodeWithTag(ShapeAtTags.field(ShapeAtField.Y)).setText("20")
        rule.onNodeWithTag(ShapeAtTags.field(ShapeAtField.Width)).setText("0")
        rule.onNodeWithTag(ShapeAtTags.field(ShapeAtField.Height)).setText("40")
        rule.waitForIdle()
        error = ShapeAtModel.plan(form, false, LengthFormat.FEET_INCHES, ElementId("n"), ElementStyle()).leftOrNull()
        rule.waitForIdle()
        assertEquals(ShapeAtField.Width, error?.field)
        rule.onNodeWithText(error!!.message).assertIsDisplayed()
        rule.onNodeWithTag(ShapeAtTags.field(ShapeAtField.Width)).setText("5")
        rule.waitForIdle()
        assertNotNull(ShapeAtModel.plan(form, false, LengthFormat.FEET_INCHES, ElementId("n"), ElementStyle()).getOrNull())
    }

    @Test
    fun canvas_should_OfferOutlineAndAddShapeAsTalkBackActions() {
        val actions = listOf(CustomAccessibilityAction("Open sketch outline") { true }, CustomAccessibilityAction("Add shape at coordinates") { true })
        rule.setContent { SceneSemanticsLayer(SemanticsTree.Empty, SketchViewport.of(), canvasActions = actions) }
        val node = rule.onNode(SemanticsMatcher.keyIsDefined(CanvasApplicationRole)).fetchSemanticsNode()
        assertEquals(listOf("Open sketch outline", "Add shape at coordinates"), node.config[SemanticsActions.CustomActions].map { it.label })
    }

    @Test
    fun addShapeFields_should_Keep48dpTargets_And_Scroll_At_LargeFontScales() {
        withFontScale(1f) {
            Column(Modifier.verticalScroll(rememberScrollState())) { AddShapeAtFields(ShapeAtForm(), null, onForm = {}, firstField = FocusRequester()) }
        }
        for (scale in listOf(1.5f, 2.0f)) {
            fontScale = scale
            rule.waitForIdle()
            for (field in ShapeAtField.entries) rule.onNodeWithTag(ShapeAtTags.field(field)).performScrollTo().assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        }
    }

    @Test
    fun outlineDialog_should_KeepRowsAndCloseAt48dp_At_LargeFontScales() {
        val entries = SketchOutlineModel.build(scene(), emptySet())
        withFontScale(1f) { SketchOutlineDialog(entries, onSelect = {}, onRename = { _, _ -> null }, onDismiss = {}) }
        for (scale in listOf(1.5f, 2.0f)) {
            fontScale = scale
            rule.waitForIdle()
            rule.onNodeWithTag(OutlineTags.row(1)).assertHeightIsAtLeast(48.dp)
            rule.onNodeWithTag(OutlineTags.CLOSE).assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        }
    }

    @Test
    fun technicalChipRow_should_BeDescribed_And_AtLeast48dp_At_LargeFontScales() {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, assertNotNull(opened.sheet), HostServices())
        session.controller.setSheetKind(SheetKind.Technical)
        rule.runOnUiThread { session.controller.submit(AddElement(filledRectEl("r1", 40, 40, 400, 300))) }
        withFontScale(2.0f) { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        rule.runOnUiThread { session.controller.tools.select(listOf(ElementId("r1"))) }
        rule.waitForIdle()
        val density = rule.density
        val chips = listOf(DimensionPillTags.CHIP_DIMENSIONS, LengthUnitTags.CHIP_ROW)
        for (tag in chips) {
            val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
            assertTrue(node.size.height / density.density >= 48f - 0.6f, "$tag is ${node.size.height / density.density} dp tall")
            assertTrue(node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.isNotBlank() }, "$tag has no content description")
        }
    }
}
