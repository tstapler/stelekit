// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okio.FileSystem as OkioFileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Android implementation of [MediaAttachmentService].
 *
 * Use [rememberAndroidMediaAttachmentService] in a Composable to create an instance
 * with properly scoped [ActivityResultLauncher]s.
 */
class AndroidMediaAttachmentService(
    private val context: Context,
    private val fileSystem: FileSystem,
    private val launchGalleryPicker: suspend () -> Uri?,
) : MediaAttachmentService {

    override suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String
    ): Either<DomainError, AttachmentResult>? {
        val uri: Uri? = withContext(Dispatchers.Main) { launchGalleryPicker() }
        if (uri == null) return null

        return withContext(PlatformDispatcher.IO) {
            if (graphRoot.startsWith("saf://")) {
                pickAndAttachSaf(uri, graphRoot)
            } else {
                pickAndAttachLegacy(uri, graphRoot)
            }
        }
    }

    private fun pickAndAttachSaf(uri: Uri, graphRoot: String): Either<DomainError, AttachmentResult> {
        val assetsPath = "$graphRoot/assets"
        if (!fileSystem.directoryExists(assetsPath)) {
            if (!fileSystem.createDirectory(assetsPath)) {
                return DomainError.AttachmentError.AssetsDirectoryFailed(
                    "Could not create assets directory: $assetsPath"
                ).left()
            }
        }
        val displayName = resolveDisplayName(uri) ?: "attachment"
        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = if (displayName.contains('.')) displayName.substringAfterLast('.') else ""
        val uniqueName = uniqueFileName(assetsPath, stem, ext, fileSystem)
        val bytes = readUriBytes(uri).getOrElse { return it.left() }
        return if (fileSystem.writeFileBytes("$assetsPath/$uniqueName", bytes)) {
            AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
        } else {
            DomainError.AttachmentError.CopyFailed("writeFileBytes returned false for $assetsPath/$uniqueName").left()
        }
    }

    private fun pickAndAttachLegacy(uri: Uri, graphRoot: String): Either<DomainError, AttachmentResult> {
        val assetsDir = File("$graphRoot/assets")
        try {
            assetsDir.mkdirs()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return DomainError.AttachmentError.AssetsDirectoryFailed(e.message ?: "unknown").left()
        }

        val displayName = resolveDisplayName(uri) ?: "attachment"
        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = if (displayName.contains('.')) displayName.substringAfterLast('.') else ""
        val uniqueName = uniqueFileName(assetsDir.toOkioPath(), stem, ext, OkioFileSystem.SYSTEM)
        val destFile = File(assetsDir, uniqueName)
        val tmpFile = File(assetsDir, ".tmp-${java.util.UUID.randomUUID()}")

        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return DomainError.AttachmentError.CopyFailed(
                    "ContentResolver returned null for $uri"
                ).left()
            inputStream.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            try {
                Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                try {
                    Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e2: Exception) {
                    if (e2 is CancellationException) throw e2
                    tmpFile.delete()
                    return DomainError.AttachmentError.CopyFailed(e2.message ?: "move failed").left()
                }
            }
            AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            tmpFile.delete()
            DomainError.AttachmentError.CopyFailed(e.message ?: "copy failed").left()
        }
    }

    override fun hasClipboardImage(): Boolean {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return false
        val clip = cm.primaryClip ?: return false
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i)?.uri ?: continue
            val type = context.contentResolver.getType(uri) ?: continue
            if (type.startsWith("image/")) return true
        }
        return false
    }

    override suspend fun pasteFromClipboard(graphRoot: String): Either<DomainError, AttachmentResult>? =
        withContext(PlatformDispatcher.IO) {
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                ?: return@withContext null
            val clip = cm.primaryClip ?: return@withContext null
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i)?.uri ?: continue
                val mimeType = context.contentResolver.getType(uri) ?: continue
                if (!mimeType.startsWith("image/")) continue
                val ext = mimeType.substringAfter("image/").substringBefore(";").let { if (it == "jpeg") "jpg" else it }
                if (graphRoot.startsWith("saf://")) {
                    val assetsPath = "$graphRoot/assets"
                    if (!fileSystem.directoryExists(assetsPath)) {
                        if (!fileSystem.createDirectory(assetsPath)) {
                            return@withContext DomainError.AttachmentError.AssetsDirectoryFailed(
                                "Could not create assets directory: $assetsPath"
                            ).left()
                        }
                    }
                    val uniqueName = uniqueFileName(assetsPath, "clipboard", ext, fileSystem)
                    val bytes = readUriBytes(uri).getOrElse { return@withContext it.left() }
                    return@withContext if (fileSystem.writeFileBytes("$assetsPath/$uniqueName", bytes)) {
                        AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
                    } else {
                        DomainError.AttachmentError.CopyFailed("writeFileBytes returned false").left()
                    }
                }
                val assetsDir = File("$graphRoot/assets")
                try {
                    assetsDir.mkdirs()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@withContext DomainError.AttachmentError.AssetsDirectoryFailed(e.message ?: "unknown").left()
                }
                val uniqueName = uniqueFileName(assetsDir.toOkioPath(), "clipboard", ext, OkioFileSystem.SYSTEM)
                val destFile = File(assetsDir, uniqueName)
                val tmpFile = File(assetsDir, ".tmp-${java.util.UUID.randomUUID()}")
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: return@withContext DomainError.AttachmentError.CopyFailed("ContentResolver returned null").left()
                    inputStream.use { input -> tmpFile.outputStream().use { output -> input.copyTo(output) } }
                    try {
                        Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        try {
                            Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        } catch (e2: Exception) {
                            if (e2 is CancellationException) throw e2
                            tmpFile.delete()
                            return@withContext DomainError.AttachmentError.CopyFailed(e2.message ?: "move failed").left()
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    tmpFile.delete()
                    return@withContext DomainError.AttachmentError.CopyFailed(e.message ?: "copy failed").left()
                }
                return@withContext AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
            }
            null
        }

    private fun readUriBytes(uri: Uri): Either<DomainError, ByteArray> {
        val tmpFile = File(context.cacheDir, ".tmp-${java.util.UUID.randomUUID()}")
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return DomainError.AttachmentError.CopyFailed("ContentResolver returned null stream for $uri").left()
            inputStream.use { input -> tmpFile.outputStream().use { output -> input.copyTo(output) } }
            tmpFile.readBytes().right()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { DomainError.AttachmentError.CopyFailed(e.message ?: "copy failed").left() }
        finally { tmpFile.delete() }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        ) ?: return null
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

}

private class ContinuationHolder {
    var continuation: Continuation<Uri?>? = null
}

/**
 * Composable factory that wires [ActivityResultLauncher]s and returns an
 * [AndroidMediaAttachmentService] stable across recompositions.
 *
 * Call this once per screen (e.g. in PageView or JournalsView), not per-block.
 *
 * Uses [ActivityResultContracts.PickVisualMedia] (Android Photo Picker, no permission
 * required on API 21+ via Google Play Services backport).
 */
@Composable
fun rememberAndroidMediaAttachmentService(
    context: Context,
    fileSystem: FileSystem,
): AndroidMediaAttachmentService {
    val holder = remember { ContinuationHolder() }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        holder.continuation?.resume(uri)
        holder.continuation = null
    }

    return remember(context, fileSystem) {
        AndroidMediaAttachmentService(
            context = context,
            fileSystem = fileSystem,
            launchGalleryPicker = {
                suspendCancellableCoroutine { cont ->
                    holder.continuation = cont
                    cont.invokeOnCancellation { holder.continuation = null }
                    launcher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
        )
    }
}
