package dev.stapler.stelekit.platform

import kotlinx.browser.window

actual class PlatformFileSystem actual constructor() : FileSystem {
    private val maxPathLength = 4096
    private val maxFileSize = 100 * 1024 * 1024
    private val dangerousPatterns = listOf("..", "../", "..\\", "\u0000")
    private val homeDir: String = "/stelekit"

    actual override fun getDefaultGraphPath(): String = homeDir

    actual override fun expandTilde(path: String): String {
        return if (path.startsWith("~")) {
            path.replaceFirst("~", homeDir)
        } else {
            path
        }
    }

    actual override fun readFile(path: String): String? {
        console.log("Reading file: $path")
        return null
    }

    actual override fun writeFile(path: String, content: String): Boolean {
        console.log("Writing file: $path")
        return true
    }

    actual override fun listFiles(path: String): List<String> {
        console.log("Listing files: $path")
        return emptyList()
    }

    actual override fun listDirectories(path: String): List<String> {
        console.log("Listing directories: $path")
        return emptyList()
    }

    actual override fun fileExists(path: String): Boolean {
        console.log("Checking file exists: $path")
        return false
    }

    actual override fun directoryExists(path: String): Boolean {
        console.log("Checking directory exists: $path")
        return false
    }

    actual override fun createDirectory(path: String): Boolean {
        console.log("Creating directory: $path")
        return true
    }

    actual override fun deleteFile(path: String): Boolean {
        console.log("Deleting file: $path")
        return true
    }

    actual override fun pickDirectory(): String? {
        console.log("Picking directory not supported in JS yet")
        return null
    }

    actual override suspend fun pickDirectoryAsync(): String? = pickDirectory()

    actual override fun getLastModifiedTime(path: String): Long? {
        console.log("Getting last modified time not supported in JS: $path")
        return null
    }
}
