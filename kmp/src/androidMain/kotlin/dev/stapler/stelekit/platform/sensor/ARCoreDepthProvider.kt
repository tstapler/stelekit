package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.calibration.DepthFrame
import dev.stapler.stelekit.error.DomainError

/**
 * ARCore Depth API implementation of [DepthSensorProvider].
 *
 * ARCHITECTURE STUB — full ARCore integration requires the ARCore SDK dependency
 * (`com.google.ar:core`) to be added to androidMain in build.gradle.kts. That dependency
 * is intentionally NOT added here because the ARCore AAR is large (~20 MB) and optional.
 *
 * To enable real ARCore depth:
 * 1. Add `implementation("com.google.ar:core:1.45.0")` to androidMain dependencies.
 * 2. Replace the [isAvailable] and [acquireDepthFrame] implementations below with real
 *    ARCore Session / Frame calls.
 * 3. Guard all ARCore calls inside `try { } catch (e: Exception) { }` — ARCore throws
 *    `UnsatisfiedLinkError` on devices without the Play Services ARCore APK.
 *
 * Current behaviour: always returns [DomainError.SensorError.HardwareUnavailable] so the
 * [dev.stapler.stelekit.calibration.CalibrationFallbackChain] skips to EXIF/ML without crashing.
 *
 * ADR-005 constraint: if ARCore depth IS active, the UI must display the warning:
 * "ARCore depth accuracy ±8–10 cm. Not suitable for measurements under 15 cm."
 */
class ARCoreDepthProvider : DepthSensorProvider {

    /**
     * Checks whether ARCore depth mode is supported on this device.
     *
     * When ARCore SDK is present, replace this body with:
     * ```kotlin
     * return try {
     *     val session = arCoreSession ?: return false
     *     session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
     * } catch (e: Exception) {
     *     false
     * }
     * ```
     */
    override val isAvailable: Boolean = false

    /**
     * Acquire a depth frame from ARCore.
     *
     * When ARCore SDK is present, replace this body with:
     * ```kotlin
     * return try {
     *     val session = arCoreSession
     *         ?: return DomainError.SensorError.HardwareUnavailable("ARCore").left()
     *     if (!session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
     *         return DomainError.SensorError.HardwareUnavailable("ARCore depth mode").left()
     *     }
     *     val frame = session.update()
     *     val depthImage = frame.acquireDepthImage16Bits()
     *     val confidenceImage = frame.acquireRawDepthConfidenceImage()
     *     // ... extract FloatArrays from Image planes ...
     *     DepthFrame(depthMapMm, confidenceMap, width, height).right()
     * } catch (e: NotYetAvailableException) {
     *     null.right()  // ARCore still initializing — not an error
     * } catch (e: Exception) {
     *     DomainError.SensorError.CaptureFailed(e.message ?: "ARCore capture failed").left()
     * }
     * ```
     */
    override suspend fun acquireDepthFrame(): Either<DomainError.SensorError, DepthFrame?> =
        DomainError.SensorError.HardwareUnavailable("ARCore depth — SDK not linked").left()
}
