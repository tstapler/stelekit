// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.export

import android.content.Intent
import androidx.core.content.FileProvider
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.SteleKitContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Characters stripped from [AndroidGraphZipExporter]'s share-sheet file name — hoisted out of
 * [AndroidGraphZipExporter.export]'s lambda so it isn't recompiled on every call. */
private val UNSAFE_FILENAME_CHARS = Regex("[^a-zA-Z0-9._-]")

/**
 * Android [GraphZipExporter] (Story 2.2.1, Task 2.2.1e / ADR-003): recursively reads a graph's
 * markdown via [FileSystem], writes a real `.zip` with `java.util.zip.ZipOutputStream` (already
 * used elsewhere in this codebase for gzip — see `GzipHelper.jvm.kt` — same stdlib package, no
 * new dependency), and hands it to the OS share sheet exactly like [AndroidShareProvider.saveToFile]
 * does — a temp file in `cacheDir/share_export/` shared via `Intent.ACTION_SEND` + [FileProvider].
 */
class AndroidGraphZipExporter : GraphZipExporter {

    override suspend fun export(
        fileSystem: FileSystem,
        graphPath: String,
        graphName: String,
    ): Either<DomainError, Unit> = withContext(PlatformDispatcher.IO) {
        try {
            val context = SteleKitContext.context
            val cacheDir = File(context.cacheDir, "share_export").also { it.mkdirs() }
            val safeName = graphName.replace(UNSAFE_FILENAME_CHARS, "_").ifEmpty { "graph" }
            val zipFile = File(cacheDir, "$safeName.zip")

            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                for ((relativePath, _) in fileSystem.listFilesRecursiveWithModTimes(graphPath)) {
                    val content = fileSystem.readFileBytes("$graphPath/$relativePath") ?: continue
                    zip.putNextEntry(ZipEntry(relativePath))
                    zip.write(content)
                    zip.closeEntry()
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Save zip via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.ExportError.ShareFailed(e.message ?: "Failed to export zip").left()
        }
    }
}
