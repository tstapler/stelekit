// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaSessionObserver(private val context: Context) : ObservedSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _bookInfo = MutableStateFlow(BookInfo.Unknown)
    override val bookInfo: StateFlow<BookInfo> = _bookInfo.asStateFlow()

    private val sessionManager: MediaSessionManager? by lazy {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateBookInfo(controllers)
    }

    override fun start() {
        if (!isNotificationListenerEnabled()) {
            _bookInfo.value = BookInfo.Unknown
            return
        }
        try {
            val notificationListenerComponent = ComponentName(context, "dev.stapler.stelekit.auto.SteleKitNotificationListenerService")
            sessionManager?.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                notificationListenerComponent,
            )
            refreshBookInfo()
        } catch (e: SecurityException) {
            _bookInfo.value = BookInfo.Unknown
        }
    }

    override fun close() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {}
        scope.cancel()
    }

    override fun refreshBookInfo() {
        scope.launch {
            if (!isNotificationListenerEnabled()) {
                _bookInfo.value = BookInfo.Unknown
                return@launch
            }
            try {
                val notificationListenerComponent = ComponentName(context, "dev.stapler.stelekit.auto.SteleKitNotificationListenerService")
                val controllers = sessionManager?.getActiveSessions(notificationListenerComponent)
                updateBookInfo(controllers)
            } catch (e: SecurityException) {
                _bookInfo.value = BookInfo.Unknown
            }
        }
    }

    private fun updateBookInfo(controllers: List<MediaController>?) {
        val controller = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()
        if (controller == null) {
            _bookInfo.value = BookInfo(isActive = false)
            return
        }
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        _bookInfo.value = extractBookInfo(metadata, playbackState)
    }

    override fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return flat.contains(context.packageName)
    }

    companion object {
        fun extractBookInfo(metadata: MediaMetadata?, playbackState: PlaybackState?): BookInfo {
            if (metadata == null) return BookInfo(isActive = false)
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
            val author = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val chapter = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            val positionMs = getCurrentPositionMs(playbackState)
            val isActive = playbackState?.state == PlaybackState.STATE_PLAYING
            return BookInfo(
                title = title,
                author = author,
                chapter = chapter,
                positionMs = positionMs,
                isActive = isActive,
            )
        }

        fun getCurrentPositionMs(playbackState: PlaybackState?): Long? {
            if (playbackState == null) return null
            if (playbackState.state == PlaybackState.STATE_NONE) return null
            val snapshotPosition = playbackState.position
            val lastUpdateTime = playbackState.lastPositionUpdateTime
            val speed = playbackState.playbackSpeed
            val elapsed = SystemClock.elapsedRealtime() - lastUpdateTime
            return snapshotPosition + (elapsed * speed).toLong()
        }
    }
}
