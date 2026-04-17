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
