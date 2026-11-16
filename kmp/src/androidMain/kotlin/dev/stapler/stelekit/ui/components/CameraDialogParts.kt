// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal const val CAMERA_STARTING = "Starting camera..."
internal const val CAMERA_UNAVAILABLE = "Camera isn't available. Another app may be using it."
internal const val CAPTURE_FAILED = "Couldn't take the photo. Try again."
internal const val WIDE_ANGLE_HINT = "Wide angle distorts edges; fine for tight spaces"
internal const val NO_SEPARATE_ULTRAWIDE = "Ultra-wide isn't available as a separate lens on this device"
internal const val LENS_UNAVAILABLE = "Ultra-wide isn't available on this device right now"

/** Neutral surface shown until the first bind completes; the shutter and chips stay disabled meanwhile. */
@Composable
internal fun CameraStartingOverlay(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Color.White)
        Text(CAMERA_STARTING, color = Color.White, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Shown when no lens could be bound; always offers a way out. */
@Composable
internal fun CameraUnavailableScreen(onRetry: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(CAMERA_UNAVAILABLE, color = Color.White, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        }
    }
}

/** A short polite status line (lens failed, capture failed, no separate ultra-wide). */
@Composable
internal fun CameraStatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The dismissible wide-angle hint; dismissal is persisted by the caller. */
@Composable
internal fun WideAngleHint(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(WIDE_ANGLE_HINT, color = Color.White, modifier = Modifier.padding(start = 16.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
    }
}
