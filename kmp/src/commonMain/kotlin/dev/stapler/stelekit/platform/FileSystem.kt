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

    suspend fun pickFileAsync(): String? = null

    /**
     * Read raw bytes from a file. Used by paranoid-mode decryption to read STEK-format files.
     * Platforms that support paranoid mode must override this with true byte-level IO.
     * The default throws [UnsupportedOperationException] to prevent silent data corruption
     * from a round-trip through String (which mangles non-UTF-8 byte sequences).
     */
    fun readFileBytes(path: String): ByteArray? =
        throw UnsupportedOperationException("readFileBytes is not implemented for this platform. Override in a platform-specific FileSystem implementation.")

    /**
     * Write raw bytes to a file. Used by paranoid-mode encryption.
     * Platforms that support paranoid mode must override this with true byte-level IO.
     * The default throws [UnsupportedOperationException] — decoding arbitrary ciphertext as
     * UTF-8 and re-encoding it is lossy and would corrupt encrypted file content.
     */
    fun writeFileBytes(path: String, data: ByteArray): Boolean =
        throw UnsupportedOperationException("writeFileBytes is not implemented for this platform. Override in a platform-specific FileSystem implementation.")

    /**
     * Write-behind hook: writes [content] to shadow storage and enqueues [path] in the
     * dirty-page queue for background SAF flush. Returns true if write-behind is active
     * (caller should NOT call writeFile). Returns false if write-behind is not supported
     * on this platform/mode (caller must call writeFile instead).
     */
    fun markDirty(path: String, content: String): Boolean = false

    /**
     * Reads file content from the shadow cache only; returns null if no warm shadow exists.
     * Never makes a SAF Binder IPC call. Safe to call from any dispatcher.
     * On non-SAF file systems (JVM, legacy Android) always returns null.
     */
    fun readShadowOnly(path: String): String? = null

    /**
     * Returns true if the shadow cache has a warm (non-empty) entry for [path].
     * A warm shadow implies the file was previously written to SAF successfully.
     * Never makes a SAF Binder IPC call. On non-SAF file systems always returns false.
     */
    fun shadowExists(path: String): Boolean = false

    /** Flush all pending write-behind pages to SAF. No-op on platforms without write-behind. */
    suspend fun flushPendingWrites() {}

    /**
     * Registers a callback invoked after each successful write-behind flush to SAF.
     * Used to call [dev.stapler.stelekit.db.GraphLoader.markFileWrittenByUs] so that
     * the FileRegistry can record the post-flush SAF mtime and suppress the next poll event.
     * No-op on platforms without write-behind.
     */
    fun setOnFlushComplete(callback: (suspend (String) -> Unit)?) {}

    /**
     * Registers a callback invoked immediately before each write-behind SAF write begins.
     * Used to call [dev.stapler.stelekit.db.GraphLoader.preMarkFileWrite] so that FileRegistry
     * sets the Long.MAX_VALUE sentinel, closing the race window where a concurrent
     * detectChanges poll emits a spurious event between writeFile() and onFlushed().
     * No-op on platforms without write-behind.
     */
    fun setOnFlushPreWrite(callback: (suspend (String) -> Unit)?) {}

    /**
     * Registers a callback invoked when a write-behind SAF write fails after [setOnFlushPreWrite].
     * Used to call [dev.stapler.stelekit.db.GraphLoader.clearFilePendingWrite] to remove the
     * Long.MAX_VALUE sentinel so the file is not permanently suppressed.
     * No-op on platforms without write-behind.
     */
    fun setOnFlushFailed(callback: (suspend (String) -> Unit)?) {}

    /** Updates the shadow copy after a SAF write. No-op on non-SAF file systems. */
    fun updateShadow(path: String, content: String) { /* no-op */ }

    /** Invalidates the shadow copy for [path], forcing a re-sync on next read. No-op on non-SAF. */
    fun invalidateShadow(path: String) { /* no-op */ }

    /**
     * Invalidates shadow entries that are stale relative to SAF using a single batch
     * mtime query per directory — no SAF file content is read.
     *
     * After this call, stale shadow files are deleted so [readFile] falls through to
     * SAF for those files and returns fresh content. Fresh shadow files are untouched.
     *
     * No-op on non-SAF file systems. Must complete before any parsing reads in
     * [GraphLoader.loadJournalsImmediate] and [GraphLoader.loadDirectory].
     */
    suspend fun invalidateStaleShadow(graphPath: String) { /* no-op */ }

    /**
     * Syncs the shadow copy for [graphPath] from SAF using batch mtime queries,
     * reading and caching stale file content. Slower than [invalidateStaleShadow]
     * but warms the cache for subsequent reads. No-op on non-SAF file systems.
     */
    suspend fun syncShadow(graphPath: String) { /* no-op */ }

    /**
     * Returns a platform-loadable URI string for [relativePath] within [graphRoot], or null to
     * fall back to the default `file://` construction in [SteleKitAssetMapper].
     *
     * Android's SAF-backed graphs override this to return a `content://` document URI so that
     * Coil can load the image via [android.content.ContentResolver] instead of a file path.
     */
    fun buildAssetUri(graphRoot: String, relativePath: String): String? = null

    /**
     * Converts a raw file [path] (absolute or saf://) to a URI string that Coil can load.
     *
     * - `saf://…` paths → `content://` document URI (Android only; overridden in PlatformFileSystem)
     * - Absolute file paths (starting with `/`) → `file://…`
     * - Already-loadable schemes (`file://`, `content://`, `http…`) → returned as-is
     * - Anything else → null (caller falls back to passing the path directly to Coil)
     */
    fun resolveLoadableUri(path: String): String? = when {
        path.startsWith("file://") || path.startsWith("content://") ||
                path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> "file://$path"
        else -> null
    }
}
