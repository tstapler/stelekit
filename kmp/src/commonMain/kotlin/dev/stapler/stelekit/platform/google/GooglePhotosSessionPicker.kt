// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Google Photos Picker session flow (photospicker.googleapis.com, the only path after the
 * `photoslibrary.readonly` scope was revoked in March 2025):
 *
 * 1. create a session, 2. hand its picker URI to [openPickerUri] (a Custom Tab on Android),
 * 3. poll until the owner has chosen, 4. download every chosen item through its temporary
 * `baseUrl` (only the stable `mediaItemId` is kept), 5. delete the session.
 *
 * Returns [PickedPhotos] so a partly failed download still delivers the photos that did arrive.
 */
class GooglePhotosSessionPicker(
    private val apiClient: PhotosPickerPort,
    private val tokenStore: GoogleTokenStore,
    private val openPickerUri: (String) -> Unit,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val maxPollAttempts: Int = MAX_POLL_ATTEMPTS,
) : PhotosPicker {

    companion object {
        /** Shown before the picker opens; required by the post-March-2025 limited-access policy. */
        const val PICKER_UI_COPY =
            "Select from Google Photos — you choose which specific photos to share with SteleKit"

        private const val MAX_POLL_ATTEMPTS = 60
        private const val POLL_INTERVAL_MS = 2_000L
    }

    override suspend fun launchPicker(): Either<DomainError, PickedPhotos> {
        if (!tokenStore.isAuthenticated()) {
            return DomainError.SensorError.PermissionDenied(
                "Google account not connected. Connect a Google account first to import from Google Photos.",
            ).left()
        }
        val session = apiClient.createPhotosPickerSession().getOrNull()
            ?: return httpError(-1, "Failed to create Google Photos Picker session. Check network connection.")

        return try {
            openPickerUri(session.pickerUri)
            collectSelection(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            httpError(-1, "Failed to open Google Photos Picker: ${e.message}")
        } finally {
            // Also runs on cancellation, so a cancelled import never leaves a session behind.
            withContext(NonCancellable) { apiClient.deletePickerSession(session.id) }
        }
    }

    private suspend fun collectSelection(session: PhotosPickerSession): Either<DomainError, PickedPhotos> {
        var latest = session
        var attempts = 0
        while (!latest.mediaItemsSet && attempts < maxPollAttempts) {
            delay(pollIntervalMs)
            attempts++
            latest = apiClient.getPickerSession(session.id).getOrNull() ?: break
        }
        if (!latest.mediaItemsSet) return httpError(408, "Google Photos Picker timed out or was cancelled.")

        val items = apiClient.listPickerMediaItems(session.id).getOrNull().orEmpty()
        if (items.isEmpty()) return httpError(-1, "No photo selected from Google Photos.")

        val photos = ArrayList<PickedPhoto>(items.size)
        var failed = 0
        for ((baseUrl, mediaItemId) in items) {
            val bytes = apiClient.downloadPickerMedia(baseUrl, mediaItemId).getOrNull()
            if (bytes == null || bytes.isEmpty()) failed++ else photos.add(PickedPhoto(bytes, mediaItemId))
        }
        return PickedPhotos(photos, failed).right()
    }

    private fun httpError(status: Int, message: String): Either<DomainError, PickedPhotos> =
        DomainError.NetworkError.HttpError(statusCode = status, message = message).left()
}
