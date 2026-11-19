// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/** "Pan arrows: Auto / Always / Never" (UX-120). */
enum class PanArrows { Auto, Always, Never }

/** The plus-shaped grid of five cells, or one row of four arrows where the grid would cover too much of the canvas. */
enum class PanVariant { Grid, Row }

enum class PanDirection(val dx: Int, val dy: Int, val label: String) {
    Up(0, -1, "Pan up"),
    Down(0, 1, "Pan down"),
    Left(-1, 0, "Pan left"),
    Right(1, 0, "Pan right"),
}

/**
 * The pan pad's rules, all from the chrome table of design/ux.md (`padCell`, `padGridCells`, `padRow`, `padMaxPct`,
 * `gestureInset`); `PanPadPolicyTest` reads those parameters from the generated fixture and fails when a constant here
 * drifts from the table (UX-120, WCAG 2.5.7).
 */
object PanPadPolicy {
    const val CELL_DP = 44
    const val GRID_CELLS = 5
    const val ROW_CELLS = 4
    const val MAX_COVER_PERCENT = 25
    const val GESTURE_INSET_DP = 32

    /** One tap moves the view this fraction of the visible canvas, as a numerator over [STEP_DENOMINATOR]. */
    const val STEP_DENOMINATOR = 4

    /** A second tap on the same arrow inside this window moves two more quarters (three quarters in all). */
    const val DOUBLE_TAP_MS = 300L

    /** Holding repeats after this long, then at [REPEAT_MS]. */
    const val FIRST_REPEAT_MS = 400L
    const val REPEAT_MS = 150L

    /**
     * Whether the "Pan" button is shown. Auto: only while Finger drawing is ON and a touch or pen pointer is present, whether
     * or not the edge strip is on. A mouse- or keyboard-only desktop reaches the pad from the overflow menu instead.
     */
    fun buttonShown(mode: PanArrows, fingerDrawing: Boolean, touchOrPenPresent: Boolean): Boolean = when (mode) {
        PanArrows.Always -> true
        PanArrows.Never -> false
        PanArrows.Auto -> fingerDrawing && touchOrPenPresent
    }

    /** The grid while its five cells cover at most [MAX_COVER_PERCENT] percent of the canvas, else the row. */
    fun variant(canvasWidthDp: Int, canvasHeightDp: Int): PanVariant {
        val grid = GRID_CELLS.toLong() * CELL_DP * CELL_DP
        val canvas = canvasWidthDp.toLong().coerceAtLeast(0L) * canvasHeightDp.toLong().coerceAtLeast(0L)
        return if (grid * PERCENT <= MAX_COVER_PERCENT * canvas) PanVariant.Grid else PanVariant.Row
    }

    /** The share of the canvas, in tenths of a percent, that [variant] covers; for the budget test. */
    fun coverageTenthsPercent(variant: PanVariant, canvasWidthDp: Int, canvasHeightDp: Int): Long {
        val cells = if (variant == PanVariant.Grid) GRID_CELLS else ROW_CELLS
        val canvas = canvasWidthDp.toLong() * canvasHeightDp.toLong()
        return if (canvas <= 0L) 0L else cells.toLong() * CELL_DP * CELL_DP * PERCENT * TENTHS / canvas
    }

    /** The clearance the cluster and pad keep from an active edge strip: the gesture inset plus one 44 dp hit area. */
    fun stripInsetDp(stripActive: Boolean): Int = if (stripActive) GESTURE_INSET_DP + CELL_DP else 0

    /** The view movement, in pixels, of [quarters] steps toward [direction]: the content moves the other way. */
    fun contentShift(direction: PanDirection, quarters: Int, canvasWidthPx: Double, canvasHeightPx: Double): Pair<Double, Double> =
        (-direction.dx * canvasWidthPx * quarters / STEP_DENOMINATOR) to (-direction.dy * canvasHeightPx * quarters / STEP_DENOMINATOR)

    /** How many repeat steps a hold of [heldMs] has produced (the press itself is not counted). */
    fun repeatsAfter(heldMs: Long): Int = if (heldMs < FIRST_REPEAT_MS) 0 else 1 + ((heldMs - FIRST_REPEAT_MS) / REPEAT_MS).toInt()

    private const val PERCENT = 100L
    private const val TENTHS = 10L
}

/** Turns taps into quarter-steps: a second tap on the same arrow within [PanPadPolicy.DOUBLE_TAP_MS] is worth two. */
class PanTapTracker {
    private var lastDirection: PanDirection? = null
    private var lastAt: Long = 0L

    /** The quarters the tap at [nowMs] moves: 1, or 2 for the second tap of a double tap. */
    fun onTap(direction: PanDirection, nowMs: Long): Int {
        val double = direction == lastDirection && nowMs - lastAt <= PanPadPolicy.DOUBLE_TAP_MS
        lastDirection = if (double) null else direction
        lastAt = nowMs
        return if (double) 2 else 1
    }
}

object PanTags {
    const val BUTTON = "sketch.pan.button"
    const val PAD = "sketch.pan.pad"
    const val FIT = "sketch.pan.fit"
    fun arrow(direction: PanDirection) = "sketch.pan.${direction.name.lowercase()}"
}

/** The one 44 dp "Pan" button of the view cluster; activating it again closes the pad. */
@Composable
fun PanButton(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(PanPadPolicy.CELL_DP.dp).testTag(PanTags.BUTTON)
            .semantics { role = Role.Button; contentDescription = if (open) "Close pan pad" else "Pan"; onClick { onToggle(); true } }
            .pointerInput(onToggle) { detectTapGestures { onToggle() } },
        contentAlignment = Alignment.Center,
    ) { Text("Pan") }
}

/**
 * The pan pad: four arrows at the touch floor plus, in the grid, a centre "Fit" (the row keeps Fit in the 100% menu). Opaque
 * and with no auto-collapse timer (WCAG 2.2.1); the host closes it on the Pan button, Esc or a canvas tap. [onPan] receives the
 * direction and the quarters to move. Holding repeats; no gesture needs a drag (WCAG 2.5.7).
 */
@Composable
fun PanPad(variant: PanVariant, onPan: (PanDirection, Int) -> Unit, onFit: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val tracker = remember { PanTapTracker() }
    val clock = remember { TimeSource.Monotonic.markNow() }
    val pan = rememberUpdatedState(onPan)
    val arrow: @Composable (PanDirection, String) -> Unit = { d, glyph ->
        PadCell(PanTags.arrow(d), d.label, glyph, { pan.value(d, tracker.onTap(d, clock.elapsedNow().inWholeMilliseconds)) }, { pan.value(d, 1) })
    }
    Surface(modifier.testTag(PanTags.PAD).onPreviewKeyEvent { e -> (e.type == KeyEventType.KeyDown && e.key == Key.Escape).also { if (it) onClose() } }, color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        if (variant == PanVariant.Row) {
            Row {
                arrow(PanDirection.Left, "←"); arrow(PanDirection.Up, "↑"); arrow(PanDirection.Down, "↓"); arrow(PanDirection.Right, "→")
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                arrow(PanDirection.Up, "↑")
                Row {
                    arrow(PanDirection.Left, "←")
                    PadCell(PanTags.FIT, "Fit", "Fit", onFit, null)
                    arrow(PanDirection.Right, "→")
                }
                arrow(PanDirection.Down, "↓")
            }
        }
    }
}

/** One 44 dp cell. [onHoldStep] runs again after the first-repeat delay and then at the repeat interval while the press lasts. */
@Composable
private fun PadCell(tag: String, description: String, glyph: String, onTap: () -> Unit, onHoldStep: (() -> Unit)?) {
    Box(
        Modifier.size(PanPadPolicy.CELL_DP.dp).testTag(tag).background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { role = Role.Button; contentDescription = description; onClick { onTap(); true } }
            .pointerInput(onTap, onHoldStep) {
                detectTapGestures(onPress = { onTap(); holdAndRepeat(onHoldStep) })
            },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Waits for the release; while the press lasts, [onHoldStep] (when there is one) repeats after the first delay. */
private suspend fun PressGestureScope.holdAndRepeat(onHoldStep: (() -> Unit)?) {
    if (onHoldStep == null) {
        tryAwaitRelease()
        return
    }
    coroutineScope {
        val repeating = launch { repeatWhileHeld(onHoldStep) }
        tryAwaitRelease()
        repeating.cancel()
    }
}

private suspend fun repeatWhileHeld(step: () -> Unit) {
    delay(PanPadPolicy.FIRST_REPEAT_MS)
    while (true) {
        step()
        delay(PanPadPolicy.REPEAT_MS)
    }
}
