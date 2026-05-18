package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError

/**
 * Camera provider for platforms that do not have a camera (JVM desktop, WASM).
 *
 * Always reports [isAvailable] = `false` and returns
 * [DomainError.SensorError.HardwareUnavailable] from [capturePhoto].
 *
 * Desktop import uses [DesktopFilePicker] instead of camera capture.
 */
class NoOpCameraProvider : CameraProvider {

    override val isAvailable: Boolean = false

    override suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile> =
        DomainError.SensorError.HardwareUnavailable("camera").left()
}
