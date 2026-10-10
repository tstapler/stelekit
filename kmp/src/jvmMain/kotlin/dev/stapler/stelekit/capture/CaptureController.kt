// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.NotificationType
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.ui.NotificationManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
@Suppress("TooManyFunctions") // one cohesive popup state machine; every function is a transition of _state
class CaptureController(private val fileSystem: PlatformFileSystem) {

    @Volatile
    private var graphManager: GraphManager? = null

    @Volatile
    private var notificationManager: NotificationManager? = null

    @Volatile
    private var appender: JournalAppender? = null

    @Volatile
    private var targetSettings: CaptureTargetSettings? = null

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

    /** The share pipeline's appender, which can also write to graphs that are not open. */
    fun attachShareServices(services: ShareCaptureServices) = attachAppender(services.appender)

    internal fun attachAppender(journalAppender: JournalAppender) {
        appender = journalAppender
    }

    fun attachTargetSettings(settings: CaptureTargetSettings) {
        targetSettings = settings
    }

    /** Registers [hotkeyListener] to call [show] when the bound combo fires. */
    fun start(hotkeyListener: GlobalHotkeyListener) {
        hotkeyListener.register { show() }
    }

    /** Unregisters [hotkeyListener], e.g. on application shutdown. */
    fun stop(hotkeyListener: GlobalHotkeyListener) {
        hotkeyListener.unregister()
        scope.cancel()
    }

    /**
     * Opens the popup with an empty draft. If the popup is already [CapturePopupState.Shown]
     * (a second hotkey trigger while it's open), this is a no-op — the existing draft text is
     * preserved and the caller is responsible for bringing the existing window to front.
     */
    fun show() {
        // Only snapshot the caller's window on the Hidden -> Shown transition. If the popup
        // is already Shown (a second hotkey press while it's open), it likely already has OS
        // focus itself -- recording it here would clobber the real priorFocusOwner captured on
        // the first press, and restoreFocus() would later try to focus the popup being hidden.
        when (val s = _state.value) {
            is CapturePopupState.Shown -> return
            // Hotkey after Esc: keep the draft rather than installing an empty popup over it.
            is CapturePopupState.ConfirmDiscard -> {
                _state.value = s.draft
                return
            }
            CapturePopupState.Hidden -> Unit
        }
        priorFocusOwner = currentActiveWindow()

        val gm = graphManager
        val captureResult = if (gm == null) {
            CaptureResult.NoActiveGraph
        } else {
            CaptureWriter.resolveCaptureAvailability(gm)
        }
        val registry = gm?.graphRegistry?.value
        val choices = registry?.graphs.orEmpty().map { GraphChoice(it.id, it.displayName) }
        val target = targetSettings?.let { CaptureTargetResolver.resolve(it, choices.map { c -> c.id }.toSet()) }
        val targetId = (target as? CaptureTarget.NamedGraph)?.graphId ?: registry?.activeGraphId
        _state.value = CapturePopupState.Shown(
            text = "",
            saveState = SaveState.Idle,
            captureResult = captureResult,
            targetGraphId = targetId,
            graphChoices = choices,
        )
    }

    /** Alt+G / the chooser button. No-op unless there is more than one graph to choose from. */
    fun setChooserOpen(open: Boolean) {
        val current = _state.value as? CapturePopupState.Shown ?: return
        if (current.graphChoices.size < 2) return
        _state.value = current.copy(chooserOpen = open)
    }

    /** Changes the destination for this capture only; typed text is kept and the default is untouched. */
    fun selectGraph(graphId: GraphId) {
        val current = _state.value as? CapturePopupState.Shown ?: return
        if (current.graphChoices.none { it.id == graphId }) return
        // A save in flight or already queued must not be re-pointed and re-saved (duplicate copy).
        if (current.saveState == SaveState.Saving || current.saveState == SaveState.Queued) return
        val retryable = current.saveState == SaveState.Error
        _state.value = current.copy(
            targetGraphId = graphId,
            chooserOpen = false,
            saveState = if (retryable) SaveState.Idle else current.saveState,
            captureResult = if (retryable) null else current.captureResult,
            statusMessage = null,
        )
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
        if (current.saveState == SaveState.Saving || current.saveState == SaveState.Queued) return
        val gm = graphManager
        if (gm == null) {
            _state.value = current.copy(saveState = SaveState.Error, captureResult = CaptureResult.NoActiveGraph)
            return
        }

        // Clear any captureResult left over from a prior failed attempt (e.g. GraphLocked from
        // an earlier save, or the initial availability check) -- CapturePopupContent's render
        // `when` checks captureResult before saveState, so a stale non-null value here would
        // render the wrong placeholder (e.g. "Vault is locked") during a Retry click instead of
        // the Saving state, even though CapturePopupState.Shown itself allows this combination.
        _state.value = current.copy(saveState = SaveState.Saving, captureResult = null, statusMessage = null)

        val targetId = current.targetGraphId
        val target = if (targetId == null) CaptureTarget.ActiveGraph else CaptureTarget.NamedGraph(targetId)
        // The vault-lock gate only concerns the open graph; an off-graph write has its own checks.
        val gate = if (targetId == null || targetId == gm.getActiveGraphId()) CaptureWriter.resolveCaptureAvailability(gm) else null
        val outcome = if (gate != null) {
            AppendOutcome.Failed("unavailable", gate)
        } else {
            (appender ?: JournalAppender(gm, fileSystem)).append(target, current.text)
        }

        // Re-read state rather than reuse `current` — updateText()/dismiss() may have raced
        // with this suspend call while the write was in flight.
        val latest = _state.value as? CapturePopupState.Shown ?: return
        when (outcome) {
            is AppendOutcome.Appended -> markSaved(latest, outcome.saved)
            is AppendOutcome.AppendedOffGraph, AppendOutcome.AlreadyPresent -> markSaved(latest, null)
            is AppendOutcome.Queued -> {
                val message = "Queued for ${latest.targetGraphName ?: "the graph"}"
                _state.value = latest.copy(saveState = SaveState.Queued, statusMessage = message)
                notificationManager?.show(message, NotificationType.INFO)
            }
            is AppendOutcome.Deferred ->
                _state.value = latest.copy(saveState = SaveState.Error, captureResult = CaptureResult.Failed(outcome.reason))
            is AppendOutcome.Failed ->
                _state.value = latest.copy(
                    saveState = SaveState.Error,
                    captureResult = outcome.cause ?: CaptureResult.Failed(outcome.error),
                )
        }
    }

    private fun markSaved(latest: CapturePopupState.Shown, saved: CaptureResult.Saved?) {
        latest.targetGraphId?.let { targetSettings?.recordLastUsed(it) }
        val name = latest.targetGraphName
        // Naming the graph is noise when there is only one.
        val message = if (latest.graphChoices.size > 1 && name != null) "Saved to $name's journal" else "Saved to today's journal"
        logger.info(message)
        notificationManager?.show(message, NotificationType.SUCCESS)
        _state.value = latest.copy(saveState = SaveState.Saved, captureResult = saved)
    }

    /**
     * Esc. Blank text closes at once; any other text asks first ([CapturePopupState.ConfirmDiscard]).
     * Never saves, unlike [dismiss].
     */
    fun requestDismiss() {
        when (val current = _state.value) {
            is CapturePopupState.Hidden -> Unit
            is CapturePopupState.ConfirmDiscard -> keepEditing()
            is CapturePopupState.Shown -> when {
                current.saveState == SaveState.Saving -> Unit
                current.saveState == SaveState.Saved || current.saveState == SaveState.Queued || current.text.isBlank() ->
                    transitionToHidden()
                else -> _state.value = CapturePopupState.ConfirmDiscard(current.copy(chooserOpen = false))
            }
        }
    }

    /** "Discard": closes without saving. */
    fun confirmDiscard() {
        if (_state.value is CapturePopupState.ConfirmDiscard) transitionToHidden()
    }

    /** "Keep editing": back to the draft, text intact. */
    fun keepEditing() {
        val current = _state.value as? CapturePopupState.ConfirmDiscard ?: return
        _state.value = current.draft
    }

    /**
     * Closes the popup on focus loss or window close (Esc uses [requestDismiss]). Non-blank text
     * auto-saves before the state transitions to `Hidden`, so click-away never silently discards a
     * draft. A draft already in [SaveState.Error] is copied to the system clipboard instead of
     * retried, since the write already failed once — closing without a retry avoids masking it.
     */
    fun dismiss() {
        // Focus loss during the discard prompt falls back to the draft, so it is saved rather than lost.
        val current = when (val s = _state.value) {
            is CapturePopupState.ConfirmDiscard -> s.draft
            is CapturePopupState.Shown -> s
            else -> return
        }

        if (current.saveState == SaveState.Saving) return // the in-flight save decides the outcome

        if (current.saveState == SaveState.Saved || current.saveState == SaveState.Queued) {
            transitionToHidden()
            return
        }

        if (current.saveState == SaveState.Error) {
            copyToClipboard(current.text)
            transitionToHidden()
            return
        }

        if (current.text.isNotBlank()) {
            _state.value = current
            scope.launch {
                performSave()
                val after = _state.value
                if (after is CapturePopupState.Shown && after.saveState == SaveState.Error) {
                    // Stay open: hiding now would drop the only copy of an unsaved draft.
                    copyToClipboard(after.text)
                } else {
                    transitionToHidden()
                }
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
