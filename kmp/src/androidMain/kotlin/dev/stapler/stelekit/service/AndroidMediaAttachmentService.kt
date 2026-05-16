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
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okio.FileSystem
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
    private val launchGalleryPicker: suspend () -> Uri?,
) : MediaAttachmentService {

    override suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String
    ): Either<DomainError, AttachmentResult>? = withContext(PlatformDispatcher.IO) {
        val uri: Uri? = launchGalleryPicker()
        if (uri == null) return@withContext null

        val assetsDir = File("$graphRoot/assets")
        try {
            assetsDir.mkdirs()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext DomainError.AttachmentError.AssetsDirectoryFailed(
                e.message ?: "unknown"
            ).left()
        }

        val displayName = resolveDisplayName(uri) ?: "attachment"
        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = if (displayName.contains('.')) displayName.substringAfterLast('.') else ""
        val uniqueName = uniqueFileName(assetsDir.toOkioPath(), stem, ext, FileSystem.SYSTEM)
        val destFile = File(assetsDir, uniqueName)
        val tmpFile = File(assetsDir, ".tmp-${java.util.UUID.randomUUID()}")

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext DomainError.AttachmentError.CopyFailed(
                    "ContentResolver returned null for $uri"
                ).left()
            inputStream.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            tmpFile.delete()
            return@withContext DomainError.AttachmentError.CopyFailed(
                e.message ?: "copy failed"
            ).left()
        }

        AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
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
                val assetsDir = File("$graphRoot/assets")
                try {
                    assetsDir.mkdirs()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@withContext DomainError.AttachmentError.AssetsDirectoryFailed(e.message ?: "unknown").left()
                }
                val uniqueName = uniqueFileName(assetsDir.toOkioPath(), "clipboard", ext, FileSystem.SYSTEM)
                val destFile = File(assetsDir, uniqueName)
                val tmpFile = File(assetsDir, ".tmp-${java.util.UUID.randomUUID()}")
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: return@withContext DomainError.AttachmentError.CopyFailed("ContentResolver returned null").left()
                    inputStream.use { input -> tmpFile.outputStream().use { output -> input.copyTo(output) } }
                    Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    tmpFile.delete()
                    return@withContext DomainError.AttachmentError.CopyFailed(e.message ?: "copy failed").left()
                }
                return@withContext AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
            }
            null
        }

    private fun resolveDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        ) ?: return null
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }
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
fun rememberAndroidMediaAttachmentService(context: Context): AndroidMediaAttachmentService {
    var pendingContinuation: Continuation<Uri?>? = null

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        pendingContinuation?.resume(uri)
        pendingContinuation = null
    }

    return remember(context) {
        AndroidMediaAttachmentService(
            context = context,
            launchGalleryPicker = {
                suspendCancellableCoroutine { cont ->
                    pendingContinuation = cont
                    cont.invokeOnCancellation { pendingContinuation = null }
                    launcher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
        )
    }
}
