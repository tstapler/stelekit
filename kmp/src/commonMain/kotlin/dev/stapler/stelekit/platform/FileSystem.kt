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
}
