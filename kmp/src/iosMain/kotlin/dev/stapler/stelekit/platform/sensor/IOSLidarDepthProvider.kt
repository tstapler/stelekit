package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.calibration.DepthFrame
import dev.stapler.stelekit.error.DomainError

/**
 * iOS LiDAR depth provider for Story 8.5.
 *
 * LiDAR hardware is available on:
 * - iPhone 12 Pro, 12 Pro Max (A14 Bionic)
 * - iPhone 13 Pro, 13 Pro Max
 * - iPhone 14 Pro, 14 Pro Max
 * - iPhone 15 Pro, 15 Pro Max, 15 Ultra
 * - iPad Pro with LiDAR (2020+)
 *
 * Full ARKit implementation requires bridging to Swift/ObjC:
 * - `ARSession` with `ARWorldTrackingConfiguration.frameSemantics.sceneDepth`
 * - `ARSceneDepth` frame data → depth map and confidence map
 *
 * This Kotlin/Native interop layer is deferred until the iOS bridging layer is
 * set up in a later story. For now, [isAvailable] uses [LidarHardwareCheck.isAvailable]
 * to detect LiDAR hardware at compile/runtime, and returns a placeholder [DepthFrame]
 * with [confidencePercent] = 85 for future full implementation.
 *
 * The [CalibrationFallbackChain] will see [isAvailable] = true on LiDAR-capable devices
 * and route through this provider; the placeholder frame triggers a graceful fallback
 * in the calibration chain since depth values are zero.
 */
class IOSLidarDepthProvider : DepthSensorProvider {

    /**
     * `true` on LiDAR-capable iOS devices.
     *
     * Uses a compile-time platform check based on device capability.
     * Until full ARKit bridging is added, even on LiDAR hardware this stub returns
     * a placeholder frame — callers must handle zero-depth gracefully.
     */
    override val isAvailable: Boolean
        get() = LidarHardwareCheck.isAvailable

    override suspend fun acquireDepthFrame(): Either<DomainError.SensorError, DepthFrame?> {
        return if (!isAvailable) {
            DomainError.SensorError.HardwareUnavailable("iOS LiDAR not available on this device").left()
        } else {
            // Placeholder DepthFrame with zero depth values.
            // A real implementation would populate depthMapMm from ARSceneDepth.
            // confidencePercent = 85 reflects LiDAR's documented ±1–2 cm accuracy when fully
            // implemented. The depth values being zero will cause CalibrationService to
            // return null, triggering the next fallback in the chain.
            DepthFrame(
                width = 256,
                height = 192,
                depthMapMm = FloatArray(256 * 192) { 0f }, // placeholder: all zeros
                confidenceMap = FloatArray(256 * 192) { 0.85f * 255f }, // 85% confidence
            ).right()
        }
    }
}

/**
 * Compile-time check for LiDAR hardware availability on iOS.
 *
 * On iOS, LiDAR availability should ultimately be checked at runtime via
 * `ARConfiguration.supportsFrameSemantics(.sceneDepth)` (requires ARKit bridging).
 * This object provides a placeholder until that bridging is in place.
 *
 * Set [isAvailable] = true when the ARKit bridging layer is complete and the app
 * runs on a LiDAR-capable device (iPhone 12 Pro+, iPad Pro 2020+).
 */
object LidarHardwareCheck {
    /**
     * Whether LiDAR hardware is available on the current device.
     *
     * Currently returns `false` (stub). Replace with `ARConfiguration.supportsFrameSemantics`
     * runtime check once ARKit bridging is complete.
     */
    const val isAvailable: Boolean = false
}
