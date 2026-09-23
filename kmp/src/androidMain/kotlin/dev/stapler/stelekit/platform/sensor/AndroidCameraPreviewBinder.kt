package dev.stapler.stelekit.platform.sensor

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Binds a CameraX [Preview] use case to the SAME lifecycle/camera-selector/[ProcessCameraProvider]
 * seam [AndroidCameraFrameSource] uses for its `ImageAnalysis` scan pipeline (Bug 2 fix,
 * camera-qr-export Story 3.2.3 AC — "camera preview + reticle", not just a static bordered box).
 *
 * Deliberately independent of [AndroidCameraFrameSource]'s own [CameraXBinder]: it binds ONLY
 * [Preview] and unbinds ONLY that use case on [unbind] — never `unbindAll()` — so it composes
 * alongside whichever `ImageAnalysis` collection(s) are concurrently active (the scan pipeline)
 * rather than tearing them down. [ProcessCameraProvider.getInstance] returns the same process-wide
 * singleton instance regardless of which class requests it, so both use cases end up bound to the
 * same underlying camera session.
 */
internal class AndroidCameraPreviewBinder(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null

    /**
     * Binds a fresh [Preview] use case with [surfaceProvider] and returns it. Catches
     * [Throwable], not [Exception] (CLAUDE.md Android rule) — CameraX bind/HAL failures can
     * surface as `Error` subclasses.
     */
    suspend fun bind(surfaceProvider: Preview.SurfaceProvider): Preview {
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    if (cont.isActive) {
                        runCatching { future.get() }
                            .onSuccess { cont.resume(it) }
                            .onFailure { cont.resumeWithException(it) }
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
            cont.invokeOnCancellation { future.cancel(true) }
        }
        cameraProvider = provider

        val preview = Preview.Builder().build().apply { setSurfaceProvider(surfaceProvider) }
        // No unbindAll() here — see class KDoc: this must compose with the concurrently-bound
        // ImageAnalysis from AndroidCameraFrameSource's scan pipeline, not tear it down.
        provider.bindToLifecycle(ProcessLifecycleOwner.get(), CameraSelector.DEFAULT_BACK_CAMERA, preview)
        return preview
    }

    fun unbind(preview: Preview) {
        cameraProvider?.unbind(preview)
    }
}
