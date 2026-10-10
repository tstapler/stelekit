package dev.stapler.stelekit.ui.transfer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Web is out of scope for QR receive in v1 (ADR-005) — no-op. */
@Composable
actual fun PlatformCameraPreview(modifier: Modifier) {
    Box(modifier = modifier)
}
