// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Clock

private val driveClientJson = Json { ignoreUnknownKeys = true }

/**
 * Response shape returned by the Drive files.create (upload) endpoint.
 */
@Serializable
private data class DriveUploadResponse(
    val id: String,
    val name: String? = null,
    val mimeType: String? = null,
)

/**
 * Response shape returned by the Drive files.create (folder) endpoint.
 */
@Serializable
private data class DriveFolderResponse(
    val id: String,
    val name: String? = null,
    val mimeType: String? = null,
)

/**
 * Google Drive REST API v3 client.
 *
 * All methods return [Either] — network errors are never thrown to callers.
 *
 * @param authClient Provides a valid access token (refreshing if near expiry).
 * @param httpClient Ktor HttpClient for executing HTTP requests.
 */
class DriveApiClient(
    private val authClient: GoogleAuthClient,
    private val httpClient: HttpClient,
) : DriveUploader {

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
        val token = authClient.getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            val boundary = "boundary_stelekit_${Clock.System.now().toEpochMilliseconds()}"
            val metadataJson = buildJsonObject {
                put("name", fileName)
                put("mimeType", mimeType)
                if (parentFolderId != null) putJsonArray("parents") { add(parentFolderId) }
            }
            val metadataPart = metadataJson.toString()

            // When uploading HTML for Google Docs conversion, the content part must declare
            // text/html as Content-Type (the source format). The metadata mimeType field
            // tells Drive the conversion target (application/vnd.google-apps.document).
            // Using the target mimeType in the content part causes Drive to reject the upload.
            val contentMimeType = if (mimeType == "application/vnd.google-apps.document") "text/html" else mimeType
            val headerBytes = buildString {
                append("--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(metadataPart)
                append("\r\n--$boundary\r\n")
                append("Content-Type: $contentMimeType\r\n\r\n")
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

            val uploadResponse = driveClientJson.decodeFromString<DriveUploadResponse>(response.bodyAsText())
            uploadResponse.id.right()
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
        val token = authClient.getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        // Drive resource IDs are server-issued alphanumeric strings. Validate before interpolating
        // into the Drive Files.list query string to prevent Drive query injection.
        if (folderId != null && !DRIVE_ID_REGEX.matches(folderId)) {
            return DomainError.NetworkError.HttpError(
                statusCode = 400,
                message = "Invalid folderId — expected alphanumeric Drive resource ID",
            ).left()
        }

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
            val json = driveClientJson.parseToJsonElement(body).jsonObject
            val filesArray = json["files"]?.jsonArray ?: return emptyList<DriveFile>().right()

            val files = filesArray.map { element ->
                driveClientJson.decodeFromString<DriveFile>(element.toString())
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
        val token = authClient.getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            val bodyJson = buildJsonObject {
                put("name", name)
                put("mimeType", "application/vnd.google-apps.folder")
                if (parentId != null) putJsonArray("parents") { add(parentId) }
            }
            val body = bodyJson.toString()

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

            val folderResponse = driveClientJson.decodeFromString<DriveFolderResponse>(response.bodyAsText())
            folderResponse.id.right()
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
        val token = authClient.getValidToken().getOrNull()
            ?: return DomainError.NetworkError.HttpError(
                statusCode = 401,
                message = "Not authenticated",
            ).left()

        return try {
            val response = httpClient.get(
                "https://www.googleapis.com/drive/v3/files",
            ) {
                bearerAuth(token)
                url {
                    appendPathSegments(fileId)
                    parameters.append("alt", "media")
                }
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

    companion object {
        // Drive resource IDs are server-issued: alphanumeric plus underscore and hyphen.
        private val DRIVE_ID_REGEX = Regex("[A-Za-z0-9_-]+")
    }
}
