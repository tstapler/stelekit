package dev.stapler.stelekit.platform

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only cache that mirrors SAF-backed markdown files to `filesDir` so Phase 3
 * background indexing can read them without Binder IPC overhead.
 *
 * Invariants:
 * - SAF is the only write target; shadow is a read cache derived from SAF.
 * - All shadow writes go through [update] or [syncFromSaf]; callers never write to
 *   the shadow directory directly.
 * - On crash between SAF write and [update], the stale shadow is detected by mtime
 *   comparison in [syncFromSaf] on the next startup and re-synced.
 */
internal class ShadowFileCache(context: Context, graphId: String) {
    private val shadowRoot = File(context.filesDir, "graphs/$graphId/shadow")

    companion object {
        private const val TAG = "ShadowFileCache"

        /** Converts a SAF tree document ID (e.g. "primary:personal-wiki/logseq") to a
         *  filesystem-safe directory name by replacing unsafe characters. */
        fun graphIdFor(treeDocId: String): String =
            treeDocId.replace(':', '-').replace('/', '-').replace(' ', '_').take(128)
    }

    /**
     * Returns a [File] resolved from [base]/[relativePath] only if the canonical path stays
     * within [base]. Returns null and logs a warning if path traversal is detected.
     */
    private fun safeShadowFile(base: File, relativePath: String): File? {
        val target = File(base, relativePath).canonicalFile
        return if (target.path.startsWith(base.canonicalPath + File.separator) ||
                   target.path == base.canonicalPath) target else {
            Log.w(TAG, "safeShadowFile: path escape blocked for '$relativePath'")
            null
        }
    }

    init {
        shadowRoot.mkdirs()
    }

    /**
     * Copies only stale files from SAF to shadow.
     *
     * [subdir] is "pages" or "journals". [fileModTimes] is the output of
     * `listFilesWithModTimes(path)` — one batch SAF cursor, not per-file queries.
     * [readSafFile] performs a direct SAF read for the given filename (must bypass shadow).
     */
    suspend fun syncFromSaf(
        subdir: String,
        fileModTimes: List<Pair<String, Long>>,
        readSafFile: (String) -> String?,
    ) = withContext(Dispatchers.IO) {
        val subdirFile = File(shadowRoot, subdir).also { it.mkdirs() }
        for ((fileName, safMtime) in fileModTimes) {
            val shadowFile = safeShadowFile(subdirFile, fileName) ?: continue
            // Skip if shadow is already fresh (mtime matches SAF)
            if (shadowFile.exists() && safMtime > 0L && shadowFile.lastModified() >= safMtime) {
                continue
            }
            val content = readSafFile(fileName) ?: continue
            try {
                shadowFile.writeText(content)
                if (safMtime > 0L) shadowFile.setLastModified(safMtime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "syncFromSaf: failed to write shadow $subdir/$fileName", e)
            }
        }
    }

    /**
     * Returns the shadow [File] for [relativePath] (e.g. "pages/Foo.md") if it exists
     * and is non-empty; returns null when shadow is absent (caller should fall back to SAF).
     */
    fun resolve(relativePath: String): File? {
        val f = safeShadowFile(shadowRoot, relativePath) ?: return null
        return if (f.exists() && f.length() > 0) f else null
    }

    /** Writes [content] to the shadow after a successful SAF write. */
    fun update(relativePath: String, content: String) {
        try {
            val f = safeShadowFile(shadowRoot, relativePath) ?: return
            f.parentFile?.mkdirs()
            f.writeText(content)
        } catch (e: Exception) {
            Log.w(TAG, "update: failed to write shadow for $relativePath", e)
        }
    }

    /**
     * Deletes shadow entries whose mtime is older than the corresponding SAF file.
     *
     * Uses [fileModTimes] from a batch SAF cursor (one IPC per directory, not per file).
     * Never reads file content — local deletes only, so callers incur zero extra SAF IPC.
     * After this call, stale entries are absent and [readFile] falls through to SAF naturally.
     */
    fun invalidateStale(subdir: String, fileModTimes: List<Pair<String, Long>>) {
        val subdirFile = File(shadowRoot, subdir)
        for ((fileName, safMtime) in fileModTimes) {
            if (safMtime <= 0L) continue
            val shadowFile = File(subdirFile, fileName)
            if (shadowFile.exists() && shadowFile.lastModified() < safMtime) {
                shadowFile.delete()
            }
        }
    }

    /** Deletes the shadow file for [relativePath], forcing a re-sync on next access. */
    fun invalidate(relativePath: String) {
        safeShadowFile(shadowRoot, relativePath)?.delete()
    }

    /** Deletes the entire shadow directory (called on SAF permission revoke). */
    fun deleteAll() {
        shadowRoot.deleteRecursively()
    }
}
