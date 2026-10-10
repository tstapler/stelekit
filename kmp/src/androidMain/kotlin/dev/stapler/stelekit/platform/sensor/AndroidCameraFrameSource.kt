package dev.stapler.stelekit.platform.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android [CameraFrameSource] using CameraX [ImageAnalysis] (ADR-002).
 *
 * Binds exactly one `ImageAnalysis` use case with [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST]
 * backpressure — CameraX withholds the next frame until [ImageProxy.close] is called on the
 * current one, so this never buffers stale frames the way a naive queue would. Requires the
 * CAMERA runtime permission — checked in [frameStream] before binding, mirroring
 * [AndroidCameraProvider].
 *
 * [binder] is a test-only seam (see the `internal constructor`): production code must always
 * use the public constructor, which wires the real [ProcessCameraProviderBinder]. Tests inject
 * a fake to assert the bind→collect→cancel→unbind wiring in this class without requiring real
 * camera hardware (CameraX's `ProcessCameraProvider.bindToLifecycle` needs a camera HAL that
 * does not exist under Robolectric/JVM unit tests).
 */
class AndroidCameraFrameSource internal constructor(
    private val context: Context,
    private val requestPermission: (suspend () -> Boolean)?,
    private val binder: CameraXBinder,
) : CameraFrameSource {

    constructor(context: Context, requestPermission: (suspend () -> Boolean)? = null) :
        this(context, requestPermission, ProcessCameraProviderBinder(context))

    override val isAvailable: Boolean = true

    /**
     * A cold [Flow] of camera luminance frames. Collection binds the `ImageAnalysis` use case;
     * cancellation unbinds it (no leaked camera binding).
     *
     * Returns [DomainError.SensorError.PermissionDenied] and completes if CAMERA permission is
     * missing and the user denies the runtime prompt (or no [requestPermission] callback was
     * supplied) — never throws, never hangs.
     */
    override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = callbackFlow {
        // 1. Permission gate — request at runtime if needed (mirrors AndroidCameraProvider).
        var permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            permissionGranted = requestPermission?.invoke() ?: false
        }

        var boundAnalysis: ImageAnalysis? = null
        var executor: ExecutorService? = null

        if (!permissionGranted) {
            trySend(DomainError.SensorError.PermissionDenied("camera").left())
            close()
        } else {
            // 2. Bind one ImageAnalysis use case. catch(Throwable), not Exception — CameraX
            // bind/HAL failures can surface as Error subclasses (CLAUDE.md Android rule; see
            // SteleKitApplication.onCreate).
            val exec = Executors.newSingleThreadExecutor()
            executor = exec
            try {
                val analyzer = ImageAnalysis.Analyzer { imageProxy -> emitFrame(imageProxy) }
                boundAnalysis = binder.bind(analyzer, exec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                trySend(
                    DomainError.SensorError.CaptureFailed(
                        "CameraX bind failed: ${e.message ?: "unknown"}"
                    ).left()
                )
                close()
            }
        }

        // 3. Unbind + shut down the analyzer executor when the collector cancels — the only
        // cleanup hook callbackFlow guarantees runs on cancellation.
        awaitClose {
            boundAnalysis?.let { binder.unbind(it) }
            executor?.shutdown()
        }
    }

    /**
     * Extracts Y-plane (grayscale) luminance bytes + rotation from [imageProxy] and emits a
     * [CameraFrame]. Always closes [imageProxy] in `finally` — under keep-latest backpressure,
     * CameraX withholds the next frame until it is closed, so leaking it stalls the stream.
     */
    private fun ProducerScope<Either<DomainError.SensorError, CameraFrame>>.emitFrame(imageProxy: ImageProxy) {
        try {
            val yPlane = imageProxy.planes[0]
            val buffer = yPlane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            trySendBlocking(
                CameraFrame(
                    luminanceBytes = bytes,
                    width = imageProxy.width,
                    height = imageProxy.height,
                    rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                ).right()
            )
        } finally {
            imageProxy.close()
        }
    }
}

/**
 * Seam over the CameraX process-camera bind/unbind lifecycle — see the `internal constructor`
 * KDoc on [AndroidCameraFrameSource].
 */
internal interface CameraXBinder {
    suspend fun bind(analyzer: ImageAnalysis.Analyzer, executor: Executor): ImageAnalysis
    fun unbind(imageAnalysis: ImageAnalysis)
}

/** Production [CameraXBinder] — real [ProcessCameraProvider] bind/unbind. */
private class ProcessCameraProviderBinder(private val context: Context) : CameraXBinder {

    private var cameraProvider: ProcessCameraProvider? = null

    override suspend fun bind(analyzer: ImageAnalysis.Analyzer, executor: Executor): ImageAnalysis {
        // Bridge ListenableFuture to suspend (matches AndroidCameraProvider.capturePhoto).
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

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        imageAnalysis.setAnalyzer(executor, analyzer)

        // Use fully-qualified class to avoid the Glance bindToLifecycle extension clash
        // (matches AndroidCameraProvider.capturePhoto).
        val lifecycleOwner = androidx.lifecycle.ProcessLifecycleOwner.get()
        // MAJOR M1 fix: no unbindAll() here — mirrors AndroidCameraPreviewBinder's own KDoc/
        // pattern (see its class KDoc): this must compose with a concurrently-bound Preview use
        // case from AndroidCameraPreviewBinder, not tear it down. Only this class's own
        // ImageAnalysis use case is tracked and unbound (via CameraXBinder.unbind), symmetric with
        // how AndroidCameraPreviewBinder.unbind only unbinds its own Preview.
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalysis)
        return imageAnalysis
    }

    override fun unbind(imageAnalysis: ImageAnalysis) {
        imageAnalysis.clearAnalyzer()
        cameraProvider?.unbind(imageAnalysis)
    }
}
