// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.TextAutoSize
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
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
 * Text auto-size, corner scaling and the font-size controls on a 360 x 780 dp phone, measured with a real Compose text measurer
 * (the bundled fonts are not loaded under Robolectric, so the platform default family stands in for all three).
 * Images land in `build/outputs/roborazzi/textsize-<scenario>.png` (record with `-Proborazzi.test.record=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class TextAutosizeRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val textId = ElementId("t1")
    private val family = SketchFontFamilies(FontFamily.Default, FontFamily.Default, FontFamily.Default)

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/textsize-$name.png")

    private fun mount(raw: String = "Door opening", size: Double = 20.0): SketchEditorSession {
        RuntimeEnvironment.setQualifiers(PHONE)
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), Density(1f, 1f), LayoutDirection.Ltr)
        val metrics = ComposeTextMetricsProvider(measurer, mutableStateOf(SketchFontState.Loaded(family)))
        val deps = V2EditorDeps(repos = rig.repos, store = { rig.store }, leases = null, settings = rig.deps.settings, graphId = { V2EditorRig.GRAPH }, touchPresent = true)
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, assertNotNull(opened.sheet), HostServices())
        val fit = assertNotNull(TextAutoSize.fit(raw, TextStyle(1, size), TextElement.DEFAULT_LINE_HEIGHT, null, metrics))
        val text = TextElement(
            ElementBase(textId), SceneUnit.ofInt(60), SceneUnit.ofInt(120), SceneUnit.of(fit.width).getOrNull()!!, SceneUnit.of(fit.height).getOrNull()!!,
            text = fit.text, originalText = raw, fontSize = size,
        )
        rule.runOnUiThread { session.controller.submit(AddElement(text)) }
        rule.setContent {
            val tm = rememberTextMeasurer()
            val resources = remember(tm) { PainterResources(tm, { FontFamily.Default }) }
            MaterialTheme(colorScheme = lightColorScheme()) { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, resources), onBack = {}) }
        }
        rule.waitForIdle()
        // The reporting phone ran at 215% with Finger drawing on; one finger on a handle then drags it instead of panning.
        rule.runOnUiThread {
            session.controller.ui.fingerDrawing = true
            session.controller.ui.viewport = session.controller.ui.viewport.zoomTo(ScreenPx(0.0, 0.0), ZOOM)
            session.controller.tools.select(listOf(textId))
        }
        rule.waitForIdle()
        return session
    }

    private fun text(session: SketchEditorSession) = assertIs<TextElement>(session.controller.doc.scene[textId])

    @Test
    fun typedText_isBoxedByItsGlyphs() {
        val session = mount()
        shot("1-autosized-selected")
        val t = text(session)
        assertTrue(t.width.value > 0 && t.height.value > 0)
        assertEquals("Door opening", t.originalText)
    }

    @Test
    fun largerChip_stepsTheSize_AndTheBoxFollows() {
        val session = mount()
        val before = text(session)
        rule.onNodeWithTag(TextSizeTags.LARGER).performClick()
        rule.waitForIdle()
        val after = text(session)
        assertEquals(24.0, after.fontSize)
        assertEquals(before.width.value * 24.0 / 20.0, after.width.value, before.width.value * 0.1)
        assertEquals(before.x, after.x)
        shot("2-larger-chip")
        rule.onNodeWithTag(TextSizeTags.SMALLER).performClick()
        rule.waitForIdle()
        assertEquals(20.0, text(session).fontSize)
    }

    @Test
    fun cornerDrag_scalesTheFont_AndShowsItsSize() {
        val session = mount()
        val before = text(session)
        val viewport = session.controller.ui.viewport
        val corner = viewport.toScreen(ScenePoint(SceneUnit.of(before.x.value + before.width.value).getOrNull()!!, SceneUnit.of(before.y.value + before.height.value).getOrNull()!!))
        rule.onNodeWithTag(HostTags.CANVAS_INPUT).performTouchInput {
            down(Offset(corner.x.toFloat(), corner.y.toFloat()))
            moveTo(Offset(corner.x.toFloat() + DRAG_PX, corner.y.toFloat() + DRAG_PX / 4))
            moveTo(Offset(corner.x.toFloat() + 2 * DRAG_PX, corner.y.toFloat() + DRAG_PX / 2))
        }
        rule.waitForIdle()
        shot("3-corner-drag")
        rule.onNodeWithTag(TextSizeTags.BADGE).assertExists()
        rule.onNodeWithTag(HostTags.CANVAS_INPUT).performTouchInput { up() }
        rule.waitForIdle()
        val after = text(session)
        assertTrue(after.fontSize > before.fontSize, "dragging a corner outward grows the font: ${before.fontSize} -> ${after.fontSize}")
        assertEquals(before.x, after.x)
        assertEquals(before.y, after.y)
        assertEquals(before.width.value / before.height.value, after.width.value / after.height.value, 1e-6)
        shot("4-after-corner-drag")
    }

    @Test
    fun stylePanel_sizeField_appliesOnDone_AndRefusesJunk() {
        val session = mount()
        rule.runOnUiThread { session.controller.ui.panelTab = PanelTab.Style }
        rule.waitForIdle()
        rule.onNodeWithText("Expand").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TextSizeTags.FIELD).performScrollTo()
        rule.waitForIdle()
        shot("5-style-panel")
        rule.onNodeWithTag(TextSizeTags.FIELD).performTextReplacement("abc")
        rule.onNodeWithTag(TextSizeTags.FIELD).performImeAction()
        rule.waitForIdle()
        rule.onNodeWithTag(TextSizeTags.ERROR).assertExists()
        assertEquals(20.0, text(session).fontSize)
        rule.onNodeWithTag(TextSizeTags.FIELD).performTextReplacement("32")
        rule.onNodeWithTag(TextSizeTags.FIELD).performImeAction()
        rule.waitForIdle()
        assertEquals(32.0, text(session).fontSize)
        shot("6-style-panel-after")
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun stylePanel_sizeField_appliesWhenFocusLeaves_NotOnlyOnDone() {
        val session = mount()
        rule.runOnUiThread { session.controller.ui.panelTab = PanelTab.Style }
        rule.waitForIdle()
        rule.onNodeWithText("Expand").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TextSizeTags.FIELD).performScrollTo()
        rule.onNodeWithTag(TextSizeTags.FIELD).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.onNodeWithTag(TextSizeTags.FIELD).performTextReplacement("30")
        rule.waitForIdle()
        rule.onNodeWithTag(TextSizeTags.FIELD).assertIsFocused()
        assertEquals(20.0, text(session).fontSize, "typing alone applies nothing")
        rule.onNodeWithTag(TextSizeTags.FIELD).performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        rule.onNodeWithTag(TextSizeTags.FIELD).assertIsNotFocused()
        assertEquals(30.0, text(session).fontSize, "moving focus away applied the typed size")
    }

    @Test
    fun theHint_sitsOnAPanel_overDrawnContent() {
        val session = mount()
        rule.runOnUiThread { session.controller.tools.select(emptyList()) }
        rule.waitForIdle()
        shot("7-hint-over-content")
        rule.onNodeWithTag(ShellTags.HINT).assertExists()
    }

    private companion object {
        const val PHONE = "w360dp-h780dp-xxhdpi"
        const val DRAG_PX = 120f
        const val ZOOM = 2.15
    }
}
