// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val photosClientJson = Json { ignoreUnknownKeys = true }

/**
 * Response shape for the Photos Picker mediaItems list endpoint.
 * Each item contains a stable id and a temporary baseUrl for downloading.
 */
@Serializable
private data class PickerMediaFile(
    val baseUrl: String,
    val mimeType: String? = null,
)

@Serializable
private data class PickerMediaItem(
    val id: String,
    val mediaFile: PickerMediaFile? = null,
)

@Serializable
private data class PickerMediaItemsResponse(
    val mediaItems: List<PickerMediaItem>? = null,
)

/**
 * Google Photos Picker API client (post-March 2025).
 *
 * Implements the new photospicker.googleapis.com API, which replaced the deprecated
 * photoslibrary.readonly scope that was revoked in March 2025.
 *
 * All methods return [Either] — network errors are never thrown to callers.
 *
 * @param authClient Provides a valid access token (refreshing if near expiry).
 * @param httpClient Ktor HttpClient for executing HTTP requests.
 */
class PhotosPickerApiClient(
    private val authClient: GoogleAuthClient,
    private val httpClient: HttpClient,
) {

    // ── Google Photos Picker API ──────────────────────────────────────────────

    /**
     * Create a new Google Photos Picker session.
     *
     * Returns a [PhotosPickerSession] containing [PhotosPickerSession.pickerUri] which
     * should be opened in a Custom Tab or WebView to let the user select photos.
     *
     * After the user makes a selection, poll [getPickerSession] until
     * [PhotosPickerSession.mediaItemsSet] is true, then call [downloadPickerMedia].
     *
     * NOTE: Uses the NEW Photos Picker API (photospicker.googleapis.com), NOT the
     * deprecated photoslibrary.readonly scope which was revoked March 2025.
     */
    suspend fun createPhotosPickerSession(): Either<DomainError, PhotosPickerSession> {
        val token = authClient.getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            val response = httpClient.post(
                "https://photospicker.googleapis.com/v1/sessions",
            ) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            if (!response.status.isSuccess()) {
                return DomainError.NetworkError.HttpError(
                    statusCode = response.status.value,
                    message = "Photos Picker session creation failed: ${response.status.description}",
                ).left()
            }

            photosClientJson.decodeFromString<PhotosPickerSession>(response.bodyAsText()).right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Photos Picker session error: ${e.message}",
            ).left()
        }
    }

    /**
     * Poll a Photos Picker [sessionId] to check if the user has made their selection.
     */
    suspend fun getPickerSession(sessionId: String): Either<DomainError, PhotosPickerSession> {
        val token = authClient.getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            val response = httpClient.get(
                "https://photospicker.googleapis.com/v1/sessions/$sessionId",
            ) {
                bearerAuth(token)
            }

            if (!response.status.isSuccess()) {
                return DomainError.NetworkError.HttpError(
                    statusCode = response.status.value,
                    message = "Photos Picker session poll failed",
                ).left()
            }

            photosClientJson.decodeFromString<PhotosPickerSession>(response.bodyAsText()).right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Photos Picker session poll error: ${e.message}",
            ).left()
        }
    }

    /**
     * Download a photo from a picker session's [baseUrl].
     *
     * The [baseUrl] is temporary and must not be persisted. Only call this immediately
     * after the session shows [PhotosPickerSession.mediaItemsSet] = true.
     * Store the [mediaItemId], not the baseUrl, for long-term reference.
     *
     * @param baseUrl The temporary download URL from the picker session media item.
     * @param mediaItemId The stable media item ID to store for future reference.
     */
    suspend fun downloadPickerMedia(
        baseUrl: String,
        mediaItemId: String,
    ): Either<DomainError, ByteArray> {
        val token = authClient.getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            val downloadUrl = if (baseUrl.endsWith("=d")) baseUrl else "$baseUrl=d"
            val response = httpClient.get(downloadUrl) {
                bearerAuth(token)
            }

            if (!response.status.isSuccess()) {
                return DomainError.NetworkError.HttpError(
                    statusCode = response.status.value,
                    message = "Photos Picker media download failed for item $mediaItemId",
                ).left()
            }

            response.bodyAsBytes().right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Photos Picker download error: ${e.message}",
            ).left()
        }
    }

    /**
     * List the media items selected by the user in a completed picker session.
     *
     * Returns a list of (baseUrl, mediaItemId) pairs. The [baseUrl] is temporary —
     * it must be used immediately to download the photo. Store the [mediaItemId]
     * for long-term reference, not the baseUrl.
     *
     * Call only after [PhotosPickerSession.mediaItemsSet] is true.
     *
     * Endpoint: GET https://photospicker.googleapis.com/v1/mediaItems?sessionId={id}
     */
    suspend fun listPickerMediaItems(sessionId: String): Either<DomainError, List<Pair<String, String>>> {
        val token = authClient.getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            val response = httpClient.get(
                "https://photospicker.googleapis.com/v1/mediaItems",
            ) {
                bearerAuth(token)
                url { parameters.append("sessionId", sessionId) }
            }

            if (!response.status.isSuccess()) {
                return DomainError.NetworkError.HttpError(
                    statusCode = response.status.value,
                    message = "Picker media items fetch failed: ${response.status.description}",
                ).left()
            }

            val parsed = photosClientJson.decodeFromString<PickerMediaItemsResponse>(response.bodyAsText())
            val pairs = parsed.mediaItems.orEmpty().mapNotNull { item ->
                val baseUrl = item.mediaFile?.baseUrl ?: return@mapNotNull null
                Pair(baseUrl, item.id)
            }
            pairs.right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Picker media items error: ${e.message}",
            ).left()
        }
    }

    /**
     * Delete a Photos Picker session after import is complete.
     * Sessions expire automatically but explicit deletion is good practice.
     */
    suspend fun deletePickerSession(sessionId: String): Either<DomainError, Unit> {
        val token = authClient.getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(statusCode = 401, message = "Not authenticated").left()

        return try {
            val response = httpClient.delete(
                "https://photospicker.googleapis.com/v1/sessions/$sessionId",
            ) {
                bearerAuth(token)
            }
            if (response.status == HttpStatusCode.NoContent || response.status.isSuccess()) {
                Unit.right()
            } else {
                DomainError.NetworkError.HttpError(
                    statusCode = response.status.value,
                    message = "Delete picker session failed",
                ).left()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.NetworkError.HttpError(statusCode = -1, message = e.message ?: "unknown").left()
        }
    }
}
