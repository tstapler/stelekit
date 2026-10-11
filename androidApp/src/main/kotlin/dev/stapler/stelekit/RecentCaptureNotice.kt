// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-line fallback for the Back auto-save toast (Task 4.2.1h): at the next app start, "Last share
 * saved to Personal graph" with Undo and Change while that capture is still the newest one recorded.
 * Shown once per capture. "Still the last one added" is approximated by "newest record, not undone";
 * edits made to the block since then are caught by the undo's own hash check.
 */
@Composable
fun RecentCaptureNotice(app: SteleKitApplication, modifier: Modifier = Modifier) {
    var record by remember { mutableStateOf(app.recentCaptures.pendingNotice()) }
    val shown = record ?: return
    val context = LocalContext.current
    LaunchedEffect(shown.captureId) { app.recentCaptures.markNoticed(shown.captureId) }

    Surface(
        modifier = modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Last share saved to ${shown.graphName}", modifier = Modifier.weight(1f))
            TextButton(onClick = { undo(app, context, shown, reopen = false) { record = null } }) { Text("Undo") }
            TextButton(onClick = { undo(app, context, shown, reopen = true) { record = null } }) { Text("Change") }
            TextButton(onClick = { record = null }) { Text("Dismiss") }
        }
    }
}

private fun undo(app: SteleKitApplication, context: Context, record: RecentCapture, reopen: Boolean, onDone: () -> Unit) {
    app.appScope.launch {
        val ok = app.undoCapture(record)
        withContext(Dispatchers.Main) {
            if (!ok) {
                Toast.makeText(context, "Couldn't undo — the note was edited", Toast.LENGTH_LONG).show()
                return@withContext
            }
            onDone()
            if (reopen) context.startActivity(reopenIntent(context, record.text))
        }
    }
}

private fun reopenIntent(context: Context, text: String) =
    Intent(context, CaptureActivity::class.java)
        .setAction(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(CaptureActivity.EXTRA_OPEN_DESTINATION_MENU, true)
