// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.stapler.stelekit.app.R
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.voice.VoiceCaptureState
import dev.stapler.stelekit.voice.VoiceErrorKind
import dev.stapler.stelekit.widget.EXTRA_BOOK_AUTHOR
import dev.stapler.stelekit.widget.EXTRA_BOOK_CHAPTER
import dev.stapler.stelekit.widget.EXTRA_BOOK_POSITION_MS
import dev.stapler.stelekit.widget.EXTRA_BOOK_TITLE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Lightweight translucent Activity launched by the voice-capture home screen widget.
 * Starts recording immediately on launch, transcribes, then auto-dismisses on success.
 */
class VoiceCaptureActivity : ComponentActivity() {

    private val viewModel: VoiceCaptureWidgetViewModel by viewModels()

    private var pendingMicPermission: CompletableDeferred<Boolean>? = null
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingMicPermission?.complete(granted)
        pendingMicPermission = null
    }

    private suspend fun requestMicPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingMicPermission = deferred
        runOnUiThread { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        return deferred.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            val bookTitle   = intent.getStringExtra(EXTRA_BOOK_TITLE)
            val bookAuthor  = intent.getStringExtra(EXTRA_BOOK_AUTHOR)
            val bookChapter = intent.getStringExtra(EXTRA_BOOK_CHAPTER)
            val bookPos     = if (intent.hasExtra(EXTRA_BOOK_POSITION_MS))
                intent.getLongExtra(EXTRA_BOOK_POSITION_MS, -1L).takeIf { it >= 0 }
            else null
            viewModel.initialize(::requestMicPermission, bookTitle, bookAuthor, bookChapter, bookPos)
        }

        setContent {
            StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
                VoiceCaptureScreen(
                    viewModel = viewModel,
                    onDismiss = ::finish,
                )
            }
        }
    }
}

@Composable
private fun VoiceCaptureScreen(
    viewModel: VoiceCaptureWidgetViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    // Auto-dismiss after confirmation flash; Close button is always available for early exit
    LaunchedEffect(state) {
        if (state is VoiceCaptureState.Done) {
            delay(3000)
            onDismiss()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Translucent dim layer — interactive only during Recording (stop) and Done (close)
        val overlayInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    enabled = state is VoiceCaptureState.Recording || state is VoiceCaptureState.Done,
                    indication = null,
                    interactionSource = overlayInteractionSource,
                ) {
                    when (state) {
                        is VoiceCaptureState.Recording -> viewModel.onMicTapped()
                        is VoiceCaptureState.Done      -> onDismiss()
                        else                           -> Unit
                    }
                },
        )

        // Bottom-anchored capture sheet
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .clickable(enabled = false, indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(2.dp),
                        ),
                )
                Spacer(Modifier.height(16.dp))

                when (val s = state) {
                    VoiceCaptureState.Idle -> {
                        IdleContent(onCancel = { viewModel.cancel(); onDismiss() })
                    }
                    VoiceCaptureState.Recording -> {
                        RecordingContent(onStop = viewModel::onMicTapped, onCancel = { viewModel.cancel(); onDismiss() })
                    }
                    VoiceCaptureState.Transcribing -> {
                        ProcessingContent(label = "Transcribing…")
                    }
                    VoiceCaptureState.Formatting -> {
                        ProcessingContent(label = "Formatting…")
                    }
                    is VoiceCaptureState.Done -> {
                        DoneContent(state = s, onDismiss = onDismiss)
                    }
                    is VoiceCaptureState.Error -> {
                        ErrorContent(
                            error = s,
                            onRetry = { viewModel.dismissError(); viewModel.onMicTapped() },
                            onDismiss = onDismiss,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun IdleContent(onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun RecordingContent(onStop: () -> Unit, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Recording…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap the mic or the button below to finish",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(32.dp)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = "Stop recording",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(88.dp))
        }
    }
}

@Composable
private fun ProcessingContent(label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DoneContent(state: VoiceCaptureState.Done, onDismiss: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val titleText = if (state.isLikelyTruncated) {
            "⚠️  Saved — recording may be incomplete"
        } else {
            "✓  Note saved"
        }
        val titleColor = if (state.isLikelyTruncated) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.primary
        }
        Text(
            text = titleText,
            style = MaterialTheme.typography.titleMedium,
            color = titleColor,
        )
        Spacer(Modifier.height(4.dp))
        val destination = if (state.savedToPageName != null) {
            "Saved to [[${state.savedToPageName}]]"
        } else {
            "Saved to today's journal"
        }
        Text(
            text = destination,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.transcriptPageTitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Full transcript: [[${state.transcriptPageTitle}]]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onDismiss) { Text("Close") }
    }
}

@Composable
private fun ErrorContent(
    error: VoiceCaptureState.Error,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
            when (error.kind) {
                VoiceErrorKind.PERMISSION_DENIED -> {
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                        onDismiss()
                    }) { Text("Open Settings") }
                }
                VoiceErrorKind.NO_GRAPH -> {
                    Button(onClick = {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        onDismiss()
                    }) { Text("Open SteleKit") }
                }
                VoiceErrorKind.GENERIC -> {
                    Button(onClick = onRetry) { Text("Record Again") }
                }
            }
        }
    }
}
