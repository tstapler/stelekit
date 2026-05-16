// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop JVM implementation of [MediaAttachmentService].
 * Uses [FileDialog] (AWT native file chooser) to pick an image file.
 */
class JvmMediaAttachmentService : MediaAttachmentService {

    override suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String
    ): Either<DomainError, AttachmentResult>? = withContext(PlatformDispatcher.IO) {
        val selectedFile: File? = showFilePicker()
        if (selectedFile == null) return@withContext null

        val assetsDir = File("$graphRoot/assets")
        try {
            assetsDir.mkdirs()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext DomainError.AttachmentError.AssetsDirectoryFailed(
                e.message ?: "unknown"
            ).left()
        }

        val stem = selectedFile.nameWithoutExtension
        val ext = selectedFile.extension
        val uniqueName = uniqueFileName(assetsDir.toOkioPath(), stem, ext, FileSystem.SYSTEM)
        val destFile = File(assetsDir, uniqueName)
        val tmpFile = File(assetsDir, ".tmp-${java.util.UUID.randomUUID()}")

        try {
            selectedFile.inputStream().use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            try {
                Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e2: Exception) {
                if (e2 is CancellationException) throw e2
                tmpFile.delete()
                return@withContext DomainError.AttachmentError.CopyFailed(
                    e2.message ?: "move failed"
                ).left()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            tmpFile.delete()
            return@withContext DomainError.AttachmentError.CopyFailed(
                e.message ?: "copy failed"
            ).left()
        }

        AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
    }

    /**
     * Implements [MediaAttachmentService.attachFilePath] by converting the path string to a
     * [File] and delegating to [attachExistingFile].
     */
    override suspend fun attachFilePath(
        filePath: String,
        graphRoot: String
    ): Either<DomainError, AttachmentResult>? = attachExistingFile(File(filePath), graphRoot)

    /**
     * Copies an already-selected file to the graph's assets directory.
     * Same copy logic as [pickAndAttach] but skips the file picker step.
     * Used for drag-and-drop scenarios.
     */
    suspend fun attachExistingFile(
        file: File,
        graphRoot: String
    ): Either<DomainError, AttachmentResult>? = withContext(PlatformDispatcher.IO) {
        val assetsDir = File("$graphRoot/assets")
        try {
            assetsDir.mkdirs()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext DomainError.AttachmentError.AssetsDirectoryFailed(
                e.message ?: "unknown"
            ).left()
        }

        val stem = file.nameWithoutExtension
        val ext = file.extension
        val uniqueName = uniqueFileName(assetsDir.toOkioPath(), stem, ext, FileSystem.SYSTEM)
        val destFile = File(assetsDir, uniqueName)
        val tmpFile = File(assetsDir, ".tmp-${java.util.UUID.randomUUID()}")

        try {
            file.inputStream().use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            try {
                Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e2: Exception) {
                if (e2 is CancellationException) throw e2
                tmpFile.delete()
                return@withContext DomainError.AttachmentError.CopyFailed(
                    e2.message ?: "move failed"
                ).left()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            tmpFile.delete()
            return@withContext DomainError.AttachmentError.CopyFailed(
                e.message ?: "copy failed"
            ).left()
        }

        AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
    }

    /**
     * Shows a native AWT [FileDialog] on the Event Dispatch Thread.
     * Returns the selected [File], or null if cancelled.
     *
     * [FileDialog] is preferred over [javax.swing.JFileChooser] on macOS because it
     * renders as a native sheet; on Linux it falls back to GTK.
     */
    private fun showFilePicker(): File? {
        var result: File? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        java.awt.EventQueue.invokeLater {
            try {
                val dialog = FileDialog(null as Frame?, "Select Image", FileDialog.LOAD).apply {
                    setFilenameFilter { _, name ->
                        name.lowercase().let { n ->
                            n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                                n.endsWith(".png") || n.endsWith(".gif") ||
                                n.endsWith(".webp") || n.endsWith(".heic") ||
                                n.endsWith(".svg") || n.endsWith(".bmp")
                        }
                    }
                    isVisible = true
                }
                val f = dialog.file
                val dir = dialog.directory
                if (f != null && dir != null) result = File(dir, f)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return result
    }
}
