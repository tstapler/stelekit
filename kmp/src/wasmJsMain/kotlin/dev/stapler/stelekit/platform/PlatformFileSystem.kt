package dev.stapler.stelekit.platform

actual class PlatformFileSystem actual constructor() : FileSystem {
    private val homeDir: String = "/stelekit"

    actual override fun getDefaultGraphPath(): String = homeDir

    actual override fun expandTilde(path: String): String =
        if (path.startsWith("~")) path.replaceFirst("~", homeDir) else path

    actual override fun readFile(path: String): String? = null
    actual override fun writeFile(path: String, content: String): Boolean = true
    actual override fun listFiles(path: String): List<String> = emptyList()
    actual override fun listDirectories(path: String): List<String> = emptyList()
    actual override fun fileExists(path: String): Boolean = false
    actual override fun directoryExists(path: String): Boolean = false
    actual override fun createDirectory(path: String): Boolean = true
    actual override fun deleteFile(path: String): Boolean = true
    actual override fun pickDirectory(): String? = null
    actual override suspend fun pickDirectoryAsync(): String? = null
    actual override fun getLastModifiedTime(path: String): Long? = null
}
