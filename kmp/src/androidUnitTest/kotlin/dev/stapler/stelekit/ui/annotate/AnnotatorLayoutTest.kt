// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PHONE = "w360dp-h640dp-xhdpi"
private const val TALL_PHONE = "w411dp-h891dp-xhdpi"
private const val MIN_TARGET_DP = 48f
private const val EDGE_TOLERANCE_DP = 0.5f

private fun linearize(channel: Float): Double {
    val c = channel.toDouble()
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun luminance(color: Color): Double = 0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)

/** WCAG contrast ratio of two opaque colours; the translucent toolbar fill is measured at full opacity like `DisabledTintContrastTest`. */
internal fun wcagContrast(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private class NamedBounds(val name: String, val rect: DpRect) {
    val width get() = rect.right.value - rect.left.value
    val height get() = rect.bottom.value - rect.top.value
    fun intersects(o: NamedBounds): Boolean =
        rect.left.value < o.rect.right.value - EDGE_TOLERANCE_DP && o.rect.left.value < rect.right.value - EDGE_TOLERANCE_DP &&
            rect.top.value < o.rect.bottom.value - EDGE_TOLERANCE_DP && o.rect.top.value < rect.bottom.value - EDGE_TOLERANCE_DP
    override fun toString() = "$name[${rect.left.value},${rect.top.value} ${width}x$height]"
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.boundsOf(matcher: SemanticsMatcher): List<NamedBounds> {
    val count = onAllNodes(matcher).fetchSemanticsNodes().size
    return (0 until count).map { i ->
        val node = onAllNodes(matcher)[i]
        val label = node.fetchSemanticsNode().config.let { cfg ->
            cfg.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: cfg.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: "node$i"
        }
        NamedBounds(label, node.getUnclippedBoundsInRoot())
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.rootBounds(): DpRect = onRoot().getUnclippedBoundsInRoot()

private fun assertAllUsable(targets: List<NamedBounds>, window: DpRect) {
    assertTrue(targets.isNotEmpty(), "no targets found")
    targets.forEach { t ->
        assertTrue(t.width >= MIN_TARGET_DP - EDGE_TOLERANCE_DP && t.height >= MIN_TARGET_DP - EDGE_TOLERANCE_DP, "target below 48 dp: $t")
        assertTrue(
            t.rect.left.value >= window.left.value - EDGE_TOLERANCE_DP && t.rect.right.value <= window.right.value + EDGE_TOLERANCE_DP &&
                t.rect.top.value >= window.top.value - EDGE_TOLERANCE_DP && t.rect.bottom.value <= window.bottom.value + EDGE_TOLERANCE_DP,
            "target clipped by the window ${window.left}..${window.right} x ${window.top}..${window.bottom}: $t",
        )
    }
    for (i in targets.indices) for (j in i + 1 until targets.size) {
        assertFalse(targets[i].intersects(targets[j]), "overlap: ${targets[i]} and ${targets[j]}")
    }
}

// Not hasVisualOverflow: Robolectric's text metrics give widths of a few px, which always reads as width overflow.
private fun assertLabelWrapsWithoutTruncation(label: String, layout: TextLayoutResult) {
    assertTrue(layout.lineCount <= 2 && !layout.didOverflowHeight, "label '$label' truncated: lines=${layout.lineCount} overflowH=${layout.didOverflowHeight}")
    assertFalse((0 until layout.lineCount).any { layout.isLineEllipsized(it) }, "label '$label' ellipsized")
}

private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    val action = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
    assertNotNull(action, "no text layout action")
    action(results)
    return results.first()
}

@Composable
private fun ToolbarHarness(
    scale: Float,
    panOn: Boolean = false,
    atEdge: (PanDirection) -> Boolean = { false },
    onPan: (PanDirection) -> Unit = {},
    currentTool: AnnotationTool = AnnotationTool.SELECT,
    zoomGroup: Boolean = true,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnnotationToolbar(
            currentTool = currentTool,
            canUndo = true,
            canRedo = false,
            displayUnit = MeasurementUnit.METERS,
            isCalibrated = true,
            onToolSelect = {},
            onUndo = {},
            onRedo = {},
            onDeleteSelect = {},
            onCalibrate = {},
            onUnitSelect = {},
            hasSelection = true,
            zoom = ToolbarZoom(
                view = { AnnotationViewTransform(scale = scale) },
                onZoomIn = {},
                onZoomOut = {},
                onFit = {},
                panOn = panOn,
                onPanToggle = {},
                onPan = onPan,
                panAtEdge = atEdge,
                showZoomGroup = zoomGroup,
            ),
            modifier = Modifier.testTag("toolbar"),
        )
    }
}

// The locale qualifier alone does not flip Compose's layout direction under Robolectric, so RTL is forced here.
@Composable
private fun Directed(rtl: Boolean, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, content = content)

private val FIRST_ROW_LABELS =listOf("Select", "Distance", "Area", "Angle", "Label", "Reference", "Calibrate")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = TALL_PHONE)
class AnnotatorLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private val img = ImageAnnotation(
        uuid = "img-layout-001", blockUuid = "blk-layout-001", pageUuid = "page-layout-001",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun launchScreen(tool: AnnotationTool = AnnotationTool.SELECT, vararg points: NormalizedPoint): AnnotationEditorViewModel {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(tool)
        points.forEach { vm.addPoint(it) }
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        return vm
    }

    private fun canvasBounds() = NamedBounds("canvas", rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION).getUnclippedBoundsInRoot())

    // ---- X-21 / Task 2.5a ----------------------------------------------------------------------------------------

    @Test
    fun depthStatus_should_BeAbsent_When_OnEstimateNull() {
        rule.setContent { DepthEstimationPanel(DepthModelUiState.Ready, false, null, onDownload = {}, onEstimate = null) }
        rule.onNodeWithText("Depth model ready — estimation coming in a future update").assertDoesNotExist()
        rule.onNodeWithText("Estimate depth (AI)").assertDoesNotExist()
    }

    @Test
    fun stripText_should_RenderInALayoutSlotAboveTheCanvas_NotOverTheImage() {
        launchScreen(AnnotationTool.DISTANCE)
        val strip = NamedBounds("strip", rule.onNodeWithText(DISTANCE_DRAG_PROMPT).getUnclippedBoundsInRoot())
        val canvas = canvasBounds()
        assertTrue(strip.rect.bottom.value <= canvas.rect.top.value + EDGE_TOLERANCE_DP, "strip $strip overlaps canvas $canvas")
        assertFalse(strip.intersects(canvas), "strip must not cover the image's top 56 dp")
    }

    // ---- X-20 / Task 2.5b zoom placement -------------------------------------------------------------------------

    @Test
    @Config(sdk = [29], qualifiers = PHONE)
    fun zoomControlsAndStatusText_should_NotIntersectPhotoBoundsAtFit_At360dp() {
        launchScreen(AnnotationTool.DISTANCE)
        val canvas = canvasBounds()
        val zoomNodes = rule.boundsOf(
            hasContentDescription(ZOOM_IN_DESCRIPTION) or hasContentDescription(ZOOM_OUT_DESCRIPTION) or
                hasContentDescription(ZOOM_FIT_DESCRIPTION) or (hasText("Zoom") and hasClickAction()),
        )
        assertTrue(zoomNodes.isNotEmpty(), "neither the zoom row nor the folded Zoom button is present")
        zoomNodes.forEach { assertFalse(it.intersects(canvas), "$it intersects $canvas") }
    }

    @Test
    @Config(sdk = [29], qualifiers = "w600dp-h900dp-xhdpi")
    fun zoomControls_should_BeAVerticalRailOutsideThePhoto_AtOrAbove600dp() {
        launchScreen()
        val canvas = canvasBounds()
        val rail = rule.boundsOf(
            hasContentDescription(ZOOM_IN_DESCRIPTION) or hasContentDescription(ZOOM_OUT_DESCRIPTION) or hasContentDescription(ZOOM_FIT_DESCRIPTION),
        )
        assertEquals(3, rail.size, "exactly one zoom group (the rail), found $rail")
        rail.forEach { assertTrue(it.rect.left.value >= canvas.rect.right.value - EDGE_TOLERANCE_DP, "$it is not right of $canvas") }
        assertAllUsable(rail, rule.rootBounds())
    }

    // ---- X-53 pan pad --------------------------------------------------------------------------------------------

    @Test
    fun panDelta_should_BeAQuarterOfTheViewportAndMoveTheImageOppositeTheArrow() {
        val geometry = ViewGeometry(1000f, 800f, 0f, 0f, 1000f, 800f)
        val zoomed = AnnotationViewTransform(scale = 2f)
        assertEquals(Offset(-250f, 0f), panDelta(PanDirection.RIGHT, 1000f, 800f))
        assertEquals(Offset(0f, -200f), panDelta(PanDirection.DOWN, 1000f, 800f))
        assertEquals(-250f, zoomed.panned(panDelta(PanDirection.RIGHT, 1000f, 800f), geometry).offsetX)
    }

    @Test
    fun panPad_should_MoveViewBy25PercentDisableAtEdgeAndHideAtOrBelowFit() {
        val moves = mutableListOf<PanDirection>()
        var scale by mutableStateOf(2.0f)
        rule.setContent {
            ToolbarHarness(scale = scale, panOn = true, atEdge = { it == PanDirection.RIGHT }, onPan = { moves += it })
        }
        rule.onNodeWithText("Pan").assertIsEnabled()
        rule.onNodeWithContentDescription("Pan left").performClick()
        assertEquals(listOf(PanDirection.LEFT), moves)
        rule.onNodeWithContentDescription("Pan right").assertIsNotEnabled()
        assertEquals(
            PAN_AT_EDGE_DESCRIPTION,
            rule.onNodeWithContentDescription("Pan right").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        assertAllUsable(rule.boundsOf(hasContentDescription("Pan left") or hasContentDescription("Pan up") or hasContentDescription("Pan down") or hasContentDescription("Pan right")), rule.rootBounds())

        scale = 1.0f
        rule.waitForIdle()
        rule.onNodeWithText("Pan").assertIsEnabled()
        rule.onNodeWithContentDescription("Pan left").assertExists()

        scale = 0.5f
        rule.waitForIdle()
        rule.onNodeWithText("Pan").assertIsNotEnabled()
        assertEquals(
            PAN_NOTHING_TO_PAN_DESCRIPTION,
            rule.onNodeWithText("Pan").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        rule.onNodeWithContentDescription("Pan left").assertDoesNotExist()
    }

    @Test
    fun panArrows_should_SitOutsideThePhotoAndCanvasExposeTalkBackPanActions() {
        launchScreen()
        rule.onNodeWithContentDescription(ZOOM_IN_DESCRIPTION).performClick()
        rule.onNodeWithContentDescription(ZOOM_IN_DESCRIPTION).performClick()
        rule.onNodeWithText("Pan").performClick()
        rule.waitForIdle()
        val canvas = canvasBounds()
        val arrows = rule.boundsOf(hasContentDescription("Pan left") or hasContentDescription("Pan up") or hasContentDescription("Pan down") or hasContentDescription("Pan right"))
        assertEquals(4, arrows.size)
        arrows.forEach { assertFalse(it.intersects(canvas), "$it is over the photo $canvas") }
        val actions = rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Pan left", "Pan up", "Pan down", "Pan right"), actions.map { it.label })
    }

    // ---- X-62 / X-68a wrap rules -----------------------------------------------------------------------------------

    private fun assertBothRowsLaidOut(rtl: Boolean) {
        rule.setContent { Directed(rtl) { ToolbarHarness(scale = 1.5f) } }
        rule.waitForIdle()
        val window = rule.rootBounds()
        val toolbar = rule.onNodeWithTag("toolbar").getUnclippedBoundsInRoot()
        assertTrue(
            toolbar.bottom.value - toolbar.top.value <= TOOLBAR_MAX_HEIGHT_FRACTION * window.bottom.value + EDGE_TOLERANCE_DP,
            "toolbar ${toolbar.bottom.value - toolbar.top.value} dp exceeds 40% of ${window.bottom.value} dp",
        )
        val targets = rule.boundsOf(hasClickAction())
        assertAllUsable(targets, window)
        FIRST_ROW_LABELS.forEach { label ->
            val layout = rule.onNode(hasText(label), useUnmergedTree = true).textLayout()
            assertLabelWrapsWithoutTruncation(label, layout)
        }
        val undo = targets.first { it.name == "Undo" }
        val unit = targets.first { it.name == MeasurementUnit.METERS.symbol() }
        if (undo.rect.top.value == unit.rect.top.value) {
            assertEquals(rtl, undo.rect.left.value > unit.rect.left.value, "control order should ${if (rtl) "mirror" else "not mirror"}: $undo vs $unit")
        }
    }

    @Test
    @Config(sdk = [29], qualifiers = PHONE)
    fun secondRow_should_NotClipOverlapOrShrinkBelow48dp_At360dp() = assertBothRowsLaidOut(rtl = false)

    @Test
    @Config(sdk = [29], qualifiers = PHONE, fontScale = 2.0f)
    fun secondRow_should_NotClipOverlapOrShrinkBelow48dp_At360dpFontScale2() = assertBothRowsLaidOut(rtl = false)

    @Test
    @Config(sdk = [29], qualifiers = "ar-ldrtl-$PHONE")
    fun secondRow_should_NotClipOverlapOrShrinkBelow48dp_At360dpRtl() = assertBothRowsLaidOut(rtl = true)

    @Test
    @Config(sdk = [29], qualifiers = "ar-ldrtl-$PHONE", fontScale = 2.0f)
    fun firstRowSevenChips_should_WrapWithoutClippingOrShrinkBelow48dp_At360dpFontScale2AndRtl() {
        rule.setContent { Directed(rtl = true) { ToolbarHarness(scale = 1.5f, panOn = true) } }
        rule.waitForIdle()
        val window = rule.rootBounds()
        val chips = rule.boundsOf(
            FIRST_ROW_LABELS.map { hasContentDescription(it) and hasClickAction() }.reduce { a, b -> a or b },
        )
        assertEquals(7, chips.size, "seven labelled chips expected, found $chips")
        assertAllUsable(chips, window)
        val rows = chips.map { it.rect.top.value }.distinct().size
        assertTrue(rows >= 2, "at 360 dp and 200% the chips must wrap onto more than one line")
        FIRST_ROW_LABELS.forEach { label ->
            assertLabelWrapsWithoutTruncation(label, rule.onNode(hasText(label), useUnmergedTree = true).textLayout())
        }
        // x grows rightwards in image space regardless of layout direction.
        assertEquals(0.0, NormalizedPoint(0.0, 0.5).x)
        assertTrue(window.right.value > 0f)
    }

    @Test
    @Config(sdk = [29], qualifiers = PHONE, fontScale = 2.0f)
    fun toolbar_should_FoldZoomAndPanIntoOneZoomButton_When_OverFortyPercentOfTheWindow() {
        rule.setContent { ToolbarHarness(scale = 1.5f, panOn = true) }
        rule.waitForIdle()
        val toolbar = rule.onNodeWithTag("toolbar").getUnclippedBoundsInRoot()
        val window = rule.rootBounds()
        assertTrue(toolbar.bottom.value - toolbar.top.value <= TOOLBAR_MAX_HEIGHT_FRACTION * window.bottom.value + EDGE_TOLERANCE_DP)
        rule.onNodeWithText("Zoom").assertExists()
        rule.onNodeWithContentDescription(ZOOM_IN_DESCRIPTION).assertDoesNotExist()
        rule.onNodeWithText("Zoom").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription(ZOOM_IN_DESCRIPTION).assertExists()
        rule.onNodeWithContentDescription("Pan left").assertExists()
    }
}

/** X-16 / UX-16: the active Select label is white semibold; every label state clears 4.5:1 on the toolbar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = TALL_PHONE)
class ToolbarContrastTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun selectLabel_should_HaveContrastAtLeast45AgainstToolbar_AndReferenceExposeCalibrateFirst() {
        rule.setContent { ToolbarHarness(scale = 1.0f, currentTool = AnnotationTool.SELECT) }
        rule.waitForIdle()
        val style = rule.onNode(hasText("Select"), useUnmergedTree = true).textLayout().layoutInput.style
        assertEquals(Color.White, style.color, "active Select label is Color.White")
        assertEquals(FontWeight.SemiBold, style.fontWeight)
        assertTrue(wcagContrast(style.color, TOOLBAR_BACKGROUND_COLOR) >= 4.5, "active label ${wcagContrast(style.color, TOOLBAR_BACKGROUND_COLOR)}")
        val inactive = rule.onNode(hasText("Distance"), useUnmergedTree = true).textLayout().layoutInput.style.color
        assertTrue(wcagContrast(inactive, TOOLBAR_BACKGROUND_COLOR) >= 4.5, "inactive label ${wcagContrast(inactive, TOOLBAR_BACKGROUND_COLOR)}")
        assertTrue(wcagContrast(DISABLED_TINT_COLOR, TOOLBAR_BACKGROUND_COLOR) >= 4.5, "disabled label")
    }
}

/** X-17 / UX-17: every chip is at least 48 dp both ways and inside the window (no system-bar overlap). The Keep-tool toggle is Task 2.6. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = PHONE)
class ToolbarLayoutBoundsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun chipsAndLockToggle_should_Be48dpAndNotOverlapMicFabOrSystemBars() {
        rule.setContent { ToolbarHarness(scale = 1.5f) }
        rule.waitForIdle()
        val chips = rule.boundsOf(FIRST_ROW_LABELS.map { hasContentDescription(it) and hasClickAction() }.reduce { a, b -> a or b })
        assertEquals(7, chips.size)
        assertAllUsable(chips, rule.rootBounds())
    }
}

/** X-22 / UX-22: zoom targets are 48 dp, the percentage is a spoken value, and the photo frame clears 3:1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = PHONE)
class ZoomControlsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun zoomControls_should_Be48dpExposeValueAndFrameHave3to1Contrast() {
        var frame = Color.Unspecified
        var pasteboard = Color.Unspecified
        rule.setContent {
            frame = MaterialTheme.colorScheme.onSurfaceVariant
            pasteboard = MaterialTheme.colorScheme.surfaceVariant
            Box(Modifier.fillMaxSize()) { AnnotationZoomControls(view = { AnnotationViewTransform(scale = 0.4f) }, onZoomIn = {}, onZoomOut = {}, onFit = {}) }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Zoom 40 percent").assertExists()
        val buttons = rule.boundsOf(hasContentDescription(ZOOM_IN_DESCRIPTION) or hasContentDescription(ZOOM_OUT_DESCRIPTION) or hasContentDescription(ZOOM_FIT_DESCRIPTION))
        assertEquals(3, buttons.size)
        assertAllUsable(buttons, rule.rootBounds())
        rule.onNodeWithContentDescription(ZOOM_OUT_DESCRIPTION).assertIsEnabled()
        assertTrue(wcagContrast(frame, pasteboard) >= 3.0, "frame contrast ${wcagContrast(frame, pasteboard)}")
    }
}

/** X-49 / UX-49: the tokens this story touches meet 4.5:1 (text) and 3:1 (non-text) in light, dark and stone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = TALL_PHONE)
class ThemeContrastTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun newTokensAndElements_should_MeetContrastInLightAndDark() {
        val failures = mutableListOf<String>()
        fun check(name: String, fg: Color, bg: Color, min: Double) {
            val ratio = wcagContrast(fg, bg)
            if (ratio < min) failures += "$name ${"%.2f".format(ratio)} < $min"
        }
        check("Select label on toolbar", TOOLBAR_ACTIVE_LABEL_COLOR, TOOLBAR_BACKGROUND_COLOR, 4.5)
        check("inactive label on toolbar", TOOLBAR_INACTIVE_TINT_COLOR, TOOLBAR_BACKGROUND_COLOR, 4.5)
        check("disabled label on toolbar", DISABLED_TINT_COLOR, TOOLBAR_BACKGROUND_COLOR, 4.5)
        val modes = listOf(StelekitThemeMode.LIGHT, StelekitThemeMode.DARK, StelekitThemeMode.STONE)
        val schemes = mutableMapOf<StelekitThemeMode, androidx.compose.material3.ColorScheme>()
        rule.setContent {
            Column {
                modes.forEach { mode -> StelekitTheme(themeMode = mode) { schemes[mode] = MaterialTheme.colorScheme } }
            }
        }
        rule.waitForIdle()
        modes.forEach { mode ->
            val s = checkNotNull(schemes[mode])
            check("$mode pasteboard frame", s.onSurfaceVariant, s.surfaceVariant, 3.0)
        }
        check("active fill on toolbar", TOOLBAR_ACTIVE_FILL_COLOR, TOOLBAR_BACKGROUND_COLOR, 3.0)
        check("white icon and Pan label on active fill", Color.White, TOOLBAR_ACTIVE_FILL_COLOR, 4.5)
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }
}
