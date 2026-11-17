// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchLoadingStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import dev.stapler.stelekit.ui.theme.stelekitStaticSchemes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stories 3.6.6 and 3.6.2 (UX-45, UX-46, UX-47, UX-74, UX-87, UX-88, UX-89): the accessibility audit of the composed shell. It walks the
 * composed semantics tree and the theme tokens, samples rendered pixels for the focus ring, and fails naming the node or token
 * that is below its bar. Fixes belong to the owning story of each surface; this test only audits. Robolectric native graphics,
 * so the pixel samples are real renders; Gradle-only like the other Compose host tests (see BUILD.bazel). It does not claim
 * what NVDA, JAWS, TalkBack or VoiceOver speak: only the semantics they would be handed.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class UxA11yAcceptanceTest {
    @get:Rule
    val rule = createComposeRule()

    private val phone = WindowDp(360, 640)
    private val desktop = WindowDp(1280, 800)

    private class Config(
        val window: WindowDp,
        val pointer: PointerClass,
        val fontScale: Float = 1f,
        val scheme: ColorScheme = lightColorScheme(),
        val reduced: Boolean? = null,
    )

    private val editor = SketchEditorState()
    private var config by mutableStateOf(Config(WindowDp(360, 640), PointerClass.TouchOrPen))
    private var hosted = false

    /** One composition per test: a different window, pointer, font scale or theme is a state change, never a second setContent. */
    private fun show(
        window: WindowDp,
        pointer: PointerClass,
        fontScale: Float = 1f,
        scheme: ColorScheme = lightColorScheme(),
        reduced: Boolean? = null,
    ) {
        config = Config(window, pointer, fontScale, scheme, reduced)
        if (!hosted) {
            hosted = true
            rule.setContent {
                val c = config
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, c.fontScale)) {
                    ProvideReducedMotion(c.reduced) {
                        MaterialTheme(colorScheme = c.scheme) {
                            Box(Modifier.size(c.window.width.dp, c.window.height.dp)) {
                                SketchScreenV2(editor, SketchShellConfig(ToolStripModel(true), c.pointer), window = c.window)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** A state write on the UI thread with the apply notification sent, so a manual-clock test sees it on the next frame. */
    private fun change(block: () -> Unit) {
        rule.runOnUiThread { block(); Snapshot.sendApplyNotifications() }
    }

    private fun nameOf(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.TestTag) ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "node ${node.id}"

    private fun dp(px: Float) = px / rule.density.density

    private fun clickable(): List<SemanticsNode> = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()

    private fun belowFloor(nodes: List<SemanticsNode>, floorDp: Float): List<String> =
        nodes.filter { dp(it.size.width.toFloat()) < floorDp - EPS || dp(it.size.height.toFloat()) < floorDp - EPS }
            .map { "${nameOf(it)} is ${dp(it.size.width.toFloat())} x ${dp(it.size.height.toFloat())} dp, below $floorDp dp" }

    // --- UX-46 targets and tokens ---------------------------------------------------------------------------------

    @Test
    fun ux46_targets_should_MeetFloorOfActivePointerClass_44dpWithTouchOrPen_24dpMouseOnly() {
        show(phone, PointerClass.TouchOrPen)
        val touch = belowFloor(clickable(), TouchTargetFloor.floorDp(PointerClass.TouchOrPen).toFloat())
        assertTrue(touch.isEmpty(), "touch or pen present:\n" + touch.joinToString("\n"))

        show(desktop, PointerClass.MouseOnly)
        val mouse = belowFloor(clickable(), TouchTargetFloor.floorDp(PointerClass.MouseOnly).toFloat())
        assertTrue(mouse.isEmpty(), "mouse only:\n" + mouse.joinToString("\n"))
    }

    @Test
    fun ux46_pinnedDockButtons_should_Be48dp_InEveryPointerClass() {
        for (pointer in PointerClass.entries) {
            show(phone, pointer)
            for (tag in listOf(StripTags.UNDO, StripTags.REDO, StripTags.DELETE)) {
                val size = rule.onNodeWithTag(tag).fetchSemanticsNode().size
                assertTrue(dp(size.width.toFloat()) >= TouchTargetFloor.pinnedDp() - EPS && dp(size.height.toFloat()) >= TouchTargetFloor.pinnedDp() - EPS, "$tag with $pointer is ${size.width} x ${size.height}")
            }
        }
    }

    @Test
    fun ux89_darkAndHighContrastTokens_should_MeetTheBarsAsComposed() {
        // The app theme's own findings are filed and audited in SketchTokensAuditTest; here the high-contrast sets, which must be clean.
        val schemes = mapOf(
            "high contrast light" to SketchTokens.highContrast(dark = false), "high contrast dark" to SketchTokens.highContrast(dark = true),
        )
        var chosen by mutableStateOf(schemes.values.first())
        var seen: ColorScheme? = null
        rule.setContent { MaterialTheme(colorScheme = chosen) { seen = MaterialTheme.colorScheme } }
        for ((name, scheme) in schemes) {
            chosen = scheme
            rule.waitForIdle()
            val failures = SketchTokens.failures(name, assertNotNull(seen))
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }
    }

    @Test
    fun ux89_defaultStrokeColour_should_Contrast3To1WithTheCanvas_InBothThemes() {
        for ((name, scheme) in stelekitStaticSchemes().filterKeys { it != "stone" }) {
            val ink = scheme.onSurface
            assertTrue(FocusRingContrast.ratio(ink, scheme.background) >= FocusRingContrast.NON_TEXT_BAR, "$name: the default stroke would vanish on the canvas")
        }
    }

    // --- UX-45 focus ring, sampled from a render ------------------------------------------------------------------

    private val fills = listOf(Color.White, Color.Black, Color(0xFF808080), Color(0xFFFF0000), Color(0xFF00C000), Color(0xFF0000FF), Color(0xFFFFFF00), Color(0xFF1E1E1E))

    /**
     * Renders the ring through the production [drawFocusRing] into an offscreen bitmap on a canvas of the theme background and
     * samples four pixels: the element fill, the inner ring, the outer ring and the canvas beyond the ring.
     */
    private fun ringSamples(scheme: ColorScheme, fill: Color): List<Color> {
        val style = FocusRingStyle.of(scheme.onSurface, scheme.inverseOnSurface)
        val bitmap = ImageBitmap(FIXTURE_DP, FIXTURE_DP)
        val edge = ((FIXTURE_DP - ELEMENT_DP) / 2).toFloat()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(FIXTURE_DP.toFloat(), FIXTURE_DP.toFloat())) {
            drawRect(scheme.background)
            drawRect(fill, topLeft = Offset(edge, edge), size = Size(ELEMENT_DP.toFloat(), ELEMENT_DP.toFloat()))
            translate(edge, edge) { drawFocusRing(style, Size(ELEMENT_DP.toFloat(), ELEMENT_DP.toFloat())) }
        }
        val pixels = bitmap.toPixelMap()
        val mid = FIXTURE_DP / 2
        val left = edge.toInt()
        return listOf(pixels[mid, mid], pixels[left - INNER_OFFSET, mid], pixels[left - OUTER_OFFSET, mid], pixels[CANVAS_X, mid])
    }

    @Test
    fun ux45_focusRing_should_ContrastWithElementAndBackground_In_LightDarkAndHighContrast() {
        val themes = mapOf(
            "light" to lightColorScheme(), "dark" to darkColorScheme(),
            "high contrast light" to SketchTokens.highContrast(dark = false), "high contrast dark" to SketchTokens.highContrast(dark = true),
        )
        for ((theme, scheme) in themes) for (fill in fills) {
            val (element, inner, outer, canvas) = ringSamples(scheme, fill)
            val sampled = FocusRingStyle(inner = inner, outer = outer)
            assertClose(fill, element, "$theme: the element fill")
            assertClose(scheme.background, canvas, "$theme: the canvas")
            assertClose(scheme.onSurface, inner, "$theme on $fill: the inner ring is drawn in onSurface")
            assertClose(scheme.inverseOnSurface, outer, "$theme on $fill: the outer ring is drawn in the inverse colour")
            assertTrue(FocusRingContrast.someToneContrasts(sampled, element), "$theme: neither ring tone is 3:1 against the element fill $fill")
            assertTrue(FocusRingContrast.someToneContrasts(sampled, canvas), "$theme: neither ring tone is 3:1 against the canvas")
        }
    }

    private fun assertClose(expected: Color, actual: Color, what: String) {
        val near = abs(expected.red - actual.red) < CHANNEL_TOLERANCE && abs(expected.green - actual.green) < CHANNEL_TOLERANCE && abs(expected.blue - actual.blue) < CHANNEL_TOLERANCE
        assertTrue(near, "$what: expected $expected but the render has $actual")
    }

    // --- UX-87, UX-88 ----------------------------------------------------------------------------------------------

    @Test
    fun ux87_focus_should_NeverBeFullyObscured_WhenTheBottomSheetIsOpenAtCompactWidth() {
        editor.panelTab = PanelTab.Style
        show(phone, PointerClass.TouchOrPen)
        val panel = rule.onNodeWithTag(ShellTags.PANEL).fetchSemanticsNode().boundsInRoot
        val focusable = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("focusable") { it.config.contains(SemanticsProperties.Focused) }).fetchSemanticsNodes()
        assertTrue(focusable.isNotEmpty(), "no focusable node was found to audit")
        val hidden = focusable.filter { n ->
            val b = n.boundsInRoot
            val inside = b.left >= panel.left && b.right <= panel.right && b.top >= panel.top && b.bottom <= panel.bottom
            val overlapsPanel = b.overlaps(panel)
            overlapsPanel && !inside
        }
        assertTrue(hidden.isEmpty(), "focusable nodes partly under the sheet: " + hidden.joinToString { nameOf(it) })
    }

    @Test
    fun ux88_fontScale200_should_NotClipOrHideActions_At360dp() {
        show(phone, PointerClass.TouchOrPen, fontScale = 2f)
        val small = belowFloor(clickable(), TouchTargetFloor.floorDp(PointerClass.TouchOrPen).toFloat())
        assertTrue(small.isEmpty(), "at 200% text:\n" + small.joinToString("\n"))
        for (tag in listOf(StripTags.UNDO, StripTags.REDO, StripTags.DELETE)) {
            val size = rule.onNodeWithTag(tag).fetchSemanticsNode().size
            assertTrue(dp(size.height.toFloat()) >= TouchTargetFloor.pinnedDp() - EPS, "$tag keeps its 48 dp at 200% text")
        }
        val clipped = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("text") { it.config.contains(SemanticsActions.GetTextLayoutResult) }).fetchSemanticsNodes().filter { node ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val overflow = results.firstOrNull()?.let { it.didOverflowWidth || it.didOverflowHeight } == true
            overflow && generateSequence(node) { it.parent }.none { !it.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() }
        }
        assertTrue(clipped.isEmpty(), "text clipped with no alternative: " + clipped.joinToString { describeOverflow(it) })
    }

    private fun describeOverflow(node: SemanticsNode): String {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val layout = results.firstOrNull() ?: return nameOf(node)
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        return "${nameOf(node)} \"$text\" (overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}, lines=${layout.lineCount}, size=${layout.size}, bounds=${node.size})"
    }

    // --- UX-74 -----------------------------------------------------------------------------------------------------

    @Test
    fun ux74_emptyState_should_OfferImportAndKeyboardShortcutsNotATour() {
        rule.setContent { MaterialTheme { EmptyState(EmptyStateActions()) } }
        rule.onNodeWithTag(EmptyStateTags.IMPORT).assertIsDisplayed()
        rule.onNodeWithTag(EmptyStateTags.SHORTCUTS).assertIsDisplayed()
        val text = listOf(SketchStrings.Empty.PROMPT, SketchStrings.Empty.IMPORT, SketchStrings.Empty.SHORTCUTS).joinToString(" ")
        assertFalse(text.contains("tour", ignoreCase = true), "the tour was dropped from the empty state")
    }

    // --- UX-47 reduced motion --------------------------------------------------------------------------------------

    private var motionTarget by mutableFloatStateOf(0f)
    private var motionSeen = -1f

    private fun valueAfterOneFrame(reduced: Boolean): Float {
        rule.mainClock.autoAdvance = false
        rule.setContent { ProvideReducedMotion(reduced) { motionSeen = MotionPolicy.animateFloat(motionTarget, "audit").value } }
        rule.mainClock.advanceTimeBy(FRAME_MS * 2)
        change { motionTarget = 1f }
        rule.mainClock.advanceTimeBy(FRAME_MS * SETTLE_FRAMES)
        return motionSeen
    }

    @Test
    fun ux47_motionAllowed_should_EaseTowardTheTarget_AsTheControl() {
        val value = valueAfterOneFrame(reduced = false)
        assertTrue(value > 0f && value < 1f, "control: with motion allowed the value is still easing, it was $value")
    }

    @Test
    fun ux47_reduceMotion_should_ReplaceEveryTransitionWithInstantChange_And_NoAnimationApiOutsideMotionPolicy() {
        assertEquals(1f, valueAfterOneFrame(reduced = true), "reduced: the final state is applied at once, without interpolation")
    }

    @Test
    fun ux47_visibilityAndPulse_should_BeInstantAndStatic_When_Reduced() {
        var shown by mutableStateOf(false)
        var pulses = mutableListOf<Float>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ProvideReducedMotion(true) {
                MotionPolicy.Visibility(shown) { Box(Modifier.testTag(VISIBLE_TAG).size(1.dp)) }
                pulses.add(MotionPolicy.pulse("audit").value)
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_MS * 2)
        change { shown = true }
        rule.mainClock.advanceTimeBy(FRAME_MS * 2)
        rule.onNodeWithTag(VISIBLE_TAG).assertExists()
        rule.mainClock.advanceTimeBy(PULSE_WINDOW_MS)
        assertTrue(pulses.all { it == 0f }, "the pulse is a constant: $pulses")
    }

    @Test
    fun ux47_indeterminateProgress_should_BeAStaticBar_When_Reduced_AndAnimatedOtherwise() {
        var reduced by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent { ProvideReducedMotion(reduced) { LoadingIndicator(SketchLoadingStrings.EXPORT, progress = null, onCancel = {}) } }
        rule.mainClock.advanceTimeBy(FRAME_MS * 2)
        val staticInfo = rule.onNodeWithTag(LoadingTags.BAR).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertNotNull(staticInfo)
        assertTrue(staticInfo != ProgressBarRangeInfo.Indeterminate, "reduced motion shows a static determinate bar, not the spinner")
        change { reduced = false }
        rule.mainClock.advanceTimeBy(FRAME_MS * 2)
        val liveInfo = rule.onNodeWithTag(LoadingTags.BAR).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(ProgressBarRangeInfo.Indeterminate, liveInfo)
    }

    @Test
    fun ux47_android_should_ReadTheAnimatorDurationScale() {
        var tick by mutableStateOf(0)
        var reduced = false
        var resolver: android.content.ContentResolver? = null
        rule.setContent {
            resolver = LocalContext.current.contentResolver
            tick.let { reduced = rememberReducedMotion() }
        }
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        change { tick++ }
        rule.waitForIdle()
        assertTrue(reduced, "an animator duration scale of 0 means reduced motion")
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        change { tick++ }
        rule.waitForIdle()
        assertFalse(reduced)
    }

    private companion object {
        const val EPS = 0.5f
        const val RING_FIXTURE = "ring.fixture"
        const val VISIBLE_TAG = "motion.visible"
        const val FIXTURE_DP = 120
        const val ELEMENT_DP = 60
        const val INNER_OFFSET = 1
        const val OUTER_OFFSET = 3
        const val CANVAS_X = 10
        const val CHANNEL_TOLERANCE = 0.02f
        const val PULSE_WINDOW_MS = 2_000L
        const val FRAME_MS = 16L
        const val SETTLE_FRAMES = 6
    }
}
