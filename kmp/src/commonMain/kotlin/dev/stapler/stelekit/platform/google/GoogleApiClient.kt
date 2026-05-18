// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * Thin facade combining [DriveApiClient] and [PhotosPickerApiClient].
 *
 * Prefer injecting [DriveApiClient] or [PhotosPickerApiClient] directly for new code.
 * This class exists for backward compatibility and DI convenience.
 *
 * @param httpClient Ktor HttpClient (should include a JSON content-negotiation plugin).
 * @param tokenStore Storage for OAuth tokens; used for Bearer auth on every request.
 * @param clientId OAuth client ID, used for token refresh.
 * @param clientSecret OAuth client secret, used for token refresh.
 */
open class GoogleApiClient(
    httpClient: HttpClient,
    tokenStore: GoogleTokenStore,
    clientId: String = "",
    clientSecret: String = "",
) : DriveUploader {

    private val authClient = GoogleAuthClient(tokenStore, httpClient, clientId, clientSecret)

    /** Google Drive REST API v3 operations. */
    val drive: DriveApiClient = DriveApiClient(authClient, httpClient)

    /** Google Photos Picker API operations. */
    val photos: PhotosPickerApiClient = PhotosPickerApiClient(authClient, httpClient)

    // ── DriveUploader delegation ──────────────────────────────────────────────

    override suspend fun uploadFile(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        parentFolderId: String?,
    ): Either<DomainError, String> = drive.uploadFile(fileName, mimeType, bytes, parentFolderId)

    // ── Drive API delegation ──────────────────────────────────────────────────

    suspend fun listFiles(folderId: String? = null): Either<DomainError, List<DriveFile>> =
        drive.listFiles(folderId)

    suspend fun createFolder(name: String, parentId: String? = null): Either<DomainError, String> =
        drive.createFolder(name, parentId)

    suspend fun downloadFile(fileId: String): Either<DomainError, ByteArray> =
        drive.downloadFile(fileId)

    // ── Photos Picker API delegation ──────────────────────────────────────────

    suspend fun createPhotosPickerSession(): Either<DomainError, PhotosPickerSession> =
        photos.createPhotosPickerSession()

    suspend fun getPickerSession(sessionId: String): Either<DomainError, PhotosPickerSession> =
        photos.getPickerSession(sessionId)

    suspend fun downloadPickerMedia(
        baseUrl: String,
        mediaItemId: String,
    ): Either<DomainError, ByteArray> = photos.downloadPickerMedia(baseUrl, mediaItemId)

    suspend fun listPickerMediaItems(sessionId: String): Either<DomainError, List<Pair<String, String>>> =
        photos.listPickerMediaItems(sessionId)

    suspend fun deletePickerSession(sessionId: String): Either<DomainError, Unit> =
        photos.deletePickerSession(sessionId)
}
