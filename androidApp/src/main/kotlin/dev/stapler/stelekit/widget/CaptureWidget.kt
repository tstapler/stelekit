// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.widget

import android.content.Context
import android.content.Intent
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import dev.stapler.stelekit.CaptureActivity
import dev.stapler.stelekit.MainActivity
import dev.stapler.stelekit.SteleKitApplication
import dev.stapler.stelekit.app.R

// Bug 4 mitigation: no var fields — all state derived from context at render time
class CaptureWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, MEDIUM_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val ctx = LocalContext.current
            val hasGraph = (ctx.applicationContext as? SteleKitApplication)?.graphManager?.getActiveRepositorySet() != null
            val size = LocalSize.current

            if (!hasGraph) {
                NoGraphContent()
            } else if (size.width >= MEDIUM_SIZE.width) {
                MediumContent()
            } else {
                SmallContent()
            }
        }
    }

    @Composable
    private fun SmallContent() {
        val context = LocalContext.current
        val captureIntent = Intent(context, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity(captureIntent))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_tile_capture),
                contentDescription = context.getString(R.string.widget_capture_button),
                modifier = GlanceModifier.size(32.dp),
            )
        }
    }

    @Composable
    private fun MediumContent() {
        val context = LocalContext.current
        val captureIntent = Intent(context, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity(captureIntent)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = GlanceModifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_tile_capture),
                    contentDescription = null,
                    modifier = GlanceModifier.size(24.dp),
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(text = context.getString(R.string.widget_capture_button))
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
        private val SMALL_SIZE = DpSize(40.dp, 40.dp)
        private val MEDIUM_SIZE = DpSize(110.dp, 40.dp)
    }
}
