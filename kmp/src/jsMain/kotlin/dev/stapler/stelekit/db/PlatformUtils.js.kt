package dev.stapler.stelekit.db

actual class PlatformUtils actual constructor() {
    actual fun getDatabaseDirectory(): String {
        // In browser, use localStorage or fallback
        return "/stelekit"
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
        // In browser, no filesystem migration needed
        return true
    }
}
