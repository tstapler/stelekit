// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.NotificationType
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.ui.NotificationManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection

/**
 * Owns the desktop quick-capture popup's lifecycle and coroutine scope.
 *
 * Per this repo's coroutine-scope-ownership rule, this class owns its [CoroutineScope]
 * internally rather than accepting a caller-supplied `rememberCoroutineScope()` — the popup
 * composable is recreated on every show/hide cycle, but the controller (and its scope)
 * outlives all of them, so a `save()` triggered after the popup has been torn down and
 * rebuilt several times never hits `ForgottenCoroutineScopeException`.
 */
class CaptureController(private val fileSystem: PlatformFileSystem) {

    @Volatile
    private var graphManager: GraphManager? = null

    @Volatile
    private var notificationManager: NotificationManager? = null

    private val logger = Logger("CaptureController")

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            logger.error("Uncaught in capture scope", e)
        },
    )

    private val _state = MutableStateFlow<CapturePopupState>(CapturePopupState.Hidden)
    val state: StateFlow<CapturePopupState> = _state.asStateFlow()

    /** The window that had focus before the popup opened, restored when it closes. */
    private var priorFocusOwner: Window? = null

    fun attachGraphManager(gm: GraphManager) {
        graphManager = gm
    }

    fun attachNotificationManager(nm: NotificationManager) {
        notificationManager = nm
    }

    /** Registers [hotkeyListener] to call [show] when the bound combo fires. */
    fun start(hotkeyListener: GlobalHotkeyListener) {
        hotkeyListener.register { show() }
    }

    /** Unregisters [hotkeyListener], e.g. on application shutdown. */
    fun stop(hotkeyListener: GlobalHotkeyListener) {
        hotkeyListener.unregister()
    }

    /**
     * Opens the popup with an empty draft. If the popup is already [CapturePopupState.Shown]
     * (a second hotkey trigger while it's open), this is a no-op — the existing draft text is
     * preserved and the caller is responsible for bringing the existing window to front.
     */
    fun show() {
        priorFocusOwner = currentActiveWindow()

        if (_state.value is CapturePopupState.Shown) return

        val gm = graphManager
        val captureResult = if (gm == null) {
            CaptureResult.NoActiveGraph
        } else {
            CaptureWriter.resolveCaptureAvailability(gm)
        }
        _state.value = CapturePopupState.Shown(text = "", saveState = SaveState.Idle, captureResult = captureResult)
    }

    /** Updates the draft text in place. No-op when the popup isn't shown. */
    fun updateText(text: String) {
        val current = _state.value
        if (current is CapturePopupState.Shown) {
            _state.value = current.copy(text = text)
        }
    }

    /** Persists the current draft via [CaptureWriter]. Runs on the controller's own scope. */
    fun save() {
        scope.launch { performSave() }
    }

    private suspend fun performSave() {
        val current = _state.value as? CapturePopupState.Shown ?: return
        val gm = graphManager
        if (gm == null) {
            _state.value = current.copy(saveState = SaveState.Error, captureResult = CaptureResult.NoActiveGraph)
            return
        }

        _state.value = current.copy(saveState = SaveState.Saving)
        val result = CaptureWriter.writeCaptureDirect(gm, fileSystem, current.text, captureId = null)

        // Re-read state rather than reuse `current` — updateText()/dismiss() may have raced
        // with this suspend call while the write was in flight.
        val latest = _state.value as? CapturePopupState.Shown ?: return
        when (result) {
            is CaptureResult.Saved -> {
                _state.value = latest.copy(saveState = SaveState.Saved, captureResult = result)
                logger.info("Saved to today's journal")
                notificationManager?.show("Saved to today's journal", NotificationType.SUCCESS)
            }
            else -> _state.value = latest.copy(saveState = SaveState.Error, captureResult = result)
        }
    }

    /**
     * Closes the popup. Non-blank text auto-saves before the state transitions to `Hidden`, so
     * dismissing (Escape, click-away) never silently discards a draft. A draft already in
     * [SaveState.Error] is copied to the system clipboard instead of retried, since the write
     * already failed once — closing without a retry avoids masking the failure.
     */
    fun dismiss() {
        val current = _state.value as? CapturePopupState.Shown ?: return

        if (current.saveState == SaveState.Error) {
            copyToClipboard(current.text)
            transitionToHidden()
            return
        }

        if (current.text.isNotBlank()) {
            scope.launch {
                performSave()
                transitionToHidden()
            }
        } else {
            transitionToHidden()
        }
    }

    /** State-only transition to `Hidden` — never saves (the save already completed). */
    fun hide() {
        transitionToHidden()
    }

    private fun transitionToHidden() {
        _state.value = CapturePopupState.Hidden
        restoreFocus()
    }

    private fun restoreFocus() {
        val window = priorFocusOwner ?: return
        priorFocusOwner = null
        try {
            window.toFront()
            window.requestFocus()
        } catch (e: Throwable) {
            // A display-less host (headless CI, this test suite) throws a raw AWTError from
            // native toolkit init, not just HeadlessException — focus restore is best-effort.
            logger.warn("Failed to restore focus to prior window", e)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        } catch (e: Throwable) {
            logger.warn("Clipboard unavailable", e)
        }
    }

    private fun currentActiveWindow(): Window? = try {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    } catch (e: Throwable) {
        null
    }
}
