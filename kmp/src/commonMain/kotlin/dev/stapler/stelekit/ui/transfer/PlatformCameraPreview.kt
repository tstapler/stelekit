package dev.stapler.stelekit.ui.transfer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders the live camera feed currently being scanned, behind [QrDecodeScreen]'s reticle overlay
 * (Bug 2 fix — Story 3.2.3 AC requires "camera preview + reticle", not just a static bordered box;
 * the user needs to see what the camera sees to aim it at the sender's screen).
 *
 * QR receive is Android-only in v1 (plan.md scope, ADR-003) — [QrDecodeScreen] is only ever reached
 * from a real [dev.stapler.stelekit.platform.sensor.CameraFrameSource] wiring today (Android's
 * `ScreenRouter`). The JVM/iOS/wasmJs actuals are deliberate no-ops (an empty [modifier]-sized
 * box) purely so those targets keep compiling — mirrors the existing
 * [dev.stapler.stelekit.ui.PlatformBackHandler] expect/actual seam shape.
 */
@Composable
expect fun PlatformCameraPreview(modifier: Modifier = Modifier)
