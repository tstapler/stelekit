package dev.stapler.stelekit.ui.transfer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** iOS QR-receive camera preview lands in Epic 4.4 (plan.md, `IOSCameraFrameSource`) — no-op for now. */
@Composable
actual fun PlatformCameraPreview(modifier: Modifier) {
    Box(modifier = modifier)
}
