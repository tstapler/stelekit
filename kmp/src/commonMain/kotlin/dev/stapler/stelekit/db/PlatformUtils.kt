package dev.stapler.stelekit.db

expect class PlatformUtils() {
    fun getDatabaseDirectory(): String
    fun getDatabasePath(graphId: String? = null): String
    fun migrateDatabaseFile(oldPath: String, newPath: String): Boolean
}

const val GRAPH_DB_PREFIX = "stelekit-graph-"
const val GRAPH_DB_SUFFIX = ".db"
