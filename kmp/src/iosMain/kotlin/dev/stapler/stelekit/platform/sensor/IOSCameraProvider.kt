package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError

/**
 * iOS camera provider using AVFoundation (via CameraK or direct Swift interop).
 *
 * This is a stub implementation for Epic 2. Full AVFoundation capture requires
 * a UIImagePickerController or PHPickerViewController presented from a UIViewController —
 * the Compose Multiplatform bridging for this will be implemented in a later story.
 *
 * For iOS image import, present a PHPickerViewController from the SwiftUI/UIKit layer
 * and hand the selected image bytes into [dev.stapler.stelekit.db.ImageImportService].
 */
class IOSCameraProvider : CameraProvider {

    override val isAvailable: Boolean = false

    override suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile> =
        DomainError.SensorError.HardwareUnavailable("camera").left()
}
