package dev.stapler.stelekit.ui.transfer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.toUiMessage
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import dev.stapler.stelekit.transfer.qrcode.ScanHint
import dev.stapler.stelekit.ui.annotate.CameraPermissionRationaleDialog

/** Continuous-`Scanning` duration after which the one-time handheld-fatigue tip appears (Story 3.2.3). */
private const val HANDHELD_FATIGUE_TIP_THRESHOLD_MS = 15_000L

/**
 * Full-screen QR receiver (Story 3.2.3): camera preview + reticle, non-linear
 * "Receiving… (N fragments)" progress, hint-driven guidance copy, a one-time-per-session
 * handheld-fatigue tip, and a one-time-ever first-use explainer.
 *
 * [onFragmentTick] mirrors [dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel]'s
 * `onHapticFeedback` pattern (`LocalHapticFeedback.current.performHapticFeedback(...)`) — directly
 * injectable so tests can substitute a plain counter instead of a real haptic call. Purely
 * additive: the "Receiving… (N fragments)" text line is always rendered and always sufficient on
 * its own, per Story 3.2.3's binding AC (haptics-disabled devices must not lose progress info).
 */
@Composable
fun QrDecodeScreen(
    viewModel: QrDecodeViewModel,
    settings: QrTransferSettings,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onImportFromFile: (() -> Unit)? = null,
    onFragmentTick: () -> Unit = LocalHapticFeedback.current.let { haptic ->
        { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    },
) {
    val state by viewModel.state.collectAsState()
    val collisionPrompt by viewModel.collisionPrompt.collectAsState()
    val pendingCollisionChoice by viewModel.pendingCollisionChoice.collectAsState()
    val concurrentTransferWarningNonce by viewModel.concurrentTransferWarningNonce.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.start()
    }

    // Story 3.3.4 binding AC: a dropped-TransferId frame during an active session MUST surface a
    // visible, transient message — never a silent drop — without disrupting the active session.
    var showConcurrentTransferWarning by remember { mutableStateOf(false) }
    LaunchedEffect(concurrentTransferWarningNonce) {
        if (concurrentTransferWarningNonce == 0) return@LaunchedEffect
        showConcurrentTransferWarning = true
        kotlinx.coroutines.delay(3_000L)
        showConcurrentTransferWarning = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showConcurrentTransferWarning) {
            ConcurrentTransferWarningBanner()
            Spacer(modifier = Modifier.height(8.dp))
        }

        when (val s = state) {
            QrDecodeUiState.Idle -> {
                Spacer(modifier = Modifier.height(64.dp))
                Text("Starting…", style = MaterialTheme.typography.titleMedium)
            }

            is QrDecodeUiState.PreflightFailed -> PreflightFailedContent(
                reason = s.reason,
                onImportFromFile = onImportFromFile,
                onBack = onDismiss,
            )

            is QrDecodeUiState.Scanning -> ScanningContent(
                state = s,
                settings = settings,
                onHapticTick = onFragmentTick,
                onCancel = { viewModel.cancel(); onDismiss() },
            )

            QrDecodeUiState.Reassembling -> {
                Spacer(modifier = Modifier.height(64.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Checking…", style = MaterialTheme.typography.titleMedium)
                Text("Verifying the received data", style = MaterialTheme.typography.bodyMedium)
            }

            QrDecodeUiState.Importing -> {
                Spacer(modifier = Modifier.height(64.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Importing…", style = MaterialTheme.typography.titleMedium)
            }

            is QrDecodeUiState.Success -> {
                Spacer(modifier = Modifier.height(64.dp))
                Text("✅  Imported!", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("\"${s.pageName.value}\" was added to your graph.", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onDismiss) { Text("Done") }
            }

            is QrDecodeUiState.Failed -> {
                Spacer(modifier = Modifier.height(64.dp))
                Text(s.error.toUiMessage(), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text("Close") }
                    Button(onClick = onDismiss) { Text("Try again") }
                }
            }

            QrDecodeUiState.Cancelled -> {
                Spacer(modifier = Modifier.height(64.dp))
                Text("Import cancelled", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onDismiss) { Text("Close") }
            }
        }
    }

    val prompt = collisionPrompt
    if (prompt != null) {
        QrImportConfirmDialog(
            existingName = prompt.existingName.value,
            pendingChoice = pendingCollisionChoice,
            onKeepBoth = { viewModel.resolveCollision(QrImportService.CollisionChoice.KEEP_BOTH) },
            onOverwrite = { viewModel.resolveCollision(QrImportService.CollisionChoice.OVERWRITE) },
            onCancel = { viewModel.cancel(); onDismiss() },
        )
    }
}

/** Transient, visible warning for a dropped second sender (Story 3.3.4 binding AC). */
@Composable
private fun ConcurrentTransferWarningBanner() {
    Text(
        "Another transfer started — ignoring it",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
            .padding(8.dp),
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * Bug 1 fix — UX criterion 11: distinct copy AND icon per [reason], never the shared generic
 * "Camera unavailable" text regardless of cause. [DomainError.SensorError.PermissionDenied]
 * additionally surfaces [CameraPermissionRationaleDialog] (reusing the existing camera-permission
 * dialog shape, Story 3.2.4) — "Not now" dismisses to [onBack] (the working alternative one level
 * up, e.g. [dev.stapler.stelekit.ui.screens.ImportScreen]'s file-import option), never leaving the
 * user stuck on the rationale dialog (UX criterion 6).
 */
@Composable
private fun PreflightFailedContent(
    reason: DomainError.SensorError,
    onImportFromFile: (() -> Unit)?,
    onBack: () -> Unit,
) {
    if (reason is DomainError.SensorError.PermissionDenied) {
        var rationaleVisible by remember { mutableStateOf(true) }
        if (rationaleVisible) {
            CameraPermissionRationaleDialog(
                onNotNow = { rationaleVisible = false; onBack() },
                // Re-requesting permission needs a fresh coordinator (a new OS prompt) — simplest
                // safe action here is the same working alternative "Not now" offers, rather than
                // silently retrying against an already-terminated coordinator.
                onContinue = { rationaleVisible = false; onBack() },
            )
        }
    }
    // Single-emitter top level (detekt MultipleEmitters) — the caller (QrDecodeScreen) already
    // provides the outer Column, this nested one just groups this function's own content.
    Column {
        if (reason is DomainError.SensorError.PermissionDenied) {
            Spacer(modifier = Modifier.height(64.dp))
            Text("📷🔒  Camera permission needed", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "SteleKit needs camera access to scan a transfer code from another device. You can allow it in Settings, or import from a file instead.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Spacer(modifier = Modifier.height(64.dp))
            Text("📷🚫  Camera unavailable", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "This device doesn't have a usable camera for scanning transfer codes. Try importing from a file instead.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        if (onImportFromFile != null) {
            Button(onClick = onImportFromFile) { Text("Import from file") }
            Spacer(modifier = Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun ColumnScope.ScanningContent(
    state: QrDecodeUiState.Scanning,
    settings: QrTransferSettings,
    onHapticTick: () -> Unit,
    onCancel: () -> Unit,
) {
    var explainerDismissed by remember { mutableStateOf(settings.seenDecoderExplainer) }
    var fatigueTipDismissed by remember { mutableStateOf(false) }
    var fatigueTipVisible by remember { mutableStateOf(false) }
    var lastTickedFragments by remember { mutableIntStateOf(0) }

    // rememberUpdatedState so this effect only restarts when state.uniqueFragments changes (its
    // key) — never when onHapticTick's lambda identity changes across recompositions, which
    // would otherwise fire the tick logic again for the same fragment count.
    val currentOnHapticTick by rememberUpdatedState(onHapticTick)

    // Haptic tick is purely additive (Story 3.2.3 binding AC) — the text line below always
    // renders the fragment count regardless of whether this effect ever fires.
    LaunchedEffect(state.uniqueFragments) {
        if (state.uniqueFragments > lastTickedFragments) {
            lastTickedFragments = state.uniqueFragments
            currentOnHapticTick()
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(HANDHELD_FATIGUE_TIP_THRESHOLD_MS)
        if (!fatigueTipDismissed) fatigueTipVisible = true
    }

    if (!explainerDismissed) {
        DecoderFirstUseExplainerBanner(
            onDismiss = {
                settings.seenDecoderExplainer = true
                explainerDismissed = true
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    CameraPreviewReticle(state = state)

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Receiving… (${state.uniqueFragments} fragments)",
        style = MaterialTheme.typography.bodyMedium,
    )

    // Bar only animates on genuine new-fragment progress — never a smooth indeterminate
    // animation implying progress that hasn't happened (UX criterion 8).
    LinearProgressIndicator(
        progress = { if (state.uniqueFragments == 0) 0f else 1f - (1f / (state.uniqueFragments + 1)) },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))
    HintCopy(hint = state.hint, stalledSeconds = state.stalledSeconds)

    if (fatigueTipVisible && !fatigueTipDismissed) {
        Spacer(modifier = Modifier.height(8.dp))
        HandheldFatigueTip(onDismiss = { fatigueTipDismissed = true; fatigueTipVisible = false })
    }

    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun HintCopy(hint: ScanHint?, stalledSeconds: Int) {
    val text = when (hint) {
        ScanHint.Stalled -> "Not receiving new data — move closer or adjust the angle."
        ScanHint.WrongCode -> "That's not a SteleKit transfer code."
        ScanHint.LowLight -> "Too dark to scan — add light."
        null -> if (stalledSeconds >= 8) {
            "Not receiving new data — move closer or adjust the angle."
        } else {
            null
        }
    }
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Bug 2 fix (Story 3.2.3 AC "camera preview + reticle"): renders the real live camera feed
 * ([PlatformCameraPreview]) behind the reticle border/corner-mark overlay, so the user can see
 * what the camera sees to aim it at the sender's screen — previously this was a static bordered
 * [Box] with no camera feed at all.
 *
 * Validation.md criterion 14 fix: the inner reticle mark and the label below it both switch on
 * [QrDecodeUiState.Scanning.isLockedOn] — a filled box + checkmark icon + "Locked on" text once at
 * least one fragment is admitted and no diagnostic hint is active, vs an outlined-only box +
 * magnifying-glass icon + "Searching…" text otherwise. Shape and icon differ, not just color, so
 * the state reads correctly under a protanopia/deuteranopia simulation.
 */
@Composable
private fun CameraPreviewReticle(state: QrDecodeUiState.Scanning) {
    val isLockedOn = state.isLockedOn
    val statusLabel = if (isLockedOn) "Locked on" else "Searching…"
    val indicatorDescription = if (isLockedOn) "Locked on indicator" else "Searching indicator"
    val indicatorIcon = if (isLockedOn) Icons.Filled.CheckCircle else Icons.Filled.Search
    val indicatorColor = if (isLockedOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Fractional width (not fillMaxWidth) keeps this from dominating the viewport on short
        // screens/small test windows and pushing the progress/hint text below the visible area —
        // same fraction as QrEncodeScreen's InsetQrCard (INSET_CARD_WIDTH_FRACTION).
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription =
                        "Point camera at the SteleKit transfer code, ${state.uniqueFragments} fragments received"
                },
            contentAlignment = Alignment.Center,
        ) {
            PlatformCameraPreview(modifier = Modifier.matchParentSize())
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    // Shape distinction, not just color: locked-on is a solid-filled corner box,
                    // searching is outline-only — genuinely different silhouettes.
                    .then(
                        if (isLockedOn) {
                            Modifier.background(indicatorColor.copy(alpha = 0.25f))
                        } else {
                            Modifier
                        },
                    )
                    .border(
                        width = if (isLockedOn) 3.dp else 2.dp,
                        color = indicatorColor,
                        shape = RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = indicatorIcon,
                    contentDescription = indicatorDescription,
                    tint = indicatorColor,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = indicatorColor)
    }
}

@Composable
private fun HandheldFatigueTip(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            "Tip: try propping your phone against something stable for a steadier scan.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onDismiss) { Text("Got it") }
    }
}

@Composable
private fun DecoderFirstUseExplainerBanner(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            "Point your camera at the other device's screen — this is a continuous scan, not a photo, and may take 30–60 seconds.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onDismiss) { Text("Got it") }
    }
}
