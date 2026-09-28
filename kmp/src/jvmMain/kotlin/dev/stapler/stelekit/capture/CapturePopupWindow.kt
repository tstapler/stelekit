// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.KeyEvent
import kotlinx.coroutines.delay
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/** How long the "Saved" confirmation stays on screen before the popup auto-hides. */
private const val SAVED_AUTO_HIDE_MS = 600L

private fun isMacOs(): Boolean =
    System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true

/**
 * Tab/Shift+Tab, Escape, and Ctrl+Enter (Cmd+Enter on macOS) are intercepted at the dialog root
 * via `onPreviewKeyEvent` (preview phase, before children) and consumed (`true`) so AWT's own
 * Tab-traversal and any parent handler never see them — see [CapturePopupWindow]'s call site for
 * why plain Tab must be caught here rather than left to the OS. Every other key (including plain
 * Enter, which must insert a newline) falls through to the focused child unconsumed.
 */
private fun handlePopupKeyEvent(
    keyEvent: KeyEvent,
    controller: CaptureController,
    focusManager: FocusManager,
    isSaveComboPressed: (KeyEvent) -> Boolean,
): Boolean {
    val isKeyDown = keyEvent.type == KeyEventType.KeyDown
    return when (keyEvent.key) {
        Key.Tab -> {
            if (isKeyDown) {
                val direction = if (keyEvent.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
                focusManager.moveFocus(direction)
            }
            true
        }
        Key.Escape -> {
            if (isKeyDown) controller.dismiss()
            true
        }
        Key.Enter, Key.NumPadEnter -> {
            val comboPressed = isKeyDown && isSaveComboPressed(keyEvent)
            if (comboPressed) controller.save()
            comboPressed
        }
        else -> false
    }
}

/**
 * The desktop quick-capture popup: an undecorated, always-on-top window that renders
 * [CapturePopupContent] and is shown/hidden per [CaptureController.state].
 *
 * Split from [CapturePopupContent] so tests can exercise the rendered content directly via
 * `composeTestRule.setContent { CapturePopupContent(controller) }` without a real AWT window —
 * a `Window()` inside `Window()` (test rule's own host window nested with this real one) creates
 * two independent Compose scenes, and `onNodeWithText`/etc. only see the outer one's semantics
 * tree.
 */
@Composable
fun CapturePopupWindow(controller: CaptureController) {
    val state by controller.state.collectAsState()
    if (state is CapturePopupState.Hidden) return

    val windowState = rememberWindowState(
        width = 480.dp,
        height = 220.dp,
        position = WindowPosition(Alignment.Center),
    )

    Window(
        onCloseRequest = controller::dismiss,
        state = windowState,
        title = "SteleKit Quick Capture",
        undecorated = true,
        transparent = false,
        resizable = false,
        alwaysOnTop = true,
    ) {
        // Losing OS focus (alt-tab away) must behave like Escape: auto-save non-blank text,
        // then close. `alwaysOnTop` only keeps the popup visually on top — it does nothing to
        // stop focus itself moving to another app, so without this listener an orphaned window
        // with a stale draft would sit on screen after the user has moved on.
        // `window` (a `ComposeWindow`, itself a `java.awt.Window`) comes from this content
        // lambda's `FrameWindowScope` receiver — `LocalWindow` provides the same instance but is
        // an internal API in this Compose UI version.
        val awtWindow = window
        DisposableEffect(awtWindow) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent) = Unit
                override fun windowLostFocus(e: WindowEvent) {
                    controller.dismiss()
                }
            }
            awtWindow.addWindowFocusListener(listener)
            onDispose { awtWindow.removeWindowFocusListener(listener) }
        }

        CapturePopupContent(controller)
    }
}

/** Renders the popup's body for whatever [CaptureController.state] currently holds. */
@Composable
fun CapturePopupContent(controller: CaptureController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val shown = state as? CapturePopupState.Shown ?: return
    val focusManager = LocalFocusManager.current
    val isSaveComboPressed = remember {
        if (isMacOs()) {
            { keyEvent: KeyEvent -> keyEvent.isMetaPressed }
        } else {
            { keyEvent: KeyEvent -> keyEvent.isCtrlPressed }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "SteleKit Quick Capture"
                // Compose UI 1.10.3's Role enum has no Dialog value (Button/Checkbox/Switch/
                // RadioButton/Tab/Image/DropdownList/ValuePicker/Carousel only) — paneTitle is
                // this version's equivalent for a top-level container's accessible name.
                paneTitle = "SteleKit Quick Capture"
            }
            .onPreviewKeyEvent { keyEvent -> handlePopupKeyEvent(keyEvent, controller, focusManager, isSaveComboPressed) },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when {
                shown.captureResult is CaptureResult.NoActiveGraph -> UnavailablePlaceholder(
                    message = "No graph configured",
                    onOpenStelekit = controller::dismiss,
                )
                shown.captureResult is CaptureResult.GraphLocked -> UnavailablePlaceholder(
                    message = "Vault is locked — open SteleKit to unlock",
                    onOpenStelekit = controller::dismiss,
                )
                shown.saveState == SaveState.Saved -> SavedContent(controller)
                else -> EditableCaptureContent(controller, shown)
            }
        }
    }
}

/** [CaptureResult.NoActiveGraph] / [CaptureResult.GraphLocked] — no text field, error prevention. */
@Composable
private fun UnavailablePlaceholder(message: String, onOpenStelekit: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenStelekit, modifier = Modifier.testTag("openStelekitButton")) {
            Text("Open SteleKit")
        }
    }
}

/** Terminal success state — auto-hides itself after [SAVED_AUTO_HIDE_MS]. */
@Composable
private fun SavedContent(controller: CaptureController) {
    Text(
        "Saved to today's journal",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    LaunchedEffect(Unit) {
        delay(SAVED_AUTO_HIDE_MS)
        controller.hide()
    }
}

/**
 * Idle / Saving / Error states — the text field stays visible and its text is never cleared or
 * lost across any of these, including a failed save (AC: preserve typed text, show Retry / Copy
 * text & close).
 */
@Composable
private fun EditableCaptureContent(controller: CaptureController, shown: CapturePopupState.Shown) {
    val focusRequester = remember { FocusRequester() }

    Column {
        OutlinedTextField(
            value = shown.text,
            onValueChange = controller::updateText,
            enabled = shown.saveState != SaveState.Saving,
            placeholder = { Text("Type a thought… (Enter for a new line, Ctrl+Enter to save)") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("captureTextField"),
            minLines = 3,
            maxLines = 6,
        )

        Spacer(Modifier.height(8.dp))

        CaptureFooter(controller, shown)
    }

    // Runs once each time this composable is newly (re)mounted — which is exactly the
    // Hidden->Shown transition, since CapturePopupContent early-returns and composes nothing
    // while Hidden, and the Error->Retry->Saving path stays within this same composable
    // (no unmount) so focus isn't stolen from the user mid-retry.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/** Below the text field: a saving spinner, an error banner + Retry/Copy, or the keyboard hint. */
@Composable
private fun CaptureFooter(controller: CaptureController, shown: CapturePopupState.Shown) {
    when (shown.saveState) {
        SaveState.Saving -> Row {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Saving…", style = MaterialTheme.typography.labelSmall)
        }
        SaveState.Error -> CaptureErrorBanner(controller, shown)
        else -> Text(
            "Esc save & close · Ctrl+Enter save now",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CaptureErrorBanner(controller: CaptureController, shown: CapturePopupState.Shown) {
    val message = (shown.captureResult as? CaptureResult.Failed)?.message
        ?: "Couldn't save — please try again."
    Column {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("captureErrorMessage"),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = controller::save, modifier = Modifier.testTag("retryButton")) {
                Text("Retry")
            }
            OutlinedButton(onClick = controller::dismiss, modifier = Modifier.testTag("copyCloseButton")) {
                Text("Copy text & close")
            }
        }
    }
}
