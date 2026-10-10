package dev.stapler.stelekit.ui

import androidx.compose.runtime.compositionLocalOf
import dev.stapler.stelekit.capture.InboxItem
import dev.stapler.stelekit.capture.RetryResult
import dev.stapler.stelekit.capture.ShareCaptureServices
import dev.stapler.stelekit.capture.ShareInboxState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the queued-shares panel can do to the inbox (UX S13). */
interface ShareInboxActions {
    /** Text for the clipboard; null when it cannot be recovered. Works without the item's graph. */
    suspend fun copyText(item: InboxItem): String?
    suspend fun discard(item: InboxItem): Boolean
    suspend fun retryNow(item: InboxItem): RetryResult
}

/** UI-facing view of the share pipeline: inbox state, panel visibility and rescue actions. */
class ShareInboxUi(private val services: ShareCaptureServices) : ShareInboxActions {
    val state: StateFlow<ShareInboxState> = services.inbox.state

    private val _panelOpen = MutableStateFlow(false)
    val panelOpen: StateFlow<Boolean> = _panelOpen.asStateFlow()

    fun openPanel() { _panelOpen.value = true }

    fun closePanel() { _panelOpen.value = false }

    /** Reloads the queue from disk; the app-start notice awaits this so it reads recovered items. */
    suspend fun refresh(): ShareInboxState {
        services.inbox.refresh()
        return state.value
    }

    override suspend fun copyText(item: InboxItem): String? = services.inbox.copyText(item.slot, item.captureId)

    override suspend fun discard(item: InboxItem): Boolean = services.inbox.discard(item.slot, item.captureId).isRight()

    override suspend fun retryNow(item: InboxItem): RetryResult = services.drain.retryNow(item.slot, item.captureId)
}

/** Null when the host has no share pipeline (iOS/Web). */
val LocalShareInboxUi = compositionLocalOf<ShareInboxUi?> { null }
