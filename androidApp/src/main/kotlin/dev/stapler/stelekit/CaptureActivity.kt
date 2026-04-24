// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.app.R
import dev.stapler.stelekit.tile.CaptureTileService
import dev.stapler.stelekit.ui.NoGraphPlaceholderContent
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode

/**
 * Lightweight translucent overlay for quick note capture.
 * Launched from the home screen widget, Quick Settings Tile, and Android share sheet.
 * Writes to today's journal page via DatabaseWriteActor + GraphWriter.
 */
class CaptureActivity : ComponentActivity() {

    private val viewModel: CaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as SteleKitApplication

        // Task 1.3: parse share intent before setContent (EXTRA_STREAM copy is synchronous)
        if (savedInstanceState == null) {
            val shareContent = parseShareIntent(intent)
            if (shareContent.imageLocalPath != null) {
                viewModel.initializeText("[image: ${shareContent.imageLocalPath}]\n${shareContent.text}".trim())
            } else {
                viewModel.initializeText(shareContent.text)
            }
        }

        setContent {
            StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
                if (app.graphManager == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NoGraphPlaceholderContent()
                    }
                } else {
                    CaptureScreen(
                        viewModel = viewModel,
                        onSaved = {
                            // Task 2.2: prompt tile add on first save
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                promptAddTileOnce()
                            }
                            finish()
                        },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    // Task 1.3: re-parse share extras when singleTop brings this Activity to front
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val shareContent = parseShareIntent(intent)
        if (shareContent.imageLocalPath != null) {
            viewModel.initializeText("[image: ${shareContent.imageLocalPath}]\n${shareContent.text}".trim())
        } else {
            viewModel.initializeText(shareContent.text)
        }
    }

    // Task 1.3: Bug 3 mitigation — read in mandated null-safe order: clipData → EXTRA_TEXT → EXTRA_SUBJECT
    private fun parseShareIntent(intent: Intent): ShareContent {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
            return ShareContent("", null)
        }
        val text = intent.clipData?.getItemAt(0)?.coerceToText(this)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: ""

        // Bug 2 mitigation: copy EXTRA_STREAM synchronously before any coroutine launch
        val imagePath = if (intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val streamUri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            streamUri?.let { copyStreamToPrivateStorage(it) }
        } else null

        return ShareContent(text, imagePath)
    }

    private fun copyStreamToPrivateStorage(uri: android.net.Uri): String? = try {
        val outFile = java.io.File(cacheDir, "share_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        outFile.absolutePath
    } catch (_: SecurityException) { null }
      catch (_: Exception) { null }

    // Task 2.2: prompt at most once after first successful save (API 33+)
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun promptAddTileOnce() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TILE_PROMPTED, false)) return
        prefs.edit().putBoolean(KEY_TILE_PROMPTED, true).apply()
        try {
            val sbm = getSystemService(android.app.StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, CaptureTileService::class.java),
                getString(R.string.tile_label_capture),
                Icon.createWithResource(this, R.drawable.ic_tile_capture),
                mainExecutor,
            ) { /* result callback — ignored */ }
        } catch (_: Exception) { /* OS may reject if tile already added or quota exceeded */ }
    }

    private data class ShareContent(val text: String, val imageLocalPath: String?)

    companion object {
        private const val PREFS_NAME = "stelekit_capture_prefs"
        private const val KEY_TILE_PROMPTED = "pref_tile_prompt_shown"
    }
}

@Composable
private fun CaptureScreen(
    viewModel: CaptureViewModel,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val captureText by viewModel.captureText.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(saveState) {
        when (val state = saveState) {
            is CaptureViewModel.SaveState.Saved -> onSaved()
            is CaptureViewModel.SaveState.Error -> {
                snackbarHostState.showSnackbar(
                    "Save failed — ${state.throwable?.message ?: "unknown error"}"
                )
            }
            else -> {}
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Auto-save on back if there is unsaved text
    BackHandler(
        enabled = captureText.isNotBlank() && saveState == CaptureViewModel.SaveState.Idle,
    ) {
        viewModel.save()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Translucent dim layer — tapping it dismisses (or saves if text is non-empty)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    if (captureText.isBlank()) onDismiss() else viewModel.save()
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
                // Consume clicks so they don't propagate to the dim layer
                .clickable(enabled = false, indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 40.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(2.dp),
                        ),
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Today's Journal",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = captureText,
                    onValueChange = viewModel::updateText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text("Capture a note…") },
                    minLines = 3,
                    maxLines = 8,
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = saveState != CaptureViewModel.SaveState.Saving,
                    ) { Text("Dismiss") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = viewModel::save,
                        enabled = saveState == CaptureViewModel.SaveState.Idle && captureText.isNotBlank(),
                    ) {
                        if (saveState == CaptureViewModel.SaveState.Saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Save")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
