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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val driveJson = Json { ignoreUnknownKeys = true }

/**
 * A Google Drive file/folder entry returned by the list endpoint.
 */
@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: String? = null,
    @SerialName("modifiedTime") val modifiedTime: String? = null,
) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
}

/**
 * Google Photos Picker session, returned after calling the picker initiation endpoint.
 */
@Serializable
data class PhotosPickerSession(
    val id: String,
    val pickerUri: String,
    @SerialName("mediaItemsSet") val mediaItemsSet: Boolean = false,
    @SerialName("expireTime") val expireTime: String? = null,
)

/**
 * Google Drive REST API v3 + Google Photos Picker API client.
 *
 * All methods return [Either] — network errors are never thrown to callers.
 * Uses Ktor [HttpClient] with per-request Bearer auth resolved from [tokenStore].
 *
 * Token refresh is handled transparently: before each request, [isTokenExpired] is checked.
 * If expired, [refreshGoogleToken] is called using the stored refresh token.
 *
 * Offline behavior: all methods return [DomainError.NetworkError] on connectivity failure.
 * The caller is responsible for surfacing an appropriate error message to the user.
 *
 * @param httpClient Ktor HttpClient (should include a JSON content-negotiation plugin).
 * @param tokenStore Storage for OAuth tokens; used for Bearer auth on every request.
 * @param clientId OAuth client ID, used for token refresh.
 * @param clientSecret OAuth client secret, used for token refresh.
 */
open class GoogleApiClient(
    private val httpClient: HttpClient,
    private val tokenStore: GoogleTokenStore,
    private val clientId: String = "",
    private val clientSecret: String = "",
) : DriveUploader {

    // ── Auth helpers ──────────────────────────────────────────────────────────

    /**
     * Get a valid access token, refreshing if expired.
     */
    private suspend fun getValidToken(): Either<DomainError, String> {
        if (tokenStore.isTokenExpired()) {
            val refresh = tokenStore.getRefreshToken()
                ?: return DomainError.NetworkError.HttpError(
                    statusCode = 401,
                    message = "Not authenticated. Connect a Google account first.",
                ).left()
            return refreshGoogleToken(httpClient, clientId, clientSecret, refresh, tokenStore)
        }
        return tokenStore.getAccessToken()?.right()
            ?: DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated. Connect a Google account first.",
            ).left()
    }

    // ── Drive REST API v3 ─────────────────────────────────────────────────────

    /**
     * Upload [bytes] as a new file with [fileName] and [mimeType] to Google Drive.
     *
     * Uses multipart upload for files ≤ 5 MB. For larger files, callers should use
     * the resumable upload flow (not yet implemented in this method).
     *
     * @param parentFolderId Optional Drive folder ID. Uploads to root if null.
     * @return Drive file ID of the created file.
     */
    override suspend fun uploadFile(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        parentFolderId: String?,
    ): Either<DomainError, String> {
        val token = getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            // Build multipart/related body: metadata part + media part
            val boundary = "boundary_stelekit_${Clock.System.now().toEpochMilliseconds()}"
            val parentsPart = if (parentFolderId != null) {
                ""","parents":["$parentFolderId"]"""
            } else ""
            val metadataPart = """{"name":"$fileName","mimeType":"$mimeType"$parentsPart}"""

            val headerBytes = buildString {
                append("--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(metadataPart)
                append("\r\n--$boundary\r\n")
                append("Content-Type: $mimeType\r\n\r\n")
            }.encodeToByteArray()
            val footerBytes = "\r\n--$boundary--".encodeToByteArray()
            val multipartBody = ByteArray(headerBytes.size + bytes.size + footerBytes.size).also { buf ->
                headerBytes.copyInto(buf, 0)
                bytes.copyInto(buf, headerBytes.size)
                footerBytes.copyInto(buf, headerBytes.size + bytes.size)
            }

            val response = httpClient.post(
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
            ) {
                bearerAuth(token)
                header(HttpHeaders.ContentType, "multipart/related; boundary=$boundary")
                setBody(multipartBody)
            }

            if (!response.status.isSuccess()) {
                return DomainError.NetworkError.HttpError(
                    statusCode = response.status.value,
                    message = "Drive upload failed: ${response.status.description}",
                ).left()
            }

            val body = response.bodyAsText()
            val json = driveJson.parseToJsonElement(body).jsonObject
            val fileId = json["id"]?.jsonPrimitive?.content
                ?: return DomainError.NetworkError.HttpError(
                    statusCode = 200,
                    message = "Drive upload succeeded but response missing file ID",
                ).left()

            fileId.right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Drive upload error: ${e.message}",
            ).left()
        }
    }

    /**
     * List files in a Drive folder (or root if [folderId] is null).
     *
     * Returns up to 100 files ordered by modified time descending.
     * Does NOT include trashed files.
     */
    suspend fun listFiles(
        folderId: String? = null,
    ): Either<DomainError, List<DriveFile>> {
        val token = getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            val parent = if (folderId != null) "'$folderId' in parents" else "'root' in parents"
            val query = "$parent and trashed=false"
            val fields = "files(id,name,mimeType,size,modifiedTime)"

            val response = httpClient.get(
                "https://www.googleapis.com/drive/v3/files",
            ) {
                bearerAuth(token)
                url {
                    parameters.append("q", query)
                    parameters.append("fields", fields)
                    parameters.append("pageSize", "100")
                    parameters.append("orderBy", "modifiedTime desc")
                }
            }

            if (!response.status.isSuccess()) {
                return DomainError.NetworkError.HttpError(
                    statusCode = response.status.value,
                    message = "Drive list failed: ${response.status.description}",
                ).left()
            }

            val body = response.bodyAsText()
            val json = driveJson.parseToJsonElement(body).jsonObject
            val filesArray = json["files"]?.jsonArray ?: return emptyList<DriveFile>().right()

            val files = filesArray.map { element ->
                driveJson.decodeFromString<DriveFile>(element.toString())
            }
            files.right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Drive list error: ${e.message}",
            ).left()
        }
    }

    /**
     * Create a new folder in Google Drive.
     *
     * @param name Folder display name.
     * @param parentId Parent folder ID. Creates in root if null.
     * @return Drive folder ID of the created folder.
     */
    suspend fun createFolder(
        name: String,
        parentId: String? = null,
    ): Either<DomainError, String> {
        val token = getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            val parentsPart = if (parentId != null) ""","parents":["$parentId"]""" else ""
            val body = """{"name":"$name","mimeType":"application/vnd.google-apps.folder"$parentsPart}"""

            val response = httpClient.post(
                "https://www.googleapis.com/drive/v3/files",
            ) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            if (!response.status.isSuccess()) {
                return DomainError.NetworkError.HttpError(
                    statusCode = response.status.value,
                    message = "Drive create folder failed: ${response.status.description}",
                ).left()
            }

            val responseBody = response.bodyAsText()
            val json = driveJson.parseToJsonElement(responseBody).jsonObject
            val folderId = json["id"]?.jsonPrimitive?.content
                ?: return DomainError.NetworkError.HttpError(
                    statusCode = 200,
                    message = "Create folder succeeded but response missing folder ID",
                ).left()

            folderId.right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Drive create folder error: ${e.message}",
            ).left()
        }
    }

    /**
     * Download the binary content of a Drive file by [fileId].
     *
     * Returns the raw bytes. For large files this may allocate significant memory;
     * callers should stream to disk for files > 50 MB.
     */
    suspend fun downloadFile(fileId: String): Either<DomainError, ByteArray> {
        val token = getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            val response = httpClient.get(
                "https://www.googleapis.com/drive/v3/files/$fileId",
            ) {
                bearerAuth(token)
                url { parameters.append("alt", "media") }
            }

            if (!response.status.isSuccess()) {
                return DomainError.NetworkError.HttpError(
                    statusCode = response.status.value,
                    message = "Drive download failed: ${response.status.description}",
                ).left()
            }

            response.bodyAsBytes().right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DomainError.NetworkError.HttpError(
                statusCode = -1,
                message = "Drive download error: ${e.message}",
            ).left()
        }
    }

    // ── Google Photos Picker API (post-March 2025) ────────────────────────────

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
        val token = getValidToken().getOrNull()
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

            val body = response.bodyAsText()
            driveJson.decodeFromString<PhotosPickerSession>(body).right()
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
        val token = getValidToken().getOrNull()
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

            val body = response.bodyAsText()
            driveJson.decodeFromString<PhotosPickerSession>(body).right()
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
        val token = getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            // Append =d to request a download (full resolution)
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
        val token = getValidToken().getOrNull()
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

            val body = response.bodyAsText()
            val json = driveJson.parseToJsonElement(body).jsonObject
            val itemsArray = json["mediaItems"]?.jsonArray ?: return emptyList<Pair<String, String>>().right()

            val pairs = itemsArray.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val baseUrl = obj["mediaFile"]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                Pair(baseUrl, id)
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
        val token = getValidToken().getOrNull()
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
