package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError

/**
 * WASM/JS camera provider stub.
 *
 * Full implementation would use `navigator.mediaDevices.getUserMedia()` to capture a frame
 * as a Blob and convert to ByteArray. That requires JS interop and is deferred to a later story.
 *
 * Current behaviour mirrors [NoOpCameraProvider]: always returns [DomainError.SensorError.HardwareUnavailable].
 * The Web UI should present an `<input type="file" accept="image/*">` instead.
 */
class WebCameraProvider : CameraProvider {

    override val isAvailable: Boolean = false

    override suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile> =
        DomainError.SensorError.HardwareUnavailable("camera").left()
}
