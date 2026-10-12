package dev.stapler.stelekit.db

import java.io.File
import kotlinx.coroutines.CancellationException

actual class PlatformUtils actual constructor() {
    actual fun getDatabaseDirectory(): String {
        val os = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")
        
        return when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: "$userHome\\AppData\\Roaming"
                "$appData\\SteleKit"
            }
            os.contains("mac") -> {
                "$userHome/Library/Application Support/SteleKit"
            }
            else -> { // Linux and others
                val xdgData = System.getenv("XDG_DATA_HOME") ?: "$userHome/.local/share"
                "$xdgData/stelekit"
            }
        }
    }
    
    actual fun getDatabasePath(graphId: String?): String {
        val dir = getDatabaseDirectory()
        File(dir).mkdirs()
        
        return if (graphId != null) {
            "$dir/${GRAPH_DB_PREFIX}${graphId}${GRAPH_DB_SUFFIX}"
        } else {
            "$dir/stelekit.db"
        }
    }
    
    actual fun migrateDatabaseFile(oldPath: String, newPath: String): Boolean {
        return try {
            val oldFile = File(oldPath)
            val newFile = File(newPath)
            
            if (!oldFile.exists()) return false
            if (newFile.exists()) return true // Already migrated
            
            // Try atomic rename first
            val renamed = oldFile.renameTo(newFile)
            if (!renamed) {
                // Fall back to copy-and-delete
                oldFile.copyTo(newFile, overwrite = true)
                oldFile.delete()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to migrate database file: ${e.message}")
            false
        }
    }

    /**
     * Runs the launcher shim (`McpLauncherMain`) with the running app's own runtime and
     * classpath: `java.home` is the bundled jlink image in a jpackage install, so this works
     * where a bare `java` would not be on `PATH`.
     */
    actual fun mcpLauncherCommand(graphId: String): String {
        val javaHome = System.getProperty("java.home") ?: return ""
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val java = listOf(javaHome, "bin", exe).joinToString(File.separator)
        val classpath = System.getProperty("java.class.path").orEmpty()
        return "${quote(java)} -cp ${quote(classpath)} dev.stapler.stelekit.mcp.McpLauncherMainKt --graph-id $graphId"
    }

    private fun quote(s: String) = if (s.any { it.isWhitespace() }) "\"$s\"" else s
}
