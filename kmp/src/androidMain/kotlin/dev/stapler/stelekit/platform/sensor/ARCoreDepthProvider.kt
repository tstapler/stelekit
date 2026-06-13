package dev.stapler.stelekit.platform.sensor

import android.content.Context
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotYetAvailableException
import dev.stapler.stelekit.calibration.DepthFrame
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import java.nio.ByteOrder

/**
 * ARCore Depth API implementation of [DepthSensorProvider].
 *
 * Requires `com.google.ar:core:1.46.0` in androidMain dependencies.
 *
 * AndroidManifest requirements (already added):
 * - `<uses-feature android:name="android.hardware.camera.ar" android:required="false"/>`
 * - `<meta-data android:name="com.google.ar.core" android:value="optional"/>` in `<application>`
 *
 * ADR-005 constraint: when ARCore depth IS active the UI must display:
 * "ARCore depth accuracy ±8–10 cm. Not suitable for measurements under 15 cm."
 *
 * Thread-safety: [acquireDepthFrame] must be called from a single coroutine at a time.
 * The ARCore [Session] is not thread-safe; serialise access via a dedicated coroutine context
 * if multiple callers are possible.
 */
class ARCoreDepthProvider(private val context: Context) : DepthSensorProvider {

    private var session: Session? = null

    /**
     * Returns `true` when ARCore is supported and installed (or can be installed) on this device.
     *
     * `SUPPORTED_INSTALLED`    — AR hardware + Play Services ARCore APK both present.
     * `SUPPORTED_NOT_INSTALLED`— AR hardware present but ARCore APK not yet installed;
     *                            the user can install it on demand.
     *
     * Returns `false` for `UNSUPPORTED_DEVICE_NOT_CAPABLE`, `UNKNOWN_TIMED_OUT`,
     * `UNKNOWN_CHECKING`, and `UNKNOWN_ERROR`.
     */
    override val isAvailable: Boolean
        get() = try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            availability == ArCoreApk.Availability.SUPPORTED_INSTALLED ||
                availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
        } catch (e: Exception) {
            false
        }

    /**
     * Acquire a single depth frame from ARCore.
     *
     * Opens an [Session] on the first call (or re-opens after [close]). Configures
     * the session for [Config.DepthMode.AUTOMATIC] so depth is captured alongside the
     * camera frame without any separate trigger.
     *
     * Return values:
     * - `Right(DepthFrame)` — depth map extracted successfully; depth values are in metres
     *   (converted from the raw 16-bit millimetre representation).
     * - `Right(null)` — ARCore is initialising or does not have enough data yet
     *   ([NotYetAvailableException] or [DeadlineExceededException]). Not an error.
     * - `Left(SensorError.HardwareUnavailable)` — device does not support depth mode.
     * - `Left(SensorError.CaptureFailed)` — unexpected ARCore error.
     */
    override suspend fun acquireDepthFrame(): Either<DomainError.SensorError, DepthFrame?> {
        return try {
            // Open session lazily on first use.
            val arSession = session ?: run {
                val s = Session(context)
                val config = s.config
                config.depthMode = Config.DepthMode.AUTOMATIC
                s.configure(config)
                session = s
                s
            }

            if (!arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                return DomainError.SensorError.HardwareUnavailable(
                    "ARCore depth mode not supported on this device"
                ).left()
            }

            // resume() is idempotent — safe to call even if already resumed.
            arSession.resume()

            val frame = arSession.update()

            // Acquire depth image — 16-bit unsigned integer, unit = millimetres.
            val depthImage = frame.acquireDepthImage16Bits()
            val confidenceImage = frame.acquireRawDepthConfidenceImage()

            val width = depthImage.width
            val height = depthImage.height
            val pixelCount = width * height

            // Extract depth plane — single plane, pixel stride 2 bytes (uint16 LE).
            val depthBuffer = depthImage.planes[0].buffer.order(ByteOrder.LITTLE_ENDIAN)
            val depthMapMm = FloatArray(pixelCount) { i ->
                // uint16 → unsigned int → metres
                (depthBuffer.getShort(i * 2).toInt() and 0xFFFF).toFloat() / 1000f
            }
            depthImage.close()

            // Extract confidence plane — single plane, pixel stride 1 byte (uint8, range 0–255).
            val confBuffer = confidenceImage.planes[0].buffer
            val confidenceMap = FloatArray(pixelCount) { i ->
                (confBuffer.get(i).toInt() and 0xFF).toFloat() / 255f
            }
            confidenceImage.close()

            DepthFrame(
                width = width,
                height = height,
                depthMapMm = depthMapMm,
                confidenceMap = confidenceMap,
            ).right()
        } catch (e: NotYetAvailableException) {
            // ARCore is still initialising or lighting is insufficient — not an error.
            null.right()
        } catch (e: DeadlineExceededException) {
            // Frame update exceeded the deadline — transient, not an error.
            null.right()
        } catch (e: CameraNotAvailableException) {
            // Camera taken by another app — transient, not an error.
            null.right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.SensorError.CaptureFailed(
                "ARCore depth capture failed: ${e.message ?: "unknown"}"
            ).left()
        }
    }

    /**
     * Close and release the ARCore session.
     *
     * Must be called when the host Activity/Fragment stops (e.g. in `onPause` or `onDestroy`)
     * to release the camera and GPU resources held by ARCore.
     */
    fun close() {
        session?.close()
        session = null
    }
}
