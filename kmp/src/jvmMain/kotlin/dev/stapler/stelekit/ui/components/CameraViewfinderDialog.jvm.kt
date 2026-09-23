package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.platform.sensor.PlatformImageFile

@Composable
actual fun CameraViewfinderDialog(
    onCapture: (PlatformImageFile) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) { /* camera capture not available on desktop */ }
