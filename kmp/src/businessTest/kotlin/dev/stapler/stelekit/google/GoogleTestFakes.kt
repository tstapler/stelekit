package dev.stapler.stelekit.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.google.DriveBrowserPort
import dev.stapler.stelekit.platform.google.DriveFile
import dev.stapler.stelekit.platform.google.GoogleTokenStore
import dev.stapler.stelekit.platform.google.PhotosPickerPort
import dev.stapler.stelekit.platform.google.PhotosPickerSession
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.service.MediaAttachmentService

/** In-memory [GoogleTokenStore]; no keystore, no network. */
class FakeGoogleTokenStore(private var authed: Boolean = true) : GoogleTokenStore {
    var email: String? = null
    var accessToken: String? = null
    var refreshToken: String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        authed = true
    }
    override suspend fun getAccessToken() = accessToken
    override suspend fun getRefreshToken() = refreshToken
    override suspend fun getExpiresAt(): Long? = null
    override suspend fun clearTokens() { authed = false }
    override suspend fun isAuthenticated() = authed
    override suspend fun saveEmail(email: String) { this.email = email }
    override suspend fun getEmail() = email
}

/**
 * Scripted Photos Picker API. [selection] is what the session returns once chosen; every
 * network-shaped call is counted so tests can assert the exact conversation.
 */
class FakePhotosPort(
    private val selection: List<Pair<String, String>> = emptyList(),
    private val pollsUntilSelected: Int = 1,
    private val createFails: Boolean = false,
    private val failDownloadFor: Set<String> = emptySet(),
    private val emptyBytesFor: Set<String> = emptySet(),
) : PhotosPickerPort {
    var polls = 0
    var created = 0
    val deletedSessions = mutableListOf<String>()
    val downloaded = mutableListOf<String>()

    override suspend fun createPhotosPickerSession(): Either<DomainError, PhotosPickerSession> {
        if (createFails) return DomainError.NetworkError.HttpError(500, "boom").left()
        created++
        return PhotosPickerSession(id = "s1", pickerUri = "https://photos.example/pick/s1").right()
    }

    override suspend fun getPickerSession(sessionId: String): Either<DomainError, PhotosPickerSession> {
        polls++
        return PhotosPickerSession(sessionId, "https://photos.example/pick/$sessionId", mediaItemsSet = polls >= pollsUntilSelected).right()
    }

    override suspend fun downloadPickerMedia(baseUrl: String, mediaItemId: String): Either<DomainError, ByteArray> {
        downloaded += mediaItemId
        return when (mediaItemId) {
            in failDownloadFor -> DomainError.NetworkError.HttpError(404, "gone").left()
            in emptyBytesFor -> ByteArray(0).right()
            else -> "bytes-$mediaItemId".encodeToByteArray().right()
        }
    }

    override suspend fun listPickerMediaItems(sessionId: String): Either<DomainError, List<Pair<String, String>>> = selection.right()

    override suspend fun deletePickerSession(sessionId: String): Either<DomainError, Unit> {
        deletedSessions += sessionId
        return Unit.right()
    }
}

/** Records every [attachBytes] call; [failFor] names files whose save fails. */
class RecordingAttachments(private val failFor: Set<String> = emptySet()) : MediaAttachmentService {
    val saved = mutableListOf<Pair<String, ByteArray>>()
    override suspend fun pickAndAttach(notesPath: EffectiveNotesPath, pageRelativePath: String): Either<DomainError, AttachmentResult>? = null
    override suspend fun attachBytes(bytes: ByteArray, suggestedName: String, notesPath: EffectiveNotesPath): Either<DomainError, AttachmentResult>? {
        if (suggestedName in failFor) return DomainError.AttachmentError.CopyFailed("disk full").left()
        saved += suggestedName to bytes
        return AttachmentResult("../assets/$suggestedName", suggestedName).right()
    }
}

/** Scripted Drive listing; [failure] is returned instead of files when set. */
class FakeDriveBrowser(
    var files: List<DriveFile> = emptyList(),
    var failure: DomainError? = null,
) : DriveBrowserPort {
    val listed = mutableListOf<String?>()
    override suspend fun listFiles(folderId: String?): Either<DomainError, List<DriveFile>> {
        listed += folderId
        return failure?.left() ?: files.right()
    }
    override suspend fun downloadFile(fileId: String): Either<DomainError, ByteArray> = ByteArray(1).right()
}
