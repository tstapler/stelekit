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
     * Scans a directory for .md files, records all mod times, and caches the result.
     * Subsequent calls to [journalFiles], [recentJournals], etc. operate on this cached list.
     */
    fun scanDirectory(dirPath: String): List<FileEntry> {
        if (!fileSystem.directoryExists(dirPath)) return emptyList()

        val entries = fileSystem.listFilesWithModTimes(dirPath)
            .filter { (name, _) -> name.endsWith(".md") }
            .map { (fileName, modTime) ->
                val filePath = "$dirPath/$fileName"
                modTimes[filePath] = modTime
                // Initialise content hash so the guard in detectChanges has a baseline.
                // Without this, any mtime change after startup bypasses the hash guard
                // (null != actualHash is always true), causing own-write false positives.
                val content = fileSystem.readFile(filePath)
                if (content != null) contentHashes[filePath] = content.hashCode()
                FileEntry(fileName, filePath, modTime)
            }

        scannedFiles[dirPath] = entries
        return entries
    }

    /**
     * Returns journal files from the last scan, filtered by [JournalUtils.isJournalName]
     * and sorted descending (most recent first).
     */
    fun journalFiles(dirPath: String): List<FileEntry> {
        val entries = scannedFiles[dirPath] ?: scanDirectory(dirPath)
        return entries
            .filter { JournalUtils.isJournalName(it.fileName.removeSuffix(".md")) }
            .sortedByDescending { it.fileName }
    }

    /** Recent N journals from the cached scan. */
    fun recentJournals(dirPath: String, count: Int): List<FileEntry> =
        journalFiles(dirPath).take(count)

    /** Journals after skipping the first [skip], taking up to [take]. */
    fun remainingJournals(dirPath: String, skip: Int, take: Int): List<FileEntry> =
        journalFiles(dirPath).drop(skip).take(take)

    /** All .md files from the cached scan (no journal filter), sorted alphabetically. */
    fun pageFiles(dirPath: String): List<FileEntry> {
        val entries = scannedFiles[dirPath] ?: scanDirectory(dirPath)
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
            .filter { (name, _) -> name.endsWith(".md") }
        val newFiles = mutableListOf<ChangedFile>()
        val changedFiles = mutableListOf<ChangedFile>()
        val currentPaths = HashSet<String>(currentFilesWithTimes.size * 2)

        for ((fileName, modTime) in currentFilesWithTimes) {
            val filePath = "$dirPath/$fileName"
            currentPaths.add(filePath)
            val lastKnown = modTimes[filePath]

            if (lastKnown == null) {
                // New file — not in registry
                val content = fileSystem.readFile(filePath) ?: continue
                modTimes[filePath] = modTime
                contentHashes[filePath] = content.hashCode()
                newFiles.add(ChangedFile(FileEntry(fileName, filePath, modTime), content))
            } else if (modTime > lastKnown) {
                // Mod time changed — check content hash guard
                val content = fileSystem.readFile(filePath) ?: continue
                val newHash = content.hashCode()
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

        // Detect deleted files — currentPaths was built in the loop above, no second pass needed
        val deletedPaths = modTimes.keys
            .filter { it.startsWith("$dirPath/") && it !in currentPaths }
            .toList()
        deletedPaths.forEach { modTimes.remove(it); contentHashes.remove(it) }

        ChangeSet(newFiles, changedFiles, deletedPaths)
    }

    // ---- Write Tracking ----

    /**
     * Marks a file as written by the app. Updates mod time and content hash
     * so the watcher's content-hash guard will suppress the next detection.
     */
    fun markWrittenByUs(filePath: String) {
        val modTime = fileSystem.getLastModifiedTime(filePath) ?: return
        modTimes[filePath] = modTime
        val content = fileSystem.readFile(filePath)
        if (content != null) {
            contentHashes[filePath] = content.hashCode()
        }
    }

    /** Updates mod time for a file (after parseAndSavePage). */
    fun updateModTime(filePath: String, modTime: Long) {
        modTimes[filePath] = modTime
    }

    /** Updates content hash for a file. */
    fun updateContentHash(filePath: String, contentHash: Int) {
        contentHashes[filePath] = contentHash
    }

    // ---- Cleanup ----

    fun clear() {
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
