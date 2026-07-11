package dev.stapler.stelekit.ui.transfer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.toUiMessage
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.transfer.qrcode.QrMatrix
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings

/**
 * Full-screen QR sender (Story 3.1.3, ADR-004). The animated QR renders in a bordered inset card
 * (≤60% viewport area) on a static background — never true full-screen — satisfying WCAG 2.3.1's
 * small-area exemption alongside the ≤3fps rate ceiling enforced entirely inside
 * [QrEncodeViewModel] (this composable never drives frame pacing itself).
 *
 * [pageName]/[blockCount] are supplied by the caller (already known from the open page) rather
 * than sourced from ViewModel state, since [QrEncodeUiState] carries only transfer-progress
 * fields, not page metadata.
 */
@Composable
fun QrEncodeScreen(
    pageUuid: PageUuid,
    pageName: String,
    blockCount: Int,
    viewModel: QrEncodeViewModel,
    settings: QrTransferSettings,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()

    LaunchedEffect(pageUuid) {
        viewModel.start(pageUuid)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            QrEncodeUiState.Idle, QrEncodeUiState.Serializing -> {
                Spacer(modifier = Modifier.height(64.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Preparing…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Serializing \"$pageName\"",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(32.dp))
                OutlinedButton(onClick = { viewModel.cancel(); onDismiss() }) { Text("Cancel") }
            }

            is QrEncodeUiState.Displaying -> DisplayingContent(
                state = s,
                pageName = pageName,
                blockCount = blockCount,
                currentFrame = currentFrame,
                settings = settings,
                onAdvance = viewModel::advanceFrame,
                onDone = viewModel::complete,
                onCancel = { viewModel.cancel(); onDismiss() },
            )

            is QrEncodeUiState.Paused -> {
                Spacer(modifier = Modifier.height(32.dp))
                InsetQrCard(matrix = currentFrame, contentDescription = "Paused, frame ${s.frameIndex + 1} of about ${s.chunkCount}")
                Spacer(modifier = Modifier.height(16.dp))
                Text("⏸  Paused", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Reopen this screen to resume sending",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(32.dp))
                OutlinedButton(onClick = { viewModel.cancel(); onDismiss() }) { Text("Cancel") }
            }

            QrEncodeUiState.Complete -> {
                Spacer(modifier = Modifier.height(64.dp))
                Text("✅  Sent", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                // UX gap G2: QR has no back-channel — never imply the receiver confirmed import.
                Text(
                    "Sent — ask the other device to confirm it imported",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onDismiss) { Text("Done") }
            }

            QrEncodeUiState.Cancelled -> {
                Spacer(modifier = Modifier.height(64.dp))
                Text("Transfer cancelled", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onDismiss) { Text("Close") }
            }

            is QrEncodeUiState.Failed -> {
                Spacer(modifier = Modifier.height(64.dp))
                val message = failedMessage(s)
                Text(message, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onDismiss) { Text("Back") }
            }
        }
    }
}

/**
 * UX acceptance criterion 7: [dev.stapler.stelekit.error.DomainError.QrTransferError.PayloadTooLarge]
 * must show the actual size and max size — `toUiMessage()` alone is too generic for this variant.
 */
private fun failedMessage(failed: QrEncodeUiState.Failed): String {
    val error = failed.error
    return if (error is DomainError.QrTransferError.PayloadTooLarge) {
        "This page is too large to send via QR (${error.sizeBytes} bytes, max ${error.maxBytes} bytes)."
    } else {
        error.toUiMessage()
    }
}

@Composable
private fun DisplayingContent(
    state: QrEncodeUiState.Displaying,
    pageName: String,
    blockCount: Int,
    currentFrame: QrMatrix?,
    settings: QrTransferSettings,
    onAdvance: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var explainerDismissed by remember { mutableStateOf(settings.seenEncoderExplainer) }

    if (!explainerDismissed) {
        FirstUseExplainerBanner(
            onDismiss = {
                settings.seenEncoderExplainer = true
                explainerDismissed = true
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Text(
        "$pageName · $blockCount blocks · ${formatBytesApprox(state.estBytes)} · ~${state.chunkCount} frames",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(16.dp))

    InsetQrCard(
        matrix = currentFrame,
        contentDescription = "Sending, frame ${state.frameIndex + 1} of about ${state.chunkCount}",
    )

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "frame ${state.frameIndex + 1} of ~${state.chunkCount} (cycling)",
        style = MaterialTheme.typography.bodySmall,
    )

    if (settings.reduceMotion) {
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onAdvance) { Text("Next ▶") }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "🔒  No internet connection used",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(modifier = Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
        Button(onClick = onDone) { Text("Done sending") }
    }
}

/**
 * The animated/static QR render target: a bordered card sized to ≤60% of the available viewport
 * width (ADR-004 small-area WCAG 2.3.1 exemption) on the screen's ordinary (static, non-flashing)
 * background.
 */
@Composable
private fun InsetQrCard(
    matrix: QrMatrix?,
    contentDescription: String = "QR transfer code",
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(INSET_CARD_WIDTH_FRACTION)
            .aspectRatio(1f)
            .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (matrix == null) {
            CircularProgressIndicator()
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cell = minOf(size.width, size.height) / matrix.size
                for (y in 0 until matrix.size) {
                    for (x in 0 until matrix.size) {
                        if (matrix[x, y]) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x * cell, y * cell),
                                size = Size(cell, cell),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One-time-ever first-use explainer (Task 3.1.3c, S3) — dismissing it never pauses or cancels
 * the in-progress transfer, since the frame-advance loop lives entirely in the ViewModel.
 */
@Composable
private fun FirstUseExplainerBanner(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            "This is a continuous scan, not a photo — keep both screens visible for about 30–60 seconds.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onDismiss) { Text("Got it") }
    }
}

private fun formatBytesApprox(bytes: Int): String =
    if (bytes < 1024) "~$bytes B" else "~${(bytes + 512) / 1024} KB"

/** Target width fraction for [InsetQrCard] — keeps rendered area well under ADR-004's ≤60% cap. */
private const val INSET_CARD_WIDTH_FRACTION = 0.55f
