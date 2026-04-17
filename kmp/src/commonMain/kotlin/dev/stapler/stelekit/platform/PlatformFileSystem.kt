package dev.stapler.stelekit.platform

expect class PlatformFileSystem() : FileSystem {
    override fun getDefaultGraphPath(): String
    override fun expandTilde(path: String): String
    override fun readFile(path: String): String?
    override fun writeFile(path: String, content: String): Boolean
    override fun listFiles(path: String): List<String>
    override fun listDirectories(path: String): List<String>
    override fun fileExists(path: String): Boolean
    override fun directoryExists(path: String): Boolean
    override fun createDirectory(path: String): Boolean
    override fun deleteFile(path: String): Boolean
    override fun pickDirectory(): String?
    override suspend fun pickDirectoryAsync(): String?
    override fun getLastModifiedTime(path: String): Long?
    }