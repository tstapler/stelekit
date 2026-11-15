// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.Settings

internal const val DRAG_HINT_COUNT_KEY = "annotator_drag_hint_count"
internal const val DRAG_HINT_MAX_SHOWS = 3
internal const val FINE_TUNE_HINT = "Drag an end to fine-tune"
internal const val DISTANCE_DRAG_PROMPT = "Distance: drag from point A to B"
internal const val POINT_A_PLACED_PROMPT = "Point A placed. Tap point B"

internal fun annotatorDragHintCount(settings: Settings?): Int =
    settings?.getString(DRAG_HINT_COUNT_KEY, "0")?.toIntOrNull() ?: 0

/** The fine-tune hint is a first-three-times nudge, persisted so it does not nag across sessions. */
internal fun shouldShowDragHint(settings: Settings?): Boolean = annotatorDragHintCount(settings) < DRAG_HINT_MAX_SHOWS

internal fun recordDragHint(settings: Settings?) {
    settings?.putString(DRAG_HINT_COUNT_KEY, (annotatorDragHintCount(settings) + 1).toString())
}

/** The prompt for the armed tool; DISTANCE has its own drag wording, the others keep their tap prompts. */
internal fun toolPromptFor(tool: AnnotationTool, pendingPoints: Int): String? = when {
    tool == AnnotationTool.SELECT -> null
    tool == AnnotationTool.DISTANCE && pendingPoints == 0 -> DISTANCE_DRAG_PROMPT
    tool == AnnotationTool.DISTANCE && pendingPoints == 1 -> POINT_A_PLACED_PROMPT
    tool == AnnotationTool.AREA && pendingPoints >= 3 -> "Tap near start to close, or tap to add vertex"
    tool == AnnotationTool.AREA && pendingPoints == 1 -> "Tap to add more vertices"
    tool == AnnotationTool.AREA -> "Tap to place first vertex"
    tool == AnnotationTool.ANGLE && pendingPoints == 1 -> "Tap to place vertex"
    tool == AnnotationTool.ANGLE && pendingPoints == 2 -> "Tap to complete angle"
    pendingPoints == 0 -> "Tap to place start point"
    pendingPoints == 1 -> "Tap to place end point"
    else -> null
}

/** The single strip message chosen by [StripPriority]; lower levels are dropped, never queued. */
internal fun stripMessage(
    errorText: String?,
    toolPrompt: String?,
    fineTuneHint: Boolean,
    statusText: String?,
    voiceHint: Boolean = false,
): Pair<StripPriority, String?> {
    val level = StripPriority.resolve(
        hasError = errorText != null,
        hasToolPrompt = toolPrompt != null,
        hasFirstTimeHint = fineTuneHint,
        hasStatusText = statusText != null,
        hasVoiceHint = voiceHint,
    )
    val text = when (level) {
        StripPriority.ERROR -> errorText
        StripPriority.TOOL_PROMPT -> toolPrompt
        StripPriority.FIRST_TIME_HINT -> FINE_TUNE_HINT
        StripPriority.STATUS_TEXT -> statusText
        StripPriority.VOICE_HINT -> VOICE_HINT_TEXT
        StripPriority.IDLE -> null
    }
    return level to text
}

@Composable
internal fun EditorStatusStrip(
    text: String,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xCC000000),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(vertical = 6.dp))
            if (onCancel != null) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)) { Text("Cancel") }
            }
        }
    }
}
