// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.voice.VoiceCaptureState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

private val ColorSuccess = Color(0xFF4CAF50)

@Composable
fun VoiceCaptureButton(
    state: VoiceCaptureState,
    onTap: () -> Unit,
    onDismissError: () -> Unit,
    onAutoReset: () -> Unit = {},
    amplitudeFlow: Flow<Float>? = null,
) {
    when (state) {
        VoiceCaptureState.Idle -> {
            FloatingActionButton(onClick = onTap) {
                Icon(Icons.Default.Mic, contentDescription = "Start recording")
            }
        }

        VoiceCaptureState.Recording -> {
            val scale = if (amplitudeFlow != null) {
                // Amplitude-driven pulse: map RMS [0,1] → scale [1.0, 1.35]
                val animatable = remember { Animatable(1f) }
                LaunchedEffect(Unit) {
                    amplitudeFlow.collect { rms ->
                        animatable.animateTo(1f + (rms * 0.35f).coerceIn(0f, 0.35f), tween(80))
                    }
                }
                animatable.value
            } else {
                // Fixed-period fallback when no amplitude data available
                val infiniteTransition = rememberInfiniteTransition()
                val fixedScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600),
                        repeatMode = RepeatMode.Reverse,
                    ),
                )
                fixedScale
            }
            FloatingActionButton(
                onClick = onTap,
                containerColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.scale(scale),
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop recording")
            }
        }

        VoiceCaptureState.Transcribing, VoiceCaptureState.Formatting -> {
            val label = if (state == VoiceCaptureState.Transcribing) "Transcribing…" else "Formatting…"
            FloatingActionButton(
                onClick = {},
                modifier = Modifier.semantics { contentDescription = label },
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                )
            }
        }

        is VoiceCaptureState.Done -> {
            LaunchedEffect(state) {
                delay(3_000)
                onAutoReset()
            }
            if (state.isLikelyTruncated) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = "Note may be incomplete",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    FloatingActionButton(
                        onClick = {},
                        containerColor = MaterialTheme.colorScheme.tertiary,
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "Note saved — may be incomplete")
                    }
                }
            } else {
                FloatingActionButton(
                    onClick = {},
                    containerColor = ColorSuccess,
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Note saved")
                }
            }
        }

        is VoiceCaptureState.Error -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = state.message,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                FloatingActionButton(
                    onClick = onDismissError,
                    containerColor = MaterialTheme.colorScheme.error,
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Error: ${state.message} — tap to dismiss")
                }
            }
        }
    }
}
