package dev.stapler.stelekit.ui.transfer

import android.view.ViewGroup
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.sensor.AndroidCameraPreviewBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val logger = Logger("PlatformCameraPreview")

/**
 * Real CameraX preview (Bug 2 fix) — an [AndroidView] wrapping a [PreviewView], bound via
 * [AndroidCameraPreviewBinder] alongside the [dev.stapler.stelekit.platform.sensor.AndroidCameraFrameSource]
 * scan pipeline that's already running underneath [QrDecodeScreen]. `rememberCoroutineScope()` is
 * used only for the one-shot bind call at composition entry (transient UI work, CLAUDE.md scope
 * rule) — [AndroidCameraPreviewBinder] itself holds no scope.
 */
@Composable
actual fun PlatformCameraPreview(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val binder = remember { AndroidCameraPreviewBinder(context) }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(binder, previewView) {
        var bound: Preview? = null
        val job = scope.launch {
            try {
                bound = binder.bind(previewView.surfaceProvider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Preview is a pure UX enhancement — a bind failure here must never break
                // scanning, which runs on its own independent ImageAnalysis binding.
                logger.warn("camera preview bind failed: ${e.message}")
            }
        }
        onDispose {
            job.cancel()
            bound?.let { binder.unbind(it) }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
