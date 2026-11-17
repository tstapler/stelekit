// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchLoadingStrings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

object LoadingTags {
    const val INDICATOR = "sketch.loading"
    const val NAME = "sketch.loading.name"
    const val BAR = "sketch.loading.bar"
    const val CANCEL = "sketch.loading.cancel"
    const val ANNOUNCE = "sketch.loading.announce"
    const val PLACEHOLDER = "sketch.loading.placeholder"
}

/** When a long operation shows its indicator (UX-94) and what is announced; pure, so the rule is tested without a screen. */
object LoadingPolicy {
    /** An operation that is still running after this long shows an indicator; a faster one never flashes it. */
    const val SHOW_AFTER_MS = 200L

    fun shouldShow(elapsedMs: Long): Boolean = elapsedMs > SHOW_AFTER_MS

    /** The one polite string at the start of a long operation: its name. Progress updates are never announced. */
    fun startText(name: String): String = SketchLoadingStrings.started(name)

    /** The one polite string at its end, spoken only if the start was spoken (an operation that never showed ends silently). */
    fun endText(name: String, startWasShown: Boolean): String = if (startWasShown) SketchLoadingStrings.finished(name) else ""

    /** The visible line: the name alone while indeterminate, with the percentage once progress is known. */
    fun label(name: String, progress: Float?): String =
        if (progress == null) name else SketchLoadingStrings.percent(name, (progress.coerceIn(0f, 1f) * PERCENT).roundToInt())

    private const val PERCENT = 100
}

/**
 * The labelled progress of a long operation: the operation [name], a determinate bar when [progress] is given or an
 * indeterminate one otherwise (static under reduced motion, through [MotionPolicy]), and Cancel where the operation can stop.
 */
@Composable
fun LoadingIndicator(name: String, progress: (() -> Float)?, onCancel: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.testTag(LoadingTags.INDICATOR), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(LoadingPolicy.label(name, progress?.invoke()), Modifier.testTag(LoadingTags.NAME), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MotionPolicy.Bar(progress, Modifier.weight(1f).testTag(LoadingTags.BAR))
            if (onCancel != null) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.defaultMinSize(minWidth = TARGET, minHeight = TARGET).testTag(LoadingTags.CANCEL).semantics {
                        contentDescription = SketchLoadingStrings.cancelName(name)
                    },
                ) { Text(SketchLoadingStrings.CANCEL) }
            }
        }
    }
}

/**
 * Shows [LoadingIndicator] once [active] has lasted [LoadingPolicy.SHOW_AFTER_MS], announces the start once and the end once
 * through one polite live region, and shows nothing for an operation that finishes sooner (Task 3.6.8c, UX-94).
 */
@Composable
fun LoadingGate(active: Boolean, name: String, progress: (() -> Float)?, onCancel: (() -> Unit)?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    var announcement by remember { mutableStateOf("") }
    LaunchedEffect(active) {
        if (active) {
            delay(LoadingPolicy.SHOW_AFTER_MS)
            shown = true
            announcement = LoadingPolicy.startText(name)
        } else {
            announcement = LoadingPolicy.endText(name, startWasShown = shown)
            shown = false
        }
    }
    Box(modifier) {
        Box(Modifier.size(1.dp).testTag(LoadingTags.ANNOUNCE).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
        if (active && shown) LoadingIndicator(name, progress, onCancel)
    }
}

/**
 * The neutral block an embed thumbnail shows while its sketch loads (UX-94): a calm surface, never a blank gap, with a soft
 * pulse that is a constant under reduced motion. Its description is the loading text for a screen reader.
 */
@Composable
fun ThumbnailPlaceholder(modifier: Modifier = Modifier) {
    val pulse by MotionPolicy.pulse("thumbnail")
    val label = SketchLoadingStrings.THUMBNAIL
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = PLACEHOLDER_HEIGHT)
            .alpha(BASE_ALPHA + PULSE_ALPHA * pulse)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(LoadingTags.PLACEHOLDER)
            .semantics { contentDescription = label },
    )
}

private val TARGET = 44.dp
private val PLACEHOLDER_HEIGHT = 64.dp
private const val BASE_ALPHA = 0.6f
private const val PULSE_ALPHA = 0.4f
