package dev.stapler.stelekit.platform

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Base class for JVM file system implementations.
 */
abstract class JvmFileSystemBase {
    companion object {
        const val MAX_PATH_LENGTH = 4096
        const val MAX_FILE_SIZE = 100 * 1024 * 1024 // 100MB
    }

    val homeDir: String by lazy { System.getProperty("user.home") ?: "" }
    
    // Whitelist to track allowed base directories. Using ConcurrentHashMap to ensure thread safety.
    private val whitelist = ConcurrentHashMap.newKeySet<String>().apply {
        // Initial whitelist includes home directory
        val home = System.getProperty("user.home")
        if (home != null) {
            add(Paths.get(home).toAbsolutePath().normalize().toString())
        }
    }

    open fun getDefaultGraphPath(): String = "$homeDir/Documents/stelekit"

    open fun expandTilde(path: String): String {
        return if (path.startsWith("~")) {
            path.replaceFirst("~", homeDir)
        } else {
            path
        }
    }

    /**
     * Registers a path as an authorized graph root.
     * Use this when the application starts with a specific graph path
     * or when the user explicitly selects a new graph via a picker.
     */
    fun registerGraphRoot(path: String) {
        val expandedPath = expandTilde(path)
        val absolutePathStr = Paths.get(expandedPath).toAbsolutePath().normalize().toString()
        whitelist.add(absolutePathStr)
    }

    protected fun validatePath(path: String, addToWhitelist: Boolean = false): String {
        require(path.length <= MAX_PATH_LENGTH) { "Path exceeds maximum length" }
        require(!path.contains('\u0000')) { "Path contains null bytes" }
        
        val expandedPath = expandTilde(path)
        val absolutePath = Paths.get(expandedPath).toAbsolutePath().normalize()
        val absolutePathStr = absolutePath.toString()
        
        if (addToWhitelist) {
            whitelist.add(absolutePathStr)
        }
        
        // Ensure path starts with one of the whitelisted paths
        val isAllowed = whitelist.any { 
            absolutePathStr == it || absolutePathStr.startsWith(it + File.separator)
        }
        
        require(isAllowed) { "Path traversal or unauthorized access attempt: $absolutePathStr" }
        
        return absolutePathStr
    }

    open fun readFile(path: String): String? {
        return try {
            val validatedPath = validatePath(path)
            val file = File(validatedPath)
            if (!file.exists() || !file.isFile) return null
            if (file.length() > MAX_FILE_SIZE) return null
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    open fun writeFile(path: String, content: String): Boolean {
        return try {
            // Security: We NO LONGER auto-whitelist here. 
            // The root must be whitelisted via registerGraphRoot or pickDirectory.
            val validatedPath = validatePath(path, addToWhitelist = false)
            if (content.length > MAX_FILE_SIZE) return false
            val file = File(validatedPath)
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs()
            }
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    open fun listFiles(path: String): List<String> {
        return try {
            val validatedPath = validatePath(path)
            val directory = File(validatedPath)
            if (!directory.exists() || !directory.isDirectory) return emptyList()
            directory.listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    open fun listDirectories(path: String): List<String> {
        return try {
            val validatedPath = validatePath(path)
            val directory = File(validatedPath)
            if (!directory.exists() || !directory.isDirectory) return emptyList()
            directory.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    open fun fileExists(path: String): Boolean {
        return try {
            val validatedPath = validatePath(path)
            val file = File(validatedPath)
            file.exists() && file.isFile
        } catch (e: Exception) {
            false
        }
    }

    open fun directoryExists(path: String): Boolean {
        return try {
            val validatedPath = validatePath(path)
            val file = File(validatedPath)
            file.exists() && file.isDirectory
        } catch (e: Exception) {
            false
        }
    }

    open fun createDirectory(path: String): Boolean {
        return try {
            // Security: We NO LONGER auto-whitelist here.
            val validatedPath = validatePath(path, addToWhitelist = false)
            val file = File(validatedPath)
            if (file.exists()) {
                return file.isDirectory
            }
            file.mkdirs()
            file.exists()
        } catch (e: Exception) {
            false
        }
    }

    open fun deleteFile(path: String): Boolean {
        return try {
            val validatedPath = validatePath(path)
            val file = File(validatedPath)
            if (!file.exists()) return true
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (e: Exception) {
            false
        }
    }

    open fun getLastModifiedTime(path: String): Long? {
        return try {
            val validatedPath = validatePath(path)
            val file = File(validatedPath)
            if (file.exists() && (file.isFile || file.isDirectory)) {
                file.lastModified()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    open fun listFilesWithModTimes(path: String): List<Pair<String, Long>> {
        return try {
            val validatedPath = validatePath(path)
            val directory = File(validatedPath)
            if (!directory.exists() || !directory.isDirectory) return emptyList()
            directory.listFiles()
                ?.filter { it.isFile }
                ?.map { file -> file.name to file.lastModified() }
                ?.sortedBy { it.first }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    open fun renameFile(from: String, to: String): Boolean {
        return try {
            val oldFile = File(from)
            val newFile = File(to)
            if (!oldFile.exists()) return false
            if (newFile.exists()) return true
            val renamed = oldFile.renameTo(newFile)
            if (!renamed) {
                oldFile.copyTo(newFile)
                oldFile.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
