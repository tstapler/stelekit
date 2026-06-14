// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import dev.stapler.stelekit.MainActivity
import dev.stapler.stelekit.SteleKitApplication
import dev.stapler.stelekit.VoiceCaptureActivity
import dev.stapler.stelekit.app.R
import dev.stapler.stelekit.auto.SteleKitNotificationListenerService

// Keys used to pass audiobook context into VoiceCaptureActivity
const val EXTRA_BOOK_TITLE = "extra_book_title"
const val EXTRA_BOOK_AUTHOR = "extra_book_author"
const val EXTRA_BOOK_CHAPTER = "extra_book_chapter"
const val EXTRA_BOOK_POSITION_MS = "extra_book_position_ms"

// Bug 4 mitigation: no var fields — all state derived from context at render time
class VoiceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, MEDIUM_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        (context.applicationContext as? SteleKitApplication)?.graphManager?.awaitPendingMigration()
        val bookInfo = queryCurrentBook(context)
        provideContent {
            val ctx = LocalContext.current
            val hasGraph = run {
                val gm = (ctx.applicationContext as? SteleKitApplication)?.graphManager
                val repoSet = gm?.getActiveRepositorySet()
                val activeId = gm?.getActiveGraphId()
                val graphInfo = activeId?.let { gm.getGraphInfo(it) }
                repoSet != null && graphInfo?.isParanoidMode != true
            }
            val size = LocalSize.current

            if (!hasGraph) {
                NoGraphContent()
            } else if (size.width >= MEDIUM_SIZE.width) {
                MediumContent(bookInfo)
            } else {
                SmallContent(bookInfo)
            }
        }
    }

    @Composable
    private fun SmallContent(bookInfo: CurrentBook?) {
        val context = LocalContext.current
        val voiceIntent = voiceIntent(context, bookInfo)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity(voiceIntent))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_mic),
                contentDescription = context.getString(R.string.widget_voice_button),
                modifier = GlanceModifier.size(32.dp),
            )
        }
    }

    @Composable
    private fun MediumContent(bookInfo: CurrentBook?) {
        val context = LocalContext.current
        val voiceIntent = voiceIntent(context, bookInfo)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity(voiceIntent)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = GlanceModifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_mic),
                        contentDescription = null,
                        modifier = GlanceModifier.size(24.dp),
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(text = context.getString(R.string.widget_voice_button))
                }
                if (bookInfo != null) {
                    Spacer(modifier = GlanceModifier.width(0.dp)) // vertical spacer via padding
                    Text(
                        text = bookInfo.title,
                        modifier = GlanceModifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun NoGraphContent() {
        val context = LocalContext.current
        val mainIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity(mainIntent))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = context.getString(R.string.no_graph_action))
        }
    }

    companion object {
        private val SMALL_SIZE  = DpSize(40.dp, 40.dp)
        private val MEDIUM_SIZE = DpSize(110.dp, 40.dp)

        /** One-shot MediaSession read for the widget render pass. Returns null when no book is playing
         *  or the notification listener permission hasn't been granted. */
        internal fun queryCurrentBook(context: Context): CurrentBook? {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return null
            if (!flat.contains(context.packageName)) return null

            return try {
                val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                        as? MediaSessionManager ?: return null
                val component = ComponentName(context, SteleKitNotificationListenerService::class.java)
                val controllers = manager.getActiveSessions(component)
                val active = controllers.firstOrNull {
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
                } ?: controllers.firstOrNull() ?: return null

                val meta = active.metadata ?: return null
                val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?: return null

                val ps = active.playbackState
                val positionMs = if (ps != null && ps.state != PlaybackState.STATE_NONE) {
                    ps.position + ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
                } else null

                CurrentBook(
                    title = title,
                    author = meta.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    chapter = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    positionMs = positionMs,
                )
            } catch (_: SecurityException) {
                null
            }
        }

        private fun voiceIntent(context: Context, bookInfo: CurrentBook?): Intent =
            Intent(context, VoiceCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply {
                    if (bookInfo != null) {
                        putExtra(EXTRA_BOOK_TITLE, bookInfo.title)
                        putExtra(EXTRA_BOOK_AUTHOR, bookInfo.author)
                        putExtra(EXTRA_BOOK_CHAPTER, bookInfo.chapter)
                        if (bookInfo.positionMs != null) putExtra(EXTRA_BOOK_POSITION_MS, bookInfo.positionMs)
                    }
                }
    }
}

/** Lightweight book snapshot for the widget render pass — avoids importing the Auto module's BookInfo. */
data class CurrentBook(
    val title: String,
    val author: String?,
    val chapter: String?,
    val positionMs: Long?,
)
