// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.stapler.stelekit.platform.PalmClassifier
import dev.stapler.stelekit.sketch.v2.tools.Modifiers
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.view.ScreenPx

/**
 * Feeds Compose pointer events to [adapter] and hands every resulting [PointerAction] to [onActions].
 *
 * Mapping per target (research/input-spike.md): Android and desktop give `PointerType` and pressure from Compose
 * core; wasmJs reports every touch as `Touch` (a raw DOM listener supplies pen data, Task 3.1.2c). A `Unknown`
 * type, which Android uses for a palm, is treated as touch unless [palm] says the contact is a palm (Task 3.1.2h);
 * a palm contact never starts or moves a gesture. The barrel button and the right mouse button map to a secondary
 * click (see [isSecondaryClick]).
 */
fun Modifier.sketchPointerInput(
    adapter: SketchPointerAdapter,
    palm: PalmClassifier = PalmClassifier.None,
    onActions: (List<PointerAction>) -> Unit,
): Modifier =
    pointerInput(adapter, palm) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val actions = event.changes.mapIndexed { index, change ->
                    val raw = change.toRaw(event)
                    val maybePalm = change.type == PointerType.Unknown || raw.phase == RawPhase.Cancel
                    raw.copy(palm = maybePalm && palm.isPalm(event, index))
                }.flatMap { adapter.onPointer(it) }
                if (actions.isNotEmpty()) onActions(actions)
                event.changes.forEach { it.consume() }
            }
        }
    }

internal fun PointerType.toKind(): PointerKind = when (this) {
    PointerType.Mouse -> PointerKind.Mouse
    PointerType.Stylus -> PointerKind.Stylus
    PointerType.Eraser -> PointerKind.Eraser
    else -> PointerKind.Touch
}

/**
 * Compose Android reports a stylus barrel button (`BUTTON_STYLUS_PRIMARY`) as the primary bit while a plain tip
 * contact sets no bit (InputSpikeAndroidTest, Robolectric; device behaviour is Task 0.1.4d). A mouse's primary bit is
 * the ordinary click, so only its secondary and tertiary bits count.
 */
private fun PointerEvent.isSecondaryClick(kind: PointerKind): Boolean = when (kind) {
    PointerKind.Stylus -> buttons.isPrimaryPressed || buttons.isSecondaryPressed || buttons.isTertiaryPressed
    PointerKind.Mouse -> buttons.isSecondaryPressed || buttons.isTertiaryPressed
    else -> false
}

private fun PointerInputChange.toRaw(event: PointerEvent): RawPointer {
    val phase = when {
        !previousPressed && pressed -> RawPhase.Down
        previousPressed && !pressed -> RawPhase.Up
        pressed -> RawPhase.Move
        event.type == PointerEventType.Exit -> RawPhase.Cancel
        else -> RawPhase.Hover
    }
    val kind = type.toKind()
    return RawPointer(
        id = id.value.toInt(),
        kind = kind,
        phase = phase,
        position = ScreenPx(position.x.toDouble(), position.y.toDouble()),
        pressure = pressure.toDouble(),
        secondaryButton = event.isSecondaryClick(kind),
        modifiers = Modifiers(
            shift = event.keyboardModifiers.isShiftPressed,
            ctrl = event.keyboardModifiers.isCtrlPressed,
            alt = event.keyboardModifiers.isAltPressed,
            meta = event.keyboardModifiers.isMetaPressed,
        ),
        timestamp = uptimeMillis,
    )
}
