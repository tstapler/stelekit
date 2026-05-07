package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

actual class PlatformFileSystem actual constructor() : FileSystem {
    private val homeDir = "/stelekit"
    private val cache = mutableMapOf<String, String>()
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
                val content = readOpfsFile(entry)
                if (content != null) cache[path] = content
            } else if (isDirectoryEntry(entry)) {
                loadFilesRecursive(entry, path)
            }
        }
    }

    actual override fun getDefaultGraphPath(): String = homeDir
    actual override fun expandTilde(path: String): String =
        if (path.startsWith("~")) path.replaceFirst("~", homeDir) else path

    actual override fun readFile(path: String): String? = cache[path]
    actual override fun fileExists(path: String): Boolean = cache.containsKey(path)
    actual override fun listFiles(path: String): List<String> =
        cache.keys.filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
    actual override fun listDirectories(path: String): List<String> =
        cache.keys
            .filter { it.startsWith("$path/") }
            .map { it.removePrefix("$path/").substringBefore('/') }
            .filter { it.isNotEmpty() && cache.keys.any { k -> k.startsWith("$path/$it/") } }
            .distinct()
            .map { "$path/$it" }

    actual override fun writeFile(path: String, content: String): Boolean {
        cache[path] = content
        scope.launch { opfsWriteFile(path, content) }
        return true
    }

    actual override fun directoryExists(path: String): Boolean = true
    actual override fun createDirectory(path: String): Boolean = true
    actual override fun deleteFile(path: String): Boolean {
        cache.remove(path)
        scope.launch { opfsDeleteFile(path) }
        return true
    }
    actual override fun pickDirectory(): String? = null
    actual override suspend fun pickDirectoryAsync(): String? = null
    actual override fun getLastModifiedTime(path: String): Long? = null
}
