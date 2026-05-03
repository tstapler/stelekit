package dev.stapler.stelekit.platform

import java.io.File
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Desktop JVM implementation of file system operations.
 * Provides secure file reading, writing, and directory operations.
 * This class is only available on JVM targets.
 */
class DesktopFileSystem {
    companion object {
        const val MAX_PATH_LENGTH = 4096
        const val MAX_FILE_SIZE = 100 * 1024 * 1024 // 100MB
        private val DANGEROUS_PATTERNS = listOf("..", "../", "..\\", "\u0000")
    }

    val homeDir: String by lazy { System.getProperty("user.home") }

    fun getDefaultGraphPath(): String = "$homeDir/Documents/stelekit"

    fun expandTilde(path: String): String {
        return if (path.startsWith("~")) {
            path.replaceFirst("~", homeDir)
        } else {
            path
        }
    }

    fun readFile(path: String): String? {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validatePath(expandedPath)
            val file = File(validatedPath)
            if (!file.exists() || !file.isFile) return null
            if (file.length() > MAX_FILE_SIZE) return null
            Files.readString(Paths.get(validatedPath))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun writeFile(path: String, content: String): Boolean {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validatePath(expandedPath)
            if (content.length > MAX_FILE_SIZE) return false
            val pathObj = Paths.get(validatedPath)
            val parentDir = pathObj.parent
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir)
            }
            Files.writeString(
                pathObj, content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun listFiles(path: String): List<String> {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validatePath(expandedPath)
            val directory = File(validatedPath)
            if (!directory.exists() || !directory.isDirectory) return emptyList()
            directory.listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun listDirectories(path: String): List<String> {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validatePath(expandedPath)
            val directory = File(validatedPath)
            if (!directory.exists() || !directory.isDirectory) return emptyList()
            directory.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fileExists(path: String): Boolean {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validatePath(expandedPath)
            val file = File(validatedPath)
            file.exists() && file.isFile
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun directoryExists(path: String): Boolean {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validatePath(expandedPath)
            val file = File(validatedPath)
            file.exists() && file.isDirectory
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun createDirectory(path: String): Boolean {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validatePath(expandedPath)
            val pathObj = Paths.get(validatedPath)
            val parentDir = pathObj.parent
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir)
            }
            if (Files.exists(pathObj)) {
                return Files.isDirectory(pathObj)
            }
            Files.createDirectory(pathObj)
            Files.exists(pathObj)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun deleteFile(path: String): Boolean {
        return try {
            val expandedPath = expandTilde(path)
            val validatedPath = validatePath(expandedPath)
            val file = File(validatedPath)
            if (!file.exists()) return true
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun validatePath(path: String): String {
        require(path.length <= MAX_PATH_LENGTH) { "Path exceeds maximum length" }
        require(!path.contains('\u0000')) { "Path contains null bytes" }
        DANGEROUS_PATTERNS.forEach { pattern ->
            require(!path.contains(pattern)) { "Path contains dangerous pattern: $pattern" }
        }
        val normalized = path.replace(Regex("[/\\\\]+"), "/")
        val expandedPath = expandTilde(normalized)
        val absolutePath = Paths.get(expandedPath).toAbsolutePath().normalize()
        val homePath = Paths.get(homeDir).toAbsolutePath().normalize()
        require(absolutePath.startsWith(homePath)) { "Path must be within user's home directory" }
        return absolutePath.toString()
    }
}
