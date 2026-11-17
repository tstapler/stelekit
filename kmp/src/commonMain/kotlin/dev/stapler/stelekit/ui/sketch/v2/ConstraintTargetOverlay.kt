// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings

/** What the armed picker asks its host to do: pick a target, move keyboard focus (the new state), or cancel without committing anything. */
class TargetPickerActions(
    val onPick: (ConstraintTarget) -> Unit,
    val onState: (PickerState) -> Unit,
    val onCancel: () -> Unit,
)

/**
 * The armed picker's markers on the canvas (Task 4.2.6b, UX-98). Every valid target of [candidates] shows a 24 dp marker inside
 * a hit area at the touch floor of [pointer]: a square for an endpoint or vertex, a circle for a centre, a bar for an edge, so
 * the kind never rests on colour. The hovered or focused marker gets a text chip naming it, announced politely. Taps and
 * clicks pick (no drag); Tab, Shift+Tab and the arrow keys cycle the markers, Enter picks and Esc cancels. Tapping empty
 * canvas does nothing here, so a mis-tap cannot lose a first pick.
 */
@Composable
fun ConstraintTargetOverlay(
    candidates: List<TargetCandidate>,
    state: PickerState,
    pointer: PointerClass,
    toScreen: (Double, Double) -> Pair<Double, Double>,
    actions: TargetPickerActions,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var hovered by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val active = state.focused.takeIf { it in candidates.indices } ?: hovered?.takeIf { it in candidates.indices }
    val hit = TouchTargetFloor.floorDp(pointer)
    Box(
        modifier.fillMaxSize().testTag(PickerTags.OVERLAY)
            .semantics { contentDescription = SketchConstraintStrings.Picker.MARKERS_REGION }
            .onPreviewKeyEvent { handleKey(it, candidates, state, actions) }
            .focusRequester(focusRequester).focusable(),
    ) {
        candidates.forEachIndexed { index, c ->
            val (sx, sy) = toScreen(c.x, c.y)
            Box(Modifier.offset((sx - hit / 2.0).dp, (sy - hit / 2.0).dp).size(hit.dp)) {
                Marker(c, active == index, { actions.onPick(c.target) }) { over -> hovered = if (over) index else hovered.takeIf { it != index } }
            }
        }
        active?.let { index ->
            val c = candidates[index]
            val (sx, sy) = toScreen(c.x, c.y)
            Text(
                c.chipText,
                Modifier.offset((sx + hit / 2.0).dp, (sy + hit / 2.0).dp).testTag(PickerTags.CHIP)
                    .background(MaterialTheme.colorScheme.surface).padding(horizontal = CHIP_PAD_DP.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun handleKey(e: KeyEvent, candidates: List<TargetCandidate>, state: PickerState, actions: TargetPickerActions): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    return when (e.key) {
        Key.Escape -> true.also { actions.onCancel() }
        Key.Tab -> true.also { actions.onState(TargetPicker.moveFocus(state, candidates.size, if (e.isShiftPressed) FocusStep.Previous else FocusStep.Next)) }
        Key.DirectionRight, Key.DirectionDown -> true.also { actions.onState(TargetPicker.moveFocus(state, candidates.size, FocusStep.Next)) }
        Key.DirectionLeft, Key.DirectionUp -> true.also { actions.onState(TargetPicker.moveFocus(state, candidates.size, FocusStep.Previous)) }
        Key.Enter, Key.NumPadEnter -> candidates.getOrNull(state.focused)?.let { actions.onPick(it.target) } != null
        else -> false
    }
}

@Composable
private fun Marker(candidate: TargetCandidate, highlighted: Boolean, onPick: () -> Unit, onHover: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val over by source.collectIsHoveredAsState()
    val latestOnHover by rememberUpdatedState(onHover)
    LaunchedEffect(over) { latestOnHover(over) }
    val border = if (highlighted) HIGHLIGHT_BORDER_DP else BORDER_DP
    val (width, height, shape) = faceOf(candidate.shape)
    Box(
        Modifier.fillMaxSize().testTag(PickerTags.marker(candidate.key)).hoverable(source).clickable(onClick = onPick)
            .semantics { contentDescription = SketchConstraintStrings.Picker.markerSemantics(candidate.chipText, candidate.picked); role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width.dp, height.dp).background(MaterialTheme.colorScheme.surface, shape)
                .border(border.dp, MaterialTheme.colorScheme.primary, shape),
        )
    }
}

private data class Face(val width: Int, val height: Int, val shape: Shape)

private fun faceOf(shape: MarkerShape): Face = when (shape) {
    MarkerShape.Square -> Face(ConstraintGlyphLayout.VISIBLE_DP, ConstraintGlyphLayout.VISIBLE_DP, RectangleShape)
    MarkerShape.Circle -> Face(ConstraintGlyphLayout.VISIBLE_DP, ConstraintGlyphLayout.VISIBLE_DP, CircleShape)
    MarkerShape.Bar -> Face(ConstraintGlyphLayout.VISIBLE_DP, BAR_HEIGHT_DP, RoundedCornerShape(BAR_RADIUS_DP.dp))
}

private const val BORDER_DP = 2
private const val HIGHLIGHT_BORDER_DP = 4
private const val BAR_HEIGHT_DP = 8
private const val BAR_RADIUS_DP = 2
private const val CHIP_PAD_DP = 8
