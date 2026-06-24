@file:Suppress("InMemoryPagination") // slicing a file-path list — no SQL involved
package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.FilePath
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

    private val modTimes = mutableMapOf<FilePath, Long>()
    private val contentHashes = mutableMapOf<FilePath, Int>()

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
                modTimes[FilePath(filePath)] = modTime
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
            val filePathKey = FilePath(filePath)
            currentPaths.add(filePath)
            val lastKnown = modTimes[filePathKey]
            val isEncrypted = fileName.endsWith(".md.stek")

            if (lastKnown == null) {
                // New file — not in registry.
                // Encrypted files are binary; content is read via readFileDecrypted at the call site.
                val content = if (isEncrypted) "" else fileSystem.readFile(filePath) ?: continue
                modTimes[filePathKey] = modTime
                if (!isEncrypted) contentHashes[filePathKey] = content.hashCode()
                newFiles.add(ChangedFile(FileEntry(fileName, filePath, modTime), content))
            } else if (modTime > lastKnown) {
                if (isEncrypted) {
                    // Encrypted files are binary — skip the text content-hash guard.
                    // modTime change alone is sufficient signal; markWrittenByUs keeps own-write
                    // suppression accurate via the modTimes map.
                    modTimes[filePathKey] = modTime
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
                    if (contentHashes[filePathKey] == newHash) {
                        // Same content (our own write) — update mod time, skip
                        modTimes[filePathKey] = modTime
                        continue
                    }
                    modTimes[filePathKey] = modTime
                    contentHashes[filePathKey] = newHash
                    changedFiles.add(ChangedFile(FileEntry(fileName, filePath, modTime), content))
                }
            } else if (modTime < lastKnown && lastKnown != Long.MAX_VALUE && !isEncrypted) {
                // The file's mod time went BACKWARD relative to what we last recorded.
                // This happens when a sync tool (git, Syncthing, Logseq Sync) delivers a
                // file that was originally modified on another device — the provider preserves
                // the original timestamp rather than setting it to "now". The modTime > lastKnown
                // guard above misses this case entirely, so we fall through to a content-hash
                // check to determine whether the file actually changed.
                //
                // Only runs when we have a stored hash (populated by prior reads or markWrittenByUs).
                // Files we have never hashed are skipped to avoid O(N) SAF reads per poll cycle.
                val storedHash = contentHashes[filePathKey]
                if (storedHash != null) {
                    fileSystem.invalidateShadow(filePath)
                    val content = fileSystem.readFile(filePath) ?: continue
                    val newHash = content.hashCode()
                    if (storedHash != newHash) {
                        modTimes[filePathKey] = modTime
                        contentHashes[filePathKey] = newHash
                        changedFiles.add(ChangedFile(FileEntry(fileName, filePath, modTime), content))
                    } else {
                        // Same content, just an older timestamp (e.g. sync re-delivered unchanged file).
                        modTimes[filePathKey] = modTime
                    }
                }
            }
        }

        // Detect deleted files — currentPaths was built in the loop above, no second pass needed
        val deletedPathKeys = modTimes.keys
            .filter { it.value.startsWith("$dirPath/") && it.value !in currentPaths }
            .toList()
        deletedPathKeys.forEach { path ->
            modTimes.remove(path)
            contentHashes.remove(path)
        }
        val deletedPaths = deletedPathKeys.map { it.value }

        ChangeSet(newFiles, changedFiles, deletedPaths)
    }

    // ---- Write Tracking ----

    /**
     * Marks a file as written by the app. For plain `.md` files, updates the content hash
     * and sets modTimes to 0 so the next [detectChanges] poll uses the content-hash guard
     * to suppress spurious own-write events. For `.md.stek` files, the sentinel set by
     * [preMarkPendingWrite] stays active — callers must fire-and-forget a [updateModTime]
     * call to eventually replace it.
     *
     * Eliminates the [FileSystem.getLastModifiedTime] SAF call (blocked for minutes on Android
     * remote document providers). [GraphLoader.markFileWrittenByUs] fires a background
     * [getLastModifiedTime] + [updateModTime] after calling this to set the real post-write mtime.
     */
    suspend fun markWrittenByUs(filePath: String) = detectMutex.withLock {
        val filePathKey = FilePath(filePath)
        if (!filePath.endsWith(".md.stek")) {
            // Read file content for the hash guard. SAF readFile is much faster than
            // getLastModifiedTime (no remote metadata query). Set modTimes to 0 so the
            // watcher's next poll enters the "changed" branch and the hash guard suppresses it.
            // A fire-and-forget getLastModifiedTime in markFileWrittenByUs will later
            // replace 0 with the real post-write mtime (eliminates the extra readFile per poll).
            val content = fileSystem.readFile(filePath)
            if (content != null) {
                contentHashes[filePathKey] = content.hashCode()
            }
            modTimes[filePathKey] = 0L
        }
        // For .md.stek: no content-hash guard (binary content). Sentinel from
        // preMarkPendingWrite stays in place; fire-and-forget modtime update replaces it.
    }

    /** Updates mod time for a file (after parseAndSavePage). */
    suspend fun updateModTime(filePath: String, modTime: Long) = detectMutex.withLock {
        modTimes[FilePath(filePath)] = modTime
    }

    /** Updates content hash for a file. */
    suspend fun updateContentHash(filePath: String, contentHash: Int) = detectMutex.withLock {
        contentHashes[FilePath(filePath)] = contentHash
    }

    /** Returns the stored content hash for [filePath], or null if not yet hashed. */
    suspend fun getContentHash(filePath: FilePath): Int? = detectMutex.withLock {
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
    suspend fun preMarkPendingWrite(filePath: FilePath) = detectMutex.withLock {
        modTimes[filePath] = Long.MAX_VALUE
    }

    /**
     * Removes the pending-write sentinel for [filePath]. Must be called from saga compensation
     * if the file write fails after [preMarkPendingWrite] was called.
     * Restores the file to the "unknown" state so the next [detectChanges] treats it as a
     * new/unknown file and re-scans it.
     */
    suspend fun clearPendingWrite(filePath: FilePath) = detectMutex.withLock {
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
