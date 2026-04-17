package dev.stapler.stelekit.db

actual class PlatformUtils actual constructor() {
    actual fun getDatabaseDirectory(): String {
        // On Android, use app-specific directory from static context
        val context = DriverFactory.staticContext 
            ?: throw IllegalStateException("DriverFactory must be initialized with a Context before calling PlatformUtils.getDatabaseDirectory().")
        
        return context.filesDir.absolutePath
    }
    
    actual fun getDatabasePath(graphId: String?): String {
        val dir = getDatabaseDirectory()
        
        return if (graphId != null) {
            "$dir/${GRAPH_DB_PREFIX}${graphId}${GRAPH_DB_SUFFIX}"
        } else {
            "$dir/stelekit.db"
        }
    }
    
    actual fun migrateDatabaseFile(oldPath: String, newPath: String): Boolean {
        return try {
            val oldFile = java.io.File(oldPath)
            val newFile = java.io.File(newPath)
            
            if (!oldFile.exists()) return false
            if (newFile.exists()) return true
            
            val renamed = oldFile.renameTo(newFile)
            if (!renamed) {
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
