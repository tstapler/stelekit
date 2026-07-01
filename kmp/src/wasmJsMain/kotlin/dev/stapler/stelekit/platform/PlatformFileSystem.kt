package dev.stapler.stelekit.platform

import dev.stapler.stelekit.sync.WasmSectionSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

actual class PlatformFileSystem actual constructor() : FileSystem {
    private val homeDir = "/stelekit"
    private val cache = mutableMapOf<String, String>()
    private val blobUrlCache = mutableMapOf<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun preload(graphPath: String) {
        try {
            loadOpfsDirectory(graphPath)
        } catch (e: Throwable) {
            println("[SteleKit] OPFS preload failed, starting with empty graph: ${e.message}")
        }
    }

    private suspend fun loadOpfsDirectory(graphPath: String) {
        val root = getOpfsRoot()
        val parts = graphPath.removePrefix("/").split("/")
        var dir: JsAny = root
        for (part in parts) {
            dir = try {
                getDirectoryHandle(dir, part, false)
            } catch (e: Throwable) {
                return
            }
        }
        loadFilesRecursive(dir, graphPath)
    }

    private suspend fun loadFilesRecursive(dirHandle: JsAny, currentPath: String) {
        val entries = listOpfsEntries(dirHandle)
        for (entry in entries) {
            val name = getEntryName(entry)
            val path = "$currentPath/$name"
            if (isFileEntry(entry)) {
                if (isImageFile(name)) {
                    val url = readOpfsFileAsObjectUrl(entry)
                    if (url != null) blobUrlCache[path] = url
                } else {
                    val content = readOpfsFile(entry)
                    if (content != null) cache[path] = content
                }
            } else if (isDirectoryEntry(entry)) {
                loadFilesRecursive(entry, path)
            }
        }
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif", "heic")
    }

    actual override fun getDefaultGraphPath(): String = homeDir
    actual override fun expandTilde(path: String): String =
        if (path.startsWith("~")) path.replaceFirst("~", homeDir) else path

    actual override fun readFile(path: String): String? = cache[path]

    override suspend fun readFileSuspend(path: String): String? {
        cache[path]?.let { return it }
        val owner = githubOwner.ifEmpty { return null }
        val repo = githubRepo.ifEmpty { return null }
        val branch = githubBranch
        val token = githubToken
        // Convert absolute OPFS path back to a repo-relative path.
        // OPFS paths look like /stelekit/<graphId>/<repo-relative-path>
        val repoRelative = path.removePrefix("/stelekit/").substringAfter("/")
        val rawUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$repoRelative"
        val content = WasmSectionSyncService.githubFetch(rawUrl, token) ?: return null
        cache[path] = content
        scope.launch { opfsWriteFile(path, content) }
        return content
    }
    actual override fun fileExists(path: String): Boolean = cache.containsKey(path) || blobUrlCache.containsKey(path)
    actual override fun listFiles(path: String): List<String> =
        cache.keys
            .filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
            .map { it.removePrefix("$path/") }
    actual override fun listDirectories(path: String): List<String> =
        cache.keys
            .filter { it.startsWith("$path/") }
            .map { it.removePrefix("$path/").substringBefore('/') }
            .filter { it.isNotEmpty() && cache.keys.any { k -> k.startsWith("$path/$it/") } }
            .distinct()

    // ponytail: single-threaded JS — pick and write always sequential, no race
    private var pendingDownloadMimeType: String = "application/octet-stream"

    actual override fun writeFile(path: String, content: String): Boolean {
        if (path.startsWith(DOWNLOAD_PREFIX)) {
            triggerBrowserTextDownload(path.removePrefix(DOWNLOAD_PREFIX), content, pendingDownloadMimeType)
            return true
        }
        cache[path] = content
        scope.launch { opfsWriteFile(path, content) }
        return true
    }

    override suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String): String? {
        pendingDownloadMimeType = mimeType
        return "$DOWNLOAD_PREFIX$suggestedName"
    }

    actual override fun directoryExists(path: String): Boolean =
        path == homeDir || cache.keys.any { it.startsWith("$path/") }
    actual override fun createDirectory(path: String): Boolean = true
    actual override fun deleteFile(path: String): Boolean {
        cache.remove(path)
        scope.launch { opfsDeleteFile(path) }
        return true
    }
    actual override fun pickDirectory(): String? = null
    override val supportsNativeDirectoryPicker: Boolean = false
    actual override suspend fun pickDirectoryAsync(): String? = null
    override suspend fun pickFileAsync(): String? = null
    actual override fun getLastModifiedTime(path: String): Long? = null
    override fun registerBlobUrl(path: String, url: String) { blobUrlCache[path] = url }
    override fun resolveAssetUri(graphRoot: String, relativePath: String): String? =
        blobUrlCache["${graphRoot.trimEnd('/')}/$relativePath"]

    companion object {
        private const val DOWNLOAD_PREFIX = "/_wasm_dl_/"
        var githubOwner: String = ""
        var githubRepo: String = ""
        var githubBranch: String = "main"
        var githubToken: String? = null
    }
}

private fun triggerBrowserTextDownload(filename: String, content: String, mimeType: String): Unit =
    js("""(function() {
        var blob = new Blob([content], { type: mimeType });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url; a.download = filename;
        document.body.appendChild(a); a.click(); document.body.removeChild(a);
        setTimeout(function() { URL.revokeObjectURL(url); }, 1000);
    })()""")
