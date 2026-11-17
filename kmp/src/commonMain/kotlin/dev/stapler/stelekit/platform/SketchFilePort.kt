// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.CancellationException

/** A file the user chose or dropped: its [name], size and a bounded reader. Nothing is read until the importer asks. */
class PickedImportFile(val name: String, val source: ImportSource)

/** What saving an export came to. */
sealed interface SketchSaveOutcome {
    /** [folder] is the folder's name when the platform knows it; [path] is set only where the app can open the file. */
    data class Saved(val name: String, val folder: String?, val path: String?) : SketchSaveOutcome

    /** The user closed the save dialog. */
    data object Cancelled : SketchSaveOutcome

    /** The chosen location could not be written. */
    data object Unwritable : SketchSaveOutcome

    /** The browser refused the download. */
    data object Blocked : SketchSaveOutcome
}

/**
 * The file seam of the sketch import and export dialogs (Stories 5.1.5 and 5.2.3): pick one file to import, save one
 * file out. Both go through the existing [FileSystem] pickers, so no platform gets a second picker implementation.
 */
interface SketchFilePort {
    /** False where the platform has no picker at all (iOS today): the Import menu item then shows its reason instead. */
    val canPick: Boolean

    /** The file the user chose, or null when the dialog was closed or the file could not be read. */
    suspend fun pickImportFile(title: String, extensions: List<String>): PickedImportFile?

    suspend fun save(suggestedName: String, mimeType: String, bytes: ByteArray): SketchSaveOutcome
}

/**
 * [SketchFilePort] over a [FileSystem]. [web] marks the browser, where a save is a download: a failed write there means the
 * browser blocked it, and there is no folder or path to report. [opensSavedFiles] says the app can open what it saved.
 */
class FileSystemSketchFilePort(
    private val fs: FileSystem,
    private val web: Boolean = false,
    private val opensSavedFiles: Boolean = false,
    private val canPickFiles: Boolean = true,
    private val limits: SceneLimits = SceneLimits.Default,
) : SketchFilePort {
    override val canPick: Boolean get() = canPickFiles

    override suspend fun pickImportFile(title: String, extensions: List<String>): PickedImportFile? {
        val path = fs.pickFileAsync(title, extensions) ?: return null
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val size = fs.getFileSize(path)
        if (size != null && size > limits.maxImportBytes) return PickedImportFile(name, unread(size))
        val bytes = fs.readFileBytesSuspend(path) ?: return null
        return PickedImportFile(name, ImportSource.ofBytes(bytes))
    }

    override suspend fun save(suggestedName: String, mimeType: String, bytes: ByteArray): SketchSaveOutcome {
        val path = fs.pickSaveFileAsync(suggestedName, mimeType) ?: return SketchSaveOutcome.Cancelled
        val written = try {
            fs.writeFileBytes(path, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return when {
            written && web -> SketchSaveOutcome.Saved(suggestedName, folder = null, path = null)
            written -> SketchSaveOutcome.Saved(suggestedName, folderOf(path), path.takeIf { opensSavedFiles })
            web -> SketchSaveOutcome.Blocked
            else -> SketchSaveOutcome.Unwritable
        }
    }

    private fun folderOf(path: String): String? =
        if (path.contains("://")) null else path.substringBeforeLast('/', "").substringAfterLast('/').takeIf { it.isNotEmpty() }

    /** A source that only knows its size: the importer refuses it for size before it reads a byte. */
    private fun unread(size: Long): ImportSource = object : ImportSource {
        override val size: Long = size
        override fun read(offset: Long, length: Int): ByteArray = ByteArray(0)
    }
}

/** A file dropped on the canvas; [source] reads it by range. The platform handler has not parsed anything. */
class DroppedCanvasFile(val name: String, val source: ImportSource)

/**
 * The canvas-local file drop and text paste seam (Story 5.1.5b, UX-135). The web handler (`CanvasFileDrop` in `wasmJsMain`)
 * listens on the canvas element only: the page-level drop target is a no-op on wasm (BUG-004). Returns the disposer.
 */
interface CanvasFileDropHost {
    fun install(onFile: (DroppedCanvasFile) -> Unit, onPasteText: (String) -> Unit): () -> Unit
}
