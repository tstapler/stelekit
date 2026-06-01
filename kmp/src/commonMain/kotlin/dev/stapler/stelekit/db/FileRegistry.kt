@file:Suppress("InMemoryPagination") // slicing a file-path list — no SQL involved
package dev.stapler.stelekit.db

import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for file metadata across all GraphLoader operations.
 *
 * Performs one scan per directory, caches the results, and provides filtered
 * views (recent journals, remaining journals, pages, changed files) without
 * re-scanning. Owns all mod-time and content-hash state that was previously
 * scattered across four code paths in GraphLoader.
 */
class FileRegistry(private val fileSystem: FileSystem) {

    private val modTimes = mutableMapOf<String, Long>()
    private val contentHashes = mutableMapOf<String, Int>()

    // Serializes detectChanges so concurrent callers (polling loop + ContentObserver
    // callback) cannot both read the same stale modTime and double-emit the same change.
    private val detectMutex = Mutex()

    /** Cached scan results per directory. */
    private val scannedFiles = mutableMapOf<String, List<FileEntry>>()

    // ---- Scan & Register ----

    /**
     * Scans a directory for .md and .md.stek files, records all mod times, and caches the result.
     * Subsequent calls to [journalFiles], [recentJournals], etc. operate on this cached list.
     *
     * Acquires [detectMutex] so writes to [modTimes] and [scannedFiles] are serialized with
     * [detectChanges], eliminating the concurrent HashMap mutation race on the JVM.
     */
    suspend fun scanDirectory(dirPath: String): List<FileEntry> = detectMutex.withLock {
        if (!fileSystem.directoryExists(dirPath)) return@withLock emptyList()

        val entries = fileSystem.listFilesWithModTimes(dirPath)
            .filter { (name, _) -> name.endsWith(".md") || name.endsWith(".md.stek") }
            .map { (fileName, modTime) ->
                val filePath = "$dirPath/$fileName"
                modTimes[filePath] = modTime
                // Content hashes are populated lazily by detectChanges when a file change is
                // detected. Reading every file here to pre-populate hashes costs O(N) SAF IPC
                // calls on Android (thousands of round-trips for large graphs), adding 15-30s
                // to startup. The markWrittenByUs mechanism is the primary own-write guard;
                // the hash check is a belt-and-suspenders fallback that can tolerate lazy init.
                FileEntry(fileName, filePath, modTime)
            }

        scannedFiles[dirPath] = entries
        entries
    }

    /**
     * Returns journal files from the last scan, filtered by [JournalUtils.isJournalName]
     * and sorted descending (most recent first).
     * Callers must invoke [scanDirectory] before calling this method.
     */
    fun journalFiles(dirPath: String): List<FileEntry> {
        val entries = scannedFiles[dirPath] ?: emptyList()
        return entries
            .filter { JournalUtils.isJournalName(it.fileName.removeSuffix(".md.stek").removeSuffix(".md")) }
            .sortedByDescending { it.fileName }
    }

    /** Recent N journals from the cached scan. */
    fun recentJournals(dirPath: String, count: Int): List<FileEntry> =
        journalFiles(dirPath).take(count)

    /** Journals after skipping the first [skip], taking up to [take]. */
    fun remainingJournals(dirPath: String, skip: Int, take: Int): List<FileEntry> =
        journalFiles(dirPath).drop(skip).take(take)

    /** All .md files from the cached scan (no journal filter), sorted alphabetically.
     * Callers must invoke [scanDirectory] before calling this method.
     */
    fun pageFiles(dirPath: String): List<FileEntry> {
        val entries = scannedFiles[dirPath] ?: emptyList()
        return entries.sortedBy { it.fileName }
    }

    // ---- Change Detection ----

    /**
     * Compares current disk state against registered mod times.
     * Reads file content only for actually-changed files to apply content-hash guard
     * (suppresses false positives from our own writes).
     *
     * Returns a [ChangeSet] with new, changed, and deleted files.
     */
    suspend fun detectChanges(dirPath: String): ChangeSet = detectMutex.withLock {
        if (!fileSystem.directoryExists(dirPath)) return@withLock ChangeSet.EMPTY

        val currentFilesWithTimes = fileSystem.listFilesWithModTimes(dirPath)
            .filter { (name, _) -> name.endsWith(".md") || name.endsWith(".md.stek") }
        val newFiles = mutableListOf<ChangedFile>()
        val changedFiles = mutableListOf<ChangedFile>()
        val currentPaths = HashSet<String>(currentFilesWithTimes.size * 2)

        for ((fileName, modTime) in currentFilesWithTimes) {
            val filePath = "$dirPath/$fileName"
            currentPaths.add(filePath)
            val lastKnown = modTimes[filePath]
            val isEncrypted = fileName.endsWith(".md.stek")

            if (lastKnown == null) {
                // New file — not in registry.
                // Encrypted files are binary; content is read via readFileDecrypted at the call site.
                val content = if (isEncrypted) "" else fileSystem.readFile(filePath) ?: continue
                modTimes[filePath] = modTime
                if (!isEncrypted) contentHashes[filePath] = content.hashCode()
                newFiles.add(ChangedFile(FileEntry(fileName, filePath, modTime), content))
            } else if (modTime > lastKnown) {
                if (isEncrypted) {
                    // Encrypted files are binary — skip the text content-hash guard.
                    // modTime change alone is sufficient signal; markWrittenByUs keeps own-write
                    // suppression accurate via the modTimes map.
                    modTimes[filePath] = modTime
                    changedFiles.add(ChangedFile(FileEntry(fileName, filePath, modTime), ""))
                } else {
                    // Mod time changed — invalidate shadow first so readFile falls through
                    // to the real file (SAF on Android) rather than a stale on-device cache.
                    // On JVM, invalidateShadow is a no-op; zero cost on desktop.
                    // markWrittenByUs stores the SAF-queried mtime so own-written files satisfy
                    // modTime == lastKnown and never enter this branch.
                    fileSystem.invalidateShadow(filePath)
                    val content = fileSystem.readFile(filePath) ?: continue
                    val newHash = content.hashCode()
                    // hashCode() is a 32-bit guard — false-negative probability ~1/4B per file.
                    // Accepted trade-off: one missed external edit is less harmful than per-file SHA computation on startup.
                    if (contentHashes[filePath] == newHash) {
                        // Same content (our own write) — update mod time, skip
                        modTimes[filePath] = modTime
                        continue
                    }
                    modTimes[filePath] = modTime
                    contentHashes[filePath] = newHash
                    changedFiles.add(ChangedFile(FileEntry(fileName, filePath, modTime), content))
                }
            }
        }

        // Detect deleted files — currentPaths was built in the loop above, no second pass needed
        val deletedPaths = modTimes.keys
            .filter { it.startsWith("$dirPath/") && it !in currentPaths }
            .toList()
        deletedPaths.forEach { path ->
            modTimes.remove(path)
            contentHashes.remove(path)
        }

        ChangeSet(newFiles, changedFiles, deletedPaths)
    }

    // ---- Write Tracking ----

    /**
     * Marks a file as written by the app. Updates mod time and content hash
     * so the watcher's content-hash guard will suppress the next detection.
     *
     * Acquires [detectMutex] so this update is atomic with respect to [detectChanges],
     * eliminating the race where a concurrent [detectChanges] could read a stale modTime
     * for a `.md.stek` file (where the content-hash guard is disabled) and emit a spurious
     * own-write event.
     */
    suspend fun markWrittenByUs(filePath: String) = detectMutex.withLock {
        val modTime = fileSystem.getLastModifiedTime(filePath) ?: return@withLock
        modTimes[filePath] = modTime
        // Binary encrypted files cannot be read as text — modTime update alone is sufficient
        // for own-write suppression (detectChanges skips the content-hash guard for .md.stek).
        if (!filePath.endsWith(".md.stek")) {
            val content = fileSystem.readFile(filePath)
            if (content != null) {
                contentHashes[filePath] = content.hashCode()
            }
        }
    }

    /** Updates mod time for a file (after parseAndSavePage). */
    suspend fun updateModTime(filePath: String, modTime: Long) = detectMutex.withLock {
        modTimes[filePath] = modTime
    }

    /** Updates content hash for a file. */
    suspend fun updateContentHash(filePath: String, contentHash: Int) = detectMutex.withLock {
        contentHashes[filePath] = contentHash
    }

    /** Returns the stored content hash for [filePath], or null if not yet hashed. */
    suspend fun getContentHash(filePath: String): Int? = detectMutex.withLock {
        contentHashes[filePath]
    }

    /**
     * Marks [filePath] as a pending own-write. Prevents the watcher from treating the file as
     * an external change during the window between this call and [markWrittenByUs].
     *
     * Implementation: stores sentinel value [Long.MAX_VALUE] in modTimes so that the
     * `modTime > lastKnown` check in [detectChanges] can never be true for any real mtime.
     * [markWrittenByUs] replaces it with the real post-write mtime.
     *
     * IMPORTANT: If the write fails (saga compensation runs), call [clearPendingWrite] to remove
     * the sentinel. Without this, the file is permanently suppressed from external-change
     * detection for the lifetime of this [FileRegistry] instance.
     */
    suspend fun preMarkPendingWrite(filePath: String) = detectMutex.withLock {
        modTimes[filePath] = Long.MAX_VALUE
    }

    /**
     * Removes the pending-write sentinel for [filePath]. Must be called from saga compensation
     * if the file write fails after [preMarkPendingWrite] was called.
     * Restores the file to the "unknown" state so the next [detectChanges] treats it as a
     * new/unknown file and re-scans it.
     */
    suspend fun clearPendingWrite(filePath: String) = detectMutex.withLock {
        // Only clear if it's still the sentinel; markWrittenByUs may have already replaced it.
        if (modTimes[filePath] == Long.MAX_VALUE) {
            modTimes.remove(filePath)
        }
    }

    // ---- Cleanup ----

    suspend fun clear() = detectMutex.withLock {
        modTimes.clear()
        contentHashes.clear()
        scannedFiles.clear()
    }
}

data class FileEntry(
    val fileName: String,
    val filePath: String,
    val modTime: Long
)

/**
 * A file that was detected as new or changed, including its content
 * (already read during detection to apply the content-hash guard).
 */
data class ChangedFile(
    val entry: FileEntry,
    val content: String
)

data class ChangeSet(
    val newFiles: List<ChangedFile>,
    val changedFiles: List<ChangedFile>,
    val deletedPaths: List<String>
) {
    companion object {
        val EMPTY = ChangeSet(emptyList(), emptyList(), emptyList())
    }
}
