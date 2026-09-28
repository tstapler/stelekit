package dev.stapler.stelekit.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.platformNavigationInput(
    onBack: () -> Unit,
    onForward: () -> Unit
): Modifier = this.onPointerEvent(PointerEventType.Press) { event ->
    event.changes.forEach { change ->
        when (change.pressed && change.previousPressed.not()) {
            true -> when {
                // Mouse back button (Button 4)
                event.button == PointerButton.Back -> {
                    onBack()
                    change.consume()
                }
                // Mouse forward button (Button 5)
                event.button == PointerButton.Forward -> {
                    onForward()
                    change.consume()
                }
            }
            else -> {}
        }
    }
}

actual fun useLongPressForDrag(): Boolean = false

// Desktop/mouse already has shift-click range select, Ctrl+A, and click-and-drag lasso-select
// (BlockGutter's bullet handle) — precise alternatives that make long-press-to-select redundant
// and actively harmful: a stationary mouse-down while positioning a text cursor is ordinary
// desktop behavior and was firing this instead of focusing the block for editing.
actual fun useLongPressToSelectBlock(): Boolean = false
