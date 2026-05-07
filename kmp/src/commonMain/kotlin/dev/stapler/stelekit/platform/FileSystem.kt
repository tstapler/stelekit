package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope

interface FileSystem {
    fun getDefaultGraphPath(): String
    fun expandTilde(path: String): String
    fun readFile(path: String): String?
    fun writeFile(path: String, content: String): Boolean
    fun listFiles(path: String): List<String>
    fun listDirectories(path: String): List<String>
    fun fileExists(path: String): Boolean
    fun directoryExists(path: String): Boolean
    fun createDirectory(path: String): Boolean
    fun deleteFile(path: String): Boolean
    fun pickDirectory(): String?
    suspend fun pickDirectoryAsync(): String? = pickDirectory()
    fun getLastModifiedTime(path: String): Long?

    /**
     * Returns file names paired with their last-modified timestamps in one pass.
     * Default implementation calls [listFiles] + [getLastModifiedTime] per file;
     * JVM overrides this with a single [File.listFiles] traversal to avoid
     * per-file path reconstruction and [validatePath] overhead.
     */
    fun listFilesWithModTimes(path: String): List<Pair<String, Long>> =
        listFiles(path).map { name -> name to (getLastModifiedTime("$path/$name") ?: 0L) }

    fun hasStoragePermission(): Boolean = true
    fun getLibraryDisplayName(): String? = null
    /** Human-readable name for a given graph path. Defaults to the last path segment. */
    fun displayNameForPath(path: String): String =
        path.substringAfterLast("/").substringAfterLast("\\").ifEmpty { path }
    fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) { /* no-op */ }
    fun stopExternalChangeDetection() { /* no-op */ }
    /** Rename/move [from] to [to]. Returns false if not supported on this platform. */
    fun renameFile(from: String, to: String): Boolean = false

    /** Platform-appropriate directory for user-facing exported files (e.g. ~/Downloads). */
    fun getDownloadsPath(): String = expandTilde("~/Downloads")

    /**
     * Opens a platform-native "save file" dialog and returns the chosen path/URI, or null
     * if the user cancelled or the platform doesn't support native file picking.
     * On Android this launches ACTION_CREATE_DOCUMENT; on desktop a save dialog.
     * Callers must pass the result directly to [writeFile].
     */
    suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String = "application/json"): String? = null

    /**
     * Read raw bytes from a file. Used by paranoid-mode decryption to read STEK-format files.
     * Default implementation reads via [readFile] and encodes to UTF-8 bytes.
     * JVM override uses direct byte-level IO to preserve binary integrity.
     */
    fun readFileBytes(path: String): ByteArray? = readFile(path)?.encodeToByteArray()

    /**
     * Write raw bytes to a file. Used by paranoid-mode encryption.
     * Default: decode as UTF-8 and call [writeFile] (works for text, not binary).
     * JVM override uses direct byte-level IO.
     */
    fun writeFileBytes(path: String, data: ByteArray): Boolean = writeFile(path, data.decodeToString())

    /** Updates the shadow copy after a SAF write. No-op on non-SAF file systems. */
    fun updateShadow(path: String, content: String) { /* no-op */ }

    /** Invalidates the shadow copy for [path], forcing a re-sync on next read. No-op on non-SAF. */
    fun invalidateShadow(path: String) { /* no-op */ }

    /**
     * Syncs the shadow copy for [graphPath] from SAF using batch mtime queries.
     * No-op on non-SAF file systems. Should run concurrently with Phase 2 metadata loading
     * so Phase 3 reads can use the shadow instead of SAF Binder IPC.
     */
    suspend fun syncShadow(graphPath: String) { /* no-op */ }
}
