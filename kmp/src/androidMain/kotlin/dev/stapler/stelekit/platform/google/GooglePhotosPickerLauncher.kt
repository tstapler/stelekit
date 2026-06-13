// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import android.content.Intent
import android.net.Uri
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.SteleKitContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Android implementation of the Google Photos Picker flow using the NEW Picker API
 * (photospicker.googleapis.com), introduced as the only supported path after the
 * photoslibrary.readonly scope was revoked in March 2025.
 *
 * Flow:
 * 1. Call [PhotosPickerApiClient.createPhotosPickerSession] to get a picker session + URI.
 * 2. Open the picker URI in a Custom Tab (system browser overlay — no WebView needed).
 * 3. Poll [PhotosPickerApiClient.getPickerSession] until [PhotosPickerSession.mediaItemsSet] = true.
 * 4. Download selected media bytes via [PhotosPickerApiClient.downloadPickerMedia] using the
 *    temporary `baseUrl` (NOT stored long-term — store `mediaItemId` instead).
 * 5. Clean up the session via [PhotosPickerApiClient.deletePickerSession].
 *
 * UI copy requirement (Story 7.5): callers must display
 * "Select from Google Photos — you choose which specific photos to share with SteleKit"
 * to clarify the limited-access scope to users (per post-March-2025 policy requirements).
 *
 * Prerequisites: user must be authenticated (call [GoogleAuthManager.authenticate] first).
 * If not authenticated, [launchPicker] returns [DomainError.SensorError.PermissionDenied].
 */
class GooglePhotosPickerLauncher(
    private val apiClient: PhotosPickerApiClient,
    private val tokenStore: GoogleTokenStore,
) {

    companion object {
        /**
         * UI copy to display to users before launching the picker.
         * Required per post-March-2025 Google Photos scope restrictions.
         */
        const val PICKER_UI_COPY =
            "Select from Google Photos — you choose which specific photos to share with SteleKit"

        /** Maximum number of polling attempts before giving up (60 × 2s = 120s timeout). */
        private const val MAX_POLL_ATTEMPTS = 60

        /** Polling interval in milliseconds. */
        private const val POLL_INTERVAL_MS = 2_000L
    }

    /**
     * Launch the Google Photos Picker and return the selected photo bytes.
     *
     * This suspend function:
     * 1. Creates a picker session.
     * 2. Opens the picker URI in a system browser / Custom Tab.
     * 3. Polls for user selection (up to [MAX_POLL_ATTEMPTS] × [POLL_INTERVAL_MS] = 120s).
     * 4. Downloads the selected photo bytes.
     * 5. Returns the bytes and the stable [mediaItemId] for long-term storage.
     *
     * @return Pair of (imageBytes, mediaItemId) on success.
     */
    suspend fun launchPicker(): Either<DomainError, Pair<ByteArray, String>> {
        if (!tokenStore.isAuthenticated()) {
            return DomainError.SensorError.PermissionDenied(
                "Google account not connected. Connect a Google account first to import from Google Photos.",
            ).left()
        }

        // Step 1: Create picker session
        val session = apiClient.createPhotosPickerSession().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Failed to create Google Photos Picker session. Check network connection.",
            ).left()

        // Step 2: Open picker URI in system browser
        try {
            val pickerIntent = Intent(Intent.ACTION_VIEW, Uri.parse(session.pickerUri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            SteleKitContext.context.startActivity(pickerIntent)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Failed to open Google Photos Picker: ${e.message}",
            ).left()
        }

        // Step 3: Poll until user makes a selection
        var attempts = 0
        var latestSession = session
        while (!latestSession.mediaItemsSet && attempts < MAX_POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            attempts++
            val polled = apiClient.getPickerSession(session.id).getOrNull() ?: break
            latestSession = polled
        }

        if (!latestSession.mediaItemsSet) {
            apiClient.deletePickerSession(session.id)
            return DomainError.NetworkError.HttpError(
                statusCode = 408,
                message = "Google Photos Picker timed out or was cancelled.",
            ).left()
        }

        // Step 4: Get media items from the completed session
        // The session response should contain mediaItems — re-fetch the session with mediaItems field
        val mediaItems = fetchSessionMediaItems(session.id)
        val firstItem = mediaItems.firstOrNull() ?: run {
            apiClient.deletePickerSession(session.id)
            return DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "No photo selected from Google Photos.",
            ).left()
        }

        // Step 5: Download the selected photo bytes using the temporary baseUrl
        val bytes = apiClient.downloadPickerMedia(
            baseUrl = firstItem.first, // temporary baseUrl — NOT stored
            mediaItemId = firstItem.second,
        ).getOrNull() ?: run {
            apiClient.deletePickerSession(session.id)
            return DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Failed to download selected photo from Google Photos.",
            ).left()
        }

        // Step 6: Clean up the session
        apiClient.deletePickerSession(session.id)

        // Return bytes + stable mediaItemId (store this, NOT the baseUrl)
        return Pair(bytes, firstItem.second).right()
    }

    /**
     * Fetch the media items (baseUrl, mediaItemId pairs) from a completed picker session.
     *
     * Delegates to [PhotosPickerApiClient.listPickerMediaItems] which calls:
     * GET https://photospicker.googleapis.com/v1/mediaItems?sessionId={id}
     * Response: { "mediaItems": [{ "id": "...", "mediaFile": { "baseUrl": "...", ... } }] }
     *
     * Returns an empty list if the request fails.
     */
    private suspend fun fetchSessionMediaItems(sessionId: String): List<Pair<String, String>> {
        return apiClient.listPickerMediaItems(sessionId).getOrNull() ?: emptyList()
    }
}
