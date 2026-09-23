package dev.stapler.stelekit.ui.transfer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop has no QR-receive camera preview in v1 (Android-only, plan.md scope) — no-op. */
@Composable
actual fun PlatformCameraPreview(modifier: Modifier) {
    Box(modifier = modifier)
}
