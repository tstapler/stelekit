// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.math.max
import kotlin.math.roundToInt

/** The three steps of the first-run walkthrough, in order (ux.md section 22). */
enum class WalkthroughStep(val number: Int, val title: String, val anchorStop: TabStop) {
    Tools(1, SketchStrings.Walkthrough.TITLE_TOOLS, TabStop.ToolStrip),
    Zoom(2, SketchStrings.Walkthrough.TITLE_ZOOM, TabStop.ViewCluster),
    Shortcuts(3, SketchStrings.Walkthrough.TITLE_SHORTCUTS, TabStop.TopBar),
}

/** A rectangle in the overlay's own coordinate space (root pixels). */
data class PlaceRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

enum class PopoverSide { Above, Below, Start, End }

/**
 * Where the walkthrough popover sits (WCAG 2.4.11): on the side of its target with the most free space, never over the
 * target, and on the opposite side if it would not fit. Start and End are the logical sides, mirrored in right-to-left.
 */
object PopoverPlacement {
    fun side(anchor: PlaceRect, popoverWidth: Float, popoverHeight: Float, container: PlaceRect): PopoverSide {
        val free = mapOf(
            PopoverSide.Above to anchor.top - container.top,
            PopoverSide.Below to container.bottom - anchor.bottom,
            PopoverSide.Start to anchor.left - container.left,
            PopoverSide.End to container.right - anchor.right,
        )
        val need = mapOf(
            PopoverSide.Above to popoverHeight, PopoverSide.Below to popoverHeight,
            PopoverSide.Start to popoverWidth, PopoverSide.End to popoverWidth,
        )
        val fitting = PopoverSide.entries.filter { free.getValue(it) >= need.getValue(it) }.ifEmpty { PopoverSide.entries }
        return fitting.maxBy { free.getValue(it) }
    }

    /** The top-left of the popover for [side], kept inside [container]; a physical Start is the left in left-to-right. */
    fun origin(side: PopoverSide, anchor: PlaceRect, popoverWidth: Float, popoverHeight: Float, container: PlaceRect, direction: LayoutDirection): Pair<Float, Float> {
        val centreX = (anchor.left + anchor.right) / 2f - popoverWidth / 2f
        val centreY = (anchor.top + anchor.bottom) / 2f - popoverHeight / 2f
        val leftOfAnchor = anchor.left - popoverWidth
        val rightOfAnchor = anchor.right
        val (x, y) = when (side) {
            PopoverSide.Above -> centreX to anchor.top - popoverHeight
            PopoverSide.Below -> centreX to anchor.bottom
            PopoverSide.Start -> (if (direction == LayoutDirection.Ltr) leftOfAnchor else rightOfAnchor) to centreY
            PopoverSide.End -> (if (direction == LayoutDirection.Ltr) rightOfAnchor else leftOfAnchor) to centreY
        }
        return x.coerceIn(container.left, max(container.left, container.right - popoverWidth)) to
            y.coerceIn(container.top, max(container.top, container.bottom - popoverHeight))
    }
}

/** The step texts: the wording variant is chosen when a step is shown and fixed while it stays (ux.md section 22). */
object WalkthroughCopy {
    const val TOTAL = 3

    fun sentence(step: WalkthroughStep, variant: InputKind, grouped: Boolean): String = when (step) {
        WalkthroughStep.Tools -> toolsSentence(variant, grouped)
        WalkthroughStep.Zoom -> when (variant) {
            InputKind.TouchOrPen -> SketchStrings.Walkthrough.ZOOM_TOUCH
            InputKind.Mouse -> SketchStrings.Walkthrough.ZOOM_MOUSE
            InputKind.Keyboard -> SketchStrings.Walkthrough.ZOOM_KEYBOARD
        }
        WalkthroughStep.Shortcuts -> SketchStrings.Walkthrough.SHORTCUTS_ALL
    }

    private fun toolsSentence(variant: InputKind, grouped: Boolean): String = when {
        grouped -> when (variant) {
            InputKind.TouchOrPen -> SketchStrings.Walkthrough.TOOLS_GROUPED_TOUCH
            InputKind.Mouse -> SketchStrings.Walkthrough.TOOLS_GROUPED_MOUSE
            InputKind.Keyboard -> SketchStrings.Walkthrough.TOOLS_GROUPED_KEYBOARD
        }
        variant == InputKind.Keyboard -> SketchStrings.Walkthrough.TOOLS_FLAT_KEYBOARD
        else -> SketchStrings.Walkthrough.TOOLS_FLAT_POINTER
    }

    /** "Step n of 3. Title. sentence": the polite announcement of a later step and the accessible name of step 1's popover. */
    fun announcement(step: WalkthroughStep, variant: InputKind, grouped: Boolean): String =
        SketchStrings.Walkthrough.announcement(step.number, TOTAL, step.title, sentence(step, variant, grouped))
}

/**
 * The first-run walkthrough's state (Task 3.6.8e): [step] is null while it is not shown. The variant and the strip's flat or
 * grouped state are read when a step is shown ([start], [next], [back]) and stay fixed until the next change, so the text never
 * changes under a screen reader. [returnFocus] is the control that held focus before it started. Pure Compose state.
 */
class WalkthroughState {
    var step: WalkthroughStep? by mutableStateOf(null)
        private set

    var variant: InputKind by mutableStateOf(InputKind.Mouse)
        private set

    var grouped: Boolean by mutableStateOf(true)
        private set

    var returnFocus: String = CANVAS_FOCUS
        private set

    /** The popover's container; the anchor's Tab exit and the F6 and Shift+F6 handling point at it (D-52). */
    val popoverFocus = FocusRequester()

    /** Where each anchor sits in root pixels; reported by the shell's layout. */
    val anchors = mutableStateMapOf<WalkthroughStep, PlaceRect>()

    val active: Boolean get() = step != null

    fun start(input: InputKind, grouped: Boolean, returnFocus: String) {
        this.returnFocus = returnFocus
        show(WalkthroughStep.Tools, input, grouped)
    }

    /** The next step, or the end after the last one; returns the focus key to restore when it ended, else null. */
    fun next(input: InputKind, grouped: Boolean): String? {
        val current = step ?: return null
        val following = WalkthroughStep.entries.getOrNull(current.ordinal + 1) ?: return end()
        show(following, input, grouped)
        return null
    }

    fun back(input: InputKind, grouped: Boolean) {
        val current = step ?: return
        WalkthroughStep.entries.getOrNull(current.ordinal - 1)?.let { show(it, input, grouped) }
    }

    /** Ends the tour (Done, Esc, or activating the anchored control); returns the focus key to restore. */
    fun end(): String {
        step = null
        return returnFocus.also { returnFocus = CANVAS_FOCUS }
    }

    /** The user activated the control [anchor] points at: that control did its action, and the tour is over. */
    fun anchorActivated(anchor: WalkthroughStep): String? = if (step == anchor) end() else null

    companion object {
        /** The focus key to restore when the control that held focus before the tour is gone: the canvas region. */
        const val CANVAS_FOCUS = "canvas"
    }

    private fun show(target: WalkthroughStep, input: InputKind, grouped: Boolean) {
        variant = input
        this.grouped = grouped
        step = target
    }
}

object WalkthroughTags {
    const val POPOVER = "sketch.walkthrough.popover"
    const val TEXT = "sketch.walkthrough.text"
    const val INDICATOR = "sketch.walkthrough.indicator"
    const val BACK = "sketch.walkthrough.back"
    const val NEXT = "sketch.walkthrough.next"
    const val DONE = "sketch.walkthrough.done"
    const val ESC_HINT = "sketch.walkthrough.esc"
    const val RING = "sketch.walkthrough.ring"
}

/**
 * What the host supplies the overlay with: the input and strip state to read when a step is shown, and where focus goes on end.
 * [regions] lets F6 and Tab leave the popover for a named region with the tour still shown; [escapeActs] is false while a row
 * above the walkthrough in the one Esc order (ux.md section 17) applies, so that row takes the press first.
 */
class WalkthroughDriver(
    val input: () -> InputKind,
    val grouped: () -> Boolean,
    val onEnded: (String) -> Unit,
    val regions: RegionFocus? = null,
    val escapeActs: () -> Boolean = { true },
)

private const val RING_DP = 2
private const val HALO_DP = 1
private val POPOVER_WIDTH = 320.dp
private val TARGET = 44.dp
private val ELEVATION = 6.dp

/**
 * The popover and the highlight ring of the walkthrough, drawn over the whole shell. Non-modal: only the popover takes
 * input. Focus goes once to the popover on start and to the primary button on every later step; Tab leaves the popover to
 * the next control (it never traps), and Esc ends the tour; [WalkthroughDriver.onEnded] receives the focus key to restore.
 */
@Composable
fun CoachWalkthroughOverlay(state: WalkthroughState, driver: WalkthroughDriver, modifier: Modifier = Modifier) {
    val step = state.step ?: return
    var origin by remember { mutableStateOf(PlaceRect(0f, 0f, 0f, 0f)) }
    val direction = LocalLayoutDirection.current
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.rootRect() }) {
        val anchor = state.anchors[step]?.let { PlaceRect(it.left - origin.left, it.top - origin.top, it.right - origin.left, it.bottom - origin.top) }
        if (anchor != null) HighlightRing(anchor)
        val container = PlaceRect(0f, 0f, origin.width, origin.height)
        Popover(state, step, driver, Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val spot = anchor?.let { placed(it, placeable.width.toFloat(), placeable.height.toFloat(), container, direction) } ?: (0f to 0f)
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(IntOffset(spot.first.roundToInt(), spot.second.roundToInt())) }
        })
    }
}

internal fun LayoutCoordinates.rootRect(): PlaceRect {
    val at = positionInRoot()
    return PlaceRect(at.x, at.y, at.x + size.width, at.y + size.height)
}

internal fun placed(anchor: PlaceRect, width: Float, height: Float, container: PlaceRect, direction: LayoutDirection): Pair<Float, Float> =
    PopoverPlacement.origin(PopoverPlacement.side(anchor, width, height, container), anchor, width, height, container, direction)

/** A two-tone ring: a focus-coloured 2 dp line inside a contrasting 1 dp halo, so it reads on any surround. */
@Composable
internal fun HighlightRing(anchor: PlaceRect) {
    val density = LocalDensity.current
    val inner = MaterialTheme.colorScheme.primary
    val halo = MaterialTheme.colorScheme.surface
    Box(
        Modifier
            .offset { IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt()) }
            .size(with(density) { anchor.width.toDp() }, with(density) { anchor.height.toDp() })
            .testTag(WalkthroughTags.RING)
            .border(HALO_DP.dp, halo)
            .padding(HALO_DP.dp)
            .border(RING_DP.dp, inner),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Popover(state: WalkthroughState, step: WalkthroughStep, driver: WalkthroughDriver, modifier: Modifier = Modifier) {
    val container = state.popoverFocus
    val primary = remember { FocusRequester() }
    val first = step == WalkthroughStep.Tools
    val announcement = WalkthroughCopy.announcement(step, state.variant, state.grouped)
    LaunchedEffect(step) { runCatching { if (first) container.requestFocus() else primary.requestFocus() } }
    val regions = driver.regions
    Surface(
        modifier
            .widthIn(max = POPOVER_WIDTH)
            .testTag(WalkthroughTags.POPOVER)
            .focusProperties { exit = { direction -> popoverExit(regions, step, direction) } }
            .focusGroup()
            .focusRequester(container)
            .focusable()
            .onPreviewKeyEvent { e -> popoverKey(e, state, step, driver) }
            .semantics {
                isTraversalGroup = true
                paneTitle = SketchStrings.Walkthrough.REGION
                contentDescription = if (first) announcement else SketchStrings.Walkthrough.REGION
            },
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = ELEVATION,
    ) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StepText(state, step, announcement)
            StepButtons(state, step, driver, primary)
        }
    }
}

/** Where Tab and Shift+Tab leave the popover: the region after the anchor, or the anchor itself; default when none is on screen. */
@OptIn(ExperimentalComposeUiApi::class)
private fun popoverExit(regions: RegionFocus?, step: WalkthroughStep, direction: FocusDirection): FocusRequester = when (direction) {
    FocusDirection.Next -> regions?.neighbour(step.anchorStop, backward = false) ?: FocusRequester.Default
    FocusDirection.Previous -> regions?.takeIf { step.anchorStop in it.present }?.requester(step.anchorStop) ?: FocusRequester.Default
    else -> FocusRequester.Default
}

/** Esc ends the tour unless a higher row of the Esc order applies; F6 and Shift+F6 leave for a region with the tour still shown. */
private fun popoverKey(e: KeyEvent, state: WalkthroughState, step: WalkthroughStep, driver: WalkthroughDriver): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    return when (e.key) {
        Key.Escape -> driver.escapeActs().also { if (it) driver.onEnded(state.end()) }
        Key.F6 -> leaveForRegion(driver.regions, step, backward = e.isShiftPressed)
        else -> false
    }
}

/** F6 goes to the region after the anchor, Shift+F6 to the anchor ([KeyboardRegions.fromPopover]). */
private fun leaveForRegion(regions: RegionFocus?, step: WalkthroughStep, backward: Boolean): Boolean {
    val target = regions?.let { KeyboardRegions.fromPopover(step.anchorStop, it.present, backward) } ?: return false
    return regions.focus(target)
}

@Composable
private fun StepText(state: WalkthroughState, step: WalkthroughStep, announcement: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(SketchStrings.Walkthrough.indicator(step.number, WalkthroughCopy.TOTAL), Modifier.testTag(WalkthroughTags.INDICATOR), style = MaterialTheme.typography.labelMedium)
        Text(step.title, style = MaterialTheme.typography.titleSmall)
        // One polite string after a later step's focus change; step 1 is read through the focused popover's name instead.
        Text(
            WalkthroughCopy.sentence(step, state.variant, state.grouped),
            Modifier.testTag(WalkthroughTags.TEXT).semantics {
                if (step != WalkthroughStep.Tools) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announcement
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.variant == InputKind.Keyboard) Text(SketchStrings.Walkthrough.ESC_TO_FINISH, Modifier.testTag(WalkthroughTags.ESC_HINT), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StepButtons(state: WalkthroughState, step: WalkthroughStep, driver: WalkthroughDriver, primary: FocusRequester) {
    val last = step == WalkthroughStep.entries.last()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { state.back(driver.input(), driver.grouped()) },
            enabled = step != WalkthroughStep.Tools,
            modifier = Modifier.defaultMinSize(minWidth = TARGET, minHeight = TARGET).testTag(WalkthroughTags.BACK),
        ) { Text(SketchStrings.Walkthrough.BACK) }
        TextButton(
            onClick = { state.next(driver.input(), driver.grouped())?.let(driver.onEnded) },
            modifier = Modifier.defaultMinSize(minWidth = TARGET, minHeight = TARGET).focusRequester(primary).testTag(if (last) WalkthroughTags.DONE else WalkthroughTags.NEXT),
        ) { Text(if (last) SketchStrings.Walkthrough.DONE else SketchStrings.Walkthrough.NEXT) }
    }
}
