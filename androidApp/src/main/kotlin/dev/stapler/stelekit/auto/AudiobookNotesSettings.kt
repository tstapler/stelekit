// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Audiobook Notes section for the phone settings screen.
 * Covers E6.S2: quick tag list, snippet duration, note destination.
 */
@Composable
fun AudiobookNotesSettings(settings: AudiobookAutoSettings) {
    val context = LocalContext.current

    var tags by remember { mutableStateOf(settings.getQuickTags()) }
    var destination by remember { mutableStateOf(settings.getNoteDestination()) }
    var snippetDuration by remember { mutableStateOf(settings.getSnippetDurationSeconds()) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {

        // Media Access permission
        Text("Android Auto Integration", fontSize = 16.sp)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant media access (for book detection)")
        }

        // Developer-mode guidance for F-Droid / sideloaded users.
        // Android Auto hides apps not installed from the Play Store unless the user
        // explicitly enables "Unknown sources" via developer mode.
        // Steps verified against Android Auto 13.x, 2026-06 — re-verify on major AA updates.
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "If SteleKit does not appear in Android Auto (F-Droid / sideloaded build):",
            fontSize = 13.sp
        )
        Text(
            "1. Open the Android Auto app\n" +
            "2. Tap the version number 10 times to unlock developer settings\n" +
            "3. Enable \"Unknown sources\"\n" +
            "4. Reconnect to your car",
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Tags
        Text("Quick Tags", fontSize = 16.sp)
        Spacer(modifier = Modifier.height(4.dp))
        tags.forEachIndexed { index, tag ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = tag,
                    onValueChange = { newValue ->
                        val updated = tags.toMutableList()
                        updated[index] = newValue
                        tags = updated
                        settings.setQuickTags(updated)
                    },
                    label = { Text("Tag ${index + 1}") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        TextButton(
            onClick = {
                tags = AudiobookAutoSettings.DEFAULT_QUICK_TAGS
                settings.setQuickTags(AudiobookAutoSettings.DEFAULT_QUICK_TAGS)
            }
        ) {
            Text("Reset to defaults")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Snippet Duration (phone-only label)
        Text("Audio Snippet Duration (Phone use only — Auto saves position)", fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 30, 45, 60).forEach { seconds ->
                OutlinedButton(
                    onClick = {
                        snippetDuration = seconds
                        settings.setSnippetDurationSeconds(seconds)
                    },
                    colors = if (snippetDuration == seconds)
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    else
                        ButtonDefaults.outlinedButtonColors(),
                ) {
                    Text("${seconds}s")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Note Destination
        Text("Default Note Destination", fontSize = 16.sp)
        Spacer(modifier = Modifier.height(4.dp))
        NoteDestination.entries.forEach { dest ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = destination == dest,
                    onClick = {
                        destination = dest
                        settings.setNoteDestination(dest)
                    }
                )
                Text(
                    text = when (dest) {
                        NoteDestination.JOURNAL_ONLY -> "Journal only"
                        NoteDestination.BOOK_PAGE_ONLY -> "Book page only"
                        NoteDestination.BOTH -> "Both (journal + book page)"
                    }
                )
            }
        }
    }
}
