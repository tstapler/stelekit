package dev.stapler.stelekit.db

import java.io.File

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
        } catch (e: Exception) {
            println("Failed to migrate database file: ${e.message}")
            false
        }
    }
}
