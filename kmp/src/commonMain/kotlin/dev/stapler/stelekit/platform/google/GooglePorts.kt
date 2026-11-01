// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation

/** Photos Picker API operations [GooglePhotosSessionPicker] needs; [PhotosPickerApiClient] is the network implementation. */
interface PhotosPickerPort {
    suspend fun createPhotosPickerSession(): Either<DomainError, PhotosPickerSession>
    suspend fun getPickerSession(sessionId: String): Either<DomainError, PhotosPickerSession>
    suspend fun downloadPickerMedia(baseUrl: String, mediaItemId: String): Either<DomainError, ByteArray>

    /** (baseUrl, mediaItemId) pairs of the items chosen in a completed session. */
    suspend fun listPickerMediaItems(sessionId: String): Either<DomainError, List<Pair<String, String>>>
    suspend fun deletePickerSession(sessionId: String): Either<DomainError, Unit>
}

/** Drive read operations [ui.drive.DriveFileBrowserViewModel] needs; [DriveApiClient] is the network implementation. */
interface DriveBrowserPort {
    suspend fun listFiles(folderId: String? = null): Either<DomainError, List<DriveFile>>
    suspend fun downloadFile(fileId: String): Either<DomainError, ByteArray>
}

/** One photo the owner selected in the Google Photos picker. */
class PickedPhoto(val bytes: ByteArray, val mediaItemId: String)

/**
 * Selected photos plus how many selected items could not be downloaded. [failedDownloads] > 0 with
 * a non-empty [photos] is a partial success.
 */
class PickedPhotos(val photos: List<PickedPhoto>, val failedDownloads: Int)

/** Opens the Google Photos picker and returns every photo the owner selected. */
interface PhotosPicker {
    suspend fun launchPicker(): Either<DomainError, PickedPhotos>
}

/**
 * Renders the annotated JPEG (source image with [measurements] baked in) for Drive export.
 * Platform-specific because decoding the source image to an `ImageBitmap` is.
 */
interface AnnotatedImageRenderer {
    suspend fun renderJpeg(
        imageAnnotation: ImageAnnotation,
        measurements: List<MeasurementAnnotation>,
    ): Either<DomainError, ByteArray>
}

/**
 * The Google surfaces one platform root hands to the shared UI. Non-null only on builds that
 * expose Google features ([GoogleBuildFlags.visible]).
 *
 * [photosPicker] and [imageRenderer] are null where the platform has no Photos picker / renderer
 * (Photos import and Drive export are Android-first); the Drive browser works wherever [driveBrowser] exists.
 */
class GoogleIntegration(
    val authManager: GoogleAuthManager,
    val driveUploader: DriveUploader,
    val driveBrowser: DriveBrowserPort,
    val photosPicker: PhotosPicker? = null,
    val imageRenderer: AnnotatedImageRenderer? = null,
)
