package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.platform.FileSystem

/**
 * Minimal in-memory [FileSystem] for sidecar and image storage tests.
 *
 * Stores files and directories entirely in memory. Open (non-private) so it can be used
 * from both commonTest and jvmTest.
 */
open class FakeFileSystem : FileSystem {
    private val textFiles = mutableMapOf<String, String>()
    private val binaryFiles = mutableMapOf<String, ByteArray>()
    private val dirs = mutableSetOf<String>()

    override fun getDefaultGraphPath(): String = "/graph"
    override fun expandTilde(path: String): String = path
    override fun readFile(path: String): String? = textFiles[path]
    override fun writeFile(path: String, content: String): Boolean {
        textFiles[path] = content
        return true
    }
    override fun listFiles(path: String): List<String> {
        val prefix = if (path.endsWith("/")) path else "$path/"
        return (textFiles.keys + binaryFiles.keys)
            .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains("/") }
            .map { it.removePrefix(prefix) }
            .distinct()
    }
    override fun listDirectories(path: String): List<String> = emptyList()
    override fun fileExists(path: String): Boolean = path in textFiles || path in binaryFiles
    override fun directoryExists(path: String): Boolean =
        path in dirs || (textFiles.keys + binaryFiles.keys).any { it.startsWith("$path/") }
    override fun createDirectory(path: String): Boolean { dirs.add(path); return true }
    override fun deleteFile(path: String): Boolean {
        textFiles.remove(path)
        binaryFiles.remove(path)
        return true
    }
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
    override fun readFileBytes(path: String): ByteArray? =
        binaryFiles[path] ?: textFiles[path]?.encodeToByteArray()
    override fun writeFileBytes(path: String, data: ByteArray): Boolean {
        binaryFiles[path] = data
        return true
    }
}
