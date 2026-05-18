package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.calibration.DepthFrame
import dev.stapler.stelekit.error.DomainError
import kotlinx.cinterop.CPointer
import kotlinx.coroutines.CancellationException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import platform.ARKit.ARConfiguration
import platform.ARKit.ARFrameSemanticSceneDepth
import platform.ARKit.ARSession
import platform.ARKit.ARWorldTrackingConfiguration
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly

/**
 * iOS LiDAR depth provider using ARKit [ARSession] with `sceneDepth` frame semantics.
 *
 * LiDAR hardware is available on:
 * - iPhone 12 Pro, 12 Pro Max (A14 Bionic)
 * - iPhone 13 Pro, 13 Pro Max
 * - iPhone 14 Pro, 14 Pro Max
 * - iPhone 15 Pro, 15 Pro Max, 15 Ultra
 * - iPad Pro with LiDAR (2020+)
 *
 * [isAvailable] performs a runtime check via
 * [ARConfiguration.supportsFrameSemantics] so devices without LiDAR get
 * [DomainError.SensorError.HardwareUnavailable] and the [CalibrationFallbackChain]
 * skips to the next method.
 *
 * Note: [ARSession] must be run on the main thread. [acquireDepthFrame] uses
 * [session.currentFrame] (a snapshot) which is safe to read from any thread once the
 * session has started on the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
class IOSLidarDepthProvider : DepthSensorProvider {

    private var session: ARSession? = null

    /**
     * `true` on LiDAR-capable iOS devices (runtime check, not a compile-time constant).
     *
     * Uses [ARWorldTrackingConfiguration.supportsFrameSemantics] which is the canonical
     * ARKit check for `.sceneDepth` availability.
     */
    override val isAvailable: Boolean
        get() = ARWorldTrackingConfiguration.supportsFrameSemantics(ARFrameSemanticSceneDepth)

    /**
     * Acquire a single depth frame from the running ARKit session.
     *
     * Opens and runs an [ARSession] on first call if one is not already active.
     * Reads [ARSession.currentFrame?.sceneDepth] to extract a [DepthFrame].
     *
     * Return values:
     * - `Right(DepthFrame)` — depth map in metres, confidence normalised 0.0–1.0
     * - `Right(null)` — session has no current frame yet (still initialising)
     * - `Left(SensorError.HardwareUnavailable)` — device does not have LiDAR
     * - `Left(SensorError.CaptureFailed)` — unexpected error
     */
    override suspend fun acquireDepthFrame(): Either<DomainError.SensorError, DepthFrame?> {
        if (!isAvailable) {
            return DomainError.SensorError.HardwareUnavailable(
                "iOS LiDAR / ARKit sceneDepth not supported on this device"
            ).left()
        }

        return try {
            val arSession = session ?: run {
                val config = ARWorldTrackingConfiguration()
                config.frameSemantics = ARFrameSemanticSceneDepth
                val s = ARSession()
                s.runWithConfiguration(config)
                session = s
                s
            }

            val currentFrame = arSession.currentFrame
                ?: return null.right() // session has not produced a frame yet

            val sceneDepth = currentFrame.sceneDepth
                ?: return null.right() // sceneDepth not yet available in this frame

            val depthBuffer = sceneDepth.depthMap

            CVPixelBufferLockBaseAddress(depthBuffer, kCVPixelBufferLock_ReadOnly)

            val width = CVPixelBufferGetWidth(depthBuffer).toInt()
            val height = CVPixelBufferGetHeight(depthBuffer).toInt()
            val bytesPerRow = CVPixelBufferGetBytesPerRow(depthBuffer).toInt()
            val pixelCount = width * height

            // depthMap is kCVPixelFormatType_DepthFloat32 — each pixel is a 32-bit IEEE float
            // representing depth in metres.
            val baseAddress = platform.CoreVideo.CVPixelBufferGetBaseAddress(depthBuffer)
            val floatPtr = baseAddress?.reinterpret<kotlinx.cinterop.FloatVar>()

            val depthMapMm = FloatArray(pixelCount)
            if (floatPtr != null) {
                val floatsPerRow = bytesPerRow / Float.SIZE_BYTES
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcIdx = y * floatsPerRow + x
                        val dstIdx = y * width + x
                        // ARKit gives metres; store as metres (DepthFrame.depthMapMm field name is
                        // historical — the calibration chain interprets the unit from the provider).
                        depthMapMm[dstIdx] = floatPtr[srcIdx]
                    }
                }
            }

            CVPixelBufferUnlockBaseAddress(depthBuffer, kCVPixelBufferLock_ReadOnly)

            // Confidence map: ARKit provides ARConfidenceLevel (0=low, 1=medium, 2=high) as
            // a CVPixelBuffer with kCVPixelFormatType_OneComponent8.
            val confidenceMap = FloatArray(pixelCount) { 0.85f } // default 85% if no confidence map
            val confBuffer = sceneDepth.confidenceMap
            if (confBuffer != null) {
                CVPixelBufferLockBaseAddress(confBuffer, kCVPixelBufferLock_ReadOnly)
                val confBase = platform.CoreVideo.CVPixelBufferGetBaseAddress(confBuffer)
                val confPtr = confBase?.reinterpret<kotlinx.cinterop.ByteVar>()
                val confBytesPerRow = CVPixelBufferGetBytesPerRow(confBuffer).toInt()
                if (confPtr != null) {
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val srcIdx = y * confBytesPerRow + x
                            val dstIdx = y * width + x
                            // ARConfidenceLevel: 0=low (~33%), 1=medium (~67%), 2=high (~100%)
                            val level = (confPtr[srcIdx].toInt() and 0xFF).coerceIn(0, 2)
                            confidenceMap[dstIdx] = (level + 1) / 3f
                        }
                    }
                }
                CVPixelBufferUnlockBaseAddress(confBuffer, kCVPixelBufferLock_ReadOnly)
            }

            DepthFrame(
                width = width,
                height = height,
                depthMapMm = depthMapMm,
                confidenceMap = confidenceMap,
            ).right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.SensorError.CaptureFailed(
                "ARKit LiDAR depth capture failed: ${e.message ?: "unknown"}"
            ).left()
        }
    }

    /**
     * Pause and release the ARKit session.
     *
     * Call from the iOS app lifecycle (`viewDidDisappear` / scene disconnect) to release
     * camera and sensor resources.
     */
    fun close() {
        session?.pause()
        session = null
    }
}

/**
 * Compile-time check object kept for backward compatibility.
 *
 * New code should use [IOSLidarDepthProvider.isAvailable] (runtime check via ARKit).
 */
object LidarHardwareCheck {
    /**
     * Runtime check for LiDAR availability using ARKit.
     *
     * Delegates to [ARWorldTrackingConfiguration.supportsFrameSemantics] which is the
     * canonical API-level check. Returns `false` on simulators and non-LiDAR devices.
     */
    val isAvailable: Boolean
        get() = ARWorldTrackingConfiguration.supportsFrameSemantics(ARFrameSemanticSceneDepth)
}
