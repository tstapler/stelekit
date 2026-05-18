package dev.stapler.stelekit.platform.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * ONNX Runtime implementation of [MonocularDepthEstimator] for Android.
 *
 * Prerequisites:
 * - API 26+ (Android 8.0 Oreo)
 * - ≥ 3 GB total RAM (memory gate)
 * - Model downloaded via [DepthModelDownloader] to `filesDir/models/depth_anything_v2_small.onnx`
 *
 * ADR-005: All results carry 15% confidence. The UI must show
 * "Low confidence — verify with reference object" whenever this estimator is active.
 *
 * All ORT calls are wrapped in try/catch — ORT may throw [ai.onnxruntime.OrtException] or
 * [UnsatisfiedLinkError] on unsupported devices.
 */
class OnnxMonocularDepthEstimator(private val context: Context) : MonocularDepthEstimator {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var _isAvailable: Boolean = false

    val downloader: DepthModelDownloader = DepthModelDownloader(context)

    /** Mirrors [DepthModelDownloader.modelState] for UI observation. */
    val modelState: StateFlow<DepthModelDownloader.ModelState> = downloader.modelState

    override val isAvailable: Boolean
        get() = _isAvailable

    /**
     * Initialize the ONNX session.
     *
     * Returns [DomainError.SensorError.HardwareUnavailable] if:
     * - device is below API 26
     * - device has < 3 GB RAM
     * - model file is not yet present (caller should offer download prompt)
     *
     * On NNAPI failure, falls back silently to CPU inference.
     */
    override suspend fun initialize(): Either<DomainError, Unit> {
        // Already initialized — idempotent.
        if (_isAvailable && ortSession != null) return Unit.right()

        // API level gate.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return DomainError.SensorError.HardwareUnavailable(
                "OnnxMonocularDepthEstimator requires API 26+",
            ).left()
        }

        // Memory gate: require ≥ 3 GB total RAM.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        if (memInfo.totalMem < MIN_RAM_BYTES) {
            return DomainError.SensorError.HardwareUnavailable(
                "OnnxMonocularDepthEstimator requires ≥ 3 GB RAM " +
                    "(device has ${memInfo.totalMem / (1024 * 1024)} MB)",
            ).left()
        }

        // Model availability gate — caller shows "Download model" prompt on HardwareUnavailable.
        if (!downloader.isModelReady()) {
            return DomainError.SensorError.HardwareUnavailable(
                "Depth model not downloaded",
            ).left()
        }

        return withContext(PlatformDispatcher.Default) {
            try {
                val env = OrtEnvironment.getEnvironment()
                ortEnv = env

                // Try NNAPI hardware delegate first; fall back to CPU if unavailable.
                val session = try {
                    val opts = OrtSession.SessionOptions().apply { addNnapi() }
                    env.createSession(downloader.modelFilePath(), opts)
                } catch (ne: Exception) {
                    if (ne is CancellationException) throw ne
                    // NNAPI not available on this device — use CPU inference.
                    env.createSession(downloader.modelFilePath(), OrtSession.SessionOptions())
                }

                ortSession = session
                _isAvailable = true
                Unit.right()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _isAvailable = false
                DomainError.SensorError.HardwareUnavailable(
                    "Failed to create ONNX session: ${e.message}",
                ).left()
            }
        }
    }

    /**
     * Run Depth Anything V2 ViT-S inference on [imageBitmap].
     *
     * Steps:
     * 1. Resize to 518×518 (model's fixed input resolution)
     * 2. Normalize with ImageNet mean/std
     * 3. Run inference via ORT
     * 4. Normalize output to [0,1] range (divide by max)
     *
     * Returns a flat [FloatArray] of 518×518 relative depth values in row-major order.
     * Values are relative, not metric — must be scaled by a known reference distance.
     *
     * NEVER called on the main thread — dispatched to [PlatformDispatcher.Default].
     */
    override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> {
        val session = ortSession
            ?: return DomainError.SensorError.HardwareUnavailable(
                "OnnxMonocularDepthEstimator not initialized",
            ).left()

        return withContext(PlatformDispatcher.Default) {
            try {
                val env = ortEnv ?: OrtEnvironment.getEnvironment()
                val androidBitmap: Bitmap = imageBitmap.asAndroidBitmap()

                // 1. Resize to model input size (518×518).
                val resized = Bitmap.createScaledBitmap(androidBitmap, INPUT_SIZE, INPUT_SIZE, true)

                // 2. Build CHW float buffer with ImageNet normalization.
                val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
                resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
                resized.recycle()

                // Layout: [1, 3, 518, 518] in NCHW order — fill R plane, then G, then B.
                val floatBuffer = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
                for (pixelValue in pixels) {
                    val r = ((pixelValue shr 16) and 0xFF) / 255f
                    floatBuffer.put((r - MEAN_R) / STD_R)
                }
                for (pixelValue in pixels) {
                    val g = ((pixelValue shr 8) and 0xFF) / 255f
                    floatBuffer.put((g - MEAN_G) / STD_G)
                }
                for (pixelValue in pixels) {
                    val b = (pixelValue and 0xFF) / 255f
                    floatBuffer.put((b - MEAN_B) / STD_B)
                }
                floatBuffer.rewind()

                // 3. Create input tensor and run inference.
                val inputShape = longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
                val inputTensor = OnnxTensor.createTensor(env, floatBuffer, inputShape)

                val outputs = inputTensor.use {
                    session.run(mapOf("image" to inputTensor))
                }

                // 4. Extract depth output — shape [1, 1, 518, 518].
                val rawDepth: FloatArray = outputs.use { result ->
                    @Suppress("UNCHECKED_CAST")
                    (result["depth"].get().value as Array<Array<FloatArray>>)[0][0]
                }

                // 5. Normalize to [0,1].
                val maxVal = rawDepth.max()
                if (maxVal > 0f) {
                    for (i in rawDepth.indices) rawDepth[i] /= maxVal
                }

                rawDepth.right()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                DomainError.SensorError.CaptureFailed(
                    "ONNX depth inference failed: ${e.message}",
                ).left()
            }
        }
    }

    companion object {
        private const val INPUT_SIZE = 518

        // ImageNet normalization constants.
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f

        /** Minimum required RAM: 3 GB. */
        private const val MIN_RAM_BYTES = 3L * 1024L * 1024L * 1024L
    }
}
