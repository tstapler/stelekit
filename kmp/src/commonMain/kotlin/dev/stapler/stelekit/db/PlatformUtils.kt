package dev.stapler.stelekit.db

expect class PlatformUtils() {
    fun getDatabaseDirectory(): String
    fun getDatabasePath(graphId: String? = null): String
    fun migrateDatabaseFile(oldPath: String, newPath: String): Boolean
    /**
     * graph-mcp-server (Task 4.1.2a): the command to paste into an MCP host's config, resolved
     * from the running app's own bundled `java.home` runtime — never a bare `"java"`, since
     * jpackage-based `.msi`/`.dmg`/`.deb` installs don't put one on the system `PATH`. Empty on
     * every non-JVM/Desktop platform (MCP is JVM/Desktop-only per requirements.md's scope).
     */
    fun mcpLauncherCommand(graphId: String): String
}

const val GRAPH_DB_PREFIX = "stelekit-graph-"
const val GRAPH_DB_SUFFIX = ".db"
