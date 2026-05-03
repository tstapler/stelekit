// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.migration.ChangeApplier
import dev.stapler.stelekit.migration.ChangelogRepository
import dev.stapler.stelekit.migration.DslEvaluator
import dev.stapler.stelekit.migration.InterruptedMigrationException
import dev.stapler.stelekit.migration.MigrationRegistry
import dev.stapler.stelekit.migration.MigrationRunner
import dev.stapler.stelekit.migration.MigrationTamperedError
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Manages multiple graphs and their respective database connections.
 * Replaces the Repositories singleton with per-graph RepositorySets.
 */
class GraphManager(
    private val platformSettings: Settings,
    private val driverFactory: DriverFactory,
    private val fileSystem: FileSystem,
    val defaultBackend: GraphBackend = GraphBackend.SQLDELIGHT,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = Logger("GraphManager")
    private val json = Json { ignoreUnknownKeys = true }
    private val _graphRegistry = MutableStateFlow(GraphRegistry())
    val graphRegistry: StateFlow<GraphRegistry> = _graphRegistry.asStateFlow()
    
    private val _activeRepositorySet = MutableStateFlow<RepositorySet?>(null)
    val activeRepositorySet: StateFlow<RepositorySet?> = _activeRepositorySet.asStateFlow()
    
    // Track current factory for lifecycle management
    private var currentFactory: dev.stapler.stelekit.repository.RepositoryFactory? = null

    // Deferred that resolves when the one-shot UUID migration for the active graph completes.
    // Callers can await this before loading graph content to ensure UUIDs are stable.
    private var _pendingMigration: Deferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

    // Track active coroutines for cleanup during graph switches
    private val activeGraphJobs = mutableMapOf<String, CoroutineScope>()
    
    init {
        loadRegistry()
    }
    
    private fun loadRegistry() {
        val registryJson = platformSettings.getString("graph_registry", "")
        if (registryJson.isNotEmpty()) {
            try {
                val registry = json.decodeFromString<GraphRegistry>(registryJson)
                // Refresh display names in case paths were stored before displayNameForPath was fixed
                val refreshed = registry.copy(
                    graphs = registry.graphs.map { graph ->
                        val freshName = fileSystem.displayNameForPath(graph.path)
                        if (freshName != graph.displayName) graph.copy(displayName = freshName) else graph
                    }
                )
                _graphRegistry.value = refreshed
                if (refreshed.graphs != registry.graphs) saveRegistry()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Corrupted registry - start fresh
                _graphRegistry.value = GraphRegistry()
                saveRegistry()
            }
        } else {
            // No registry exists - check for migration from single-graph setup
            migrateFromSingleGraph()
        }
    }
    
    /**
     * Migrate from the old single-graph setup to multi-graph.
     * - Checks for existing `lastGraphPath` setting
     * - Renames `logseq.db` to `logseq-graph-{hash}.db`
     * - Creates GraphRegistry with the migrated graph as active
     */
    private fun migrateFromSingleGraph() {
        val lastGraphPath = platformSettings.getString("lastGraphPath", "")
        if (lastGraphPath.isEmpty()) {
            // No previous graph - fresh install
            return
        }
        
        try {
            val expandedPath = fileSystem.expandTilde(lastGraphPath)
            val graphId = graphIdFromPath(expandedPath)
            
            // Get the database directory (platform-specific)
            val dbDir = driverFactory.getDatabaseDirectory()
            val oldDbPath = "$dbDir/logseq.db"
            val newDbPath = driverFactory.getDatabaseUrl(graphId).substringAfter("jdbc:sqlite:")
            
            // Check if old database exists and rename it
            if (fileSystem.fileExists(oldDbPath)) {
                // Try to rename the file (this is platform-specific)
                val renamed = migrateDatabaseFile(oldDbPath, newDbPath)
                if (renamed) {
                    // Also try to migrate WAL and SHM files if they exist
                    migrateWalShmFiles(dbDir, graphId)
                }
            }
            
            // Create graph registry with the migrated graph
            val displayName = fileSystem.displayNameForPath(lastGraphPath)
            
            val graphInfo = GraphInfo(
                id = graphId,
                path = expandedPath,
                displayName = displayName,
                addedAt = Clock.System.now().toEpochMilliseconds()
            )
            
            val registry = GraphRegistry(
                activeGraphId = graphId,
                graphs = listOf(graphInfo)
            )
            _graphRegistry.value = registry
            saveRegistry()
            
            println("Migration complete: graph '$displayName' migrated to ID $graphId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Migration failed: ${e.message}")
            // Start fresh if migration fails
            _graphRegistry.value = GraphRegistry()
            saveRegistry()
        }
    }
    
    /**
     * Migrate the database file from old path to new path.
     * Returns true if successful.
     */
    private fun migrateDatabaseFile(oldPath: String, newPath: String): Boolean {
        return try {
            fileSystem.renameFile(oldPath, newPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to migrate database file: ${e.message}")
            false
        }
    }

    /**
     * Migrate WAL and SHM files (SQLite write-ahead log files)
     */
    private fun migrateWalShmFiles(dbDir: String, graphId: String) {
        try {
            val newDbPath = driverFactory.getDatabaseUrl(graphId).substringAfter("jdbc:sqlite:")
            fileSystem.renameFile("$dbDir/logseq.db-wal", "$newDbPath-wal")
            fileSystem.renameFile("$dbDir/logseq.db-shm", "$newDbPath-shm")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Non-critical - WAL/SHM files may not exist
        }
    }
    
    private fun saveRegistry() {
        val registryJson = json.encodeToString(_graphRegistry.value)
        platformSettings.putString("graph_registry", registryJson)
    }
    
    fun graphIdFromPath(path: String): String = 
        ContentHasher.sha256(path).take(16)
    
    fun addGraph(path: String): String {
        // Use expanded path for consistent ID generation
        val expandedPath = fileSystem.expandTilde(path)
        val graphId = graphIdFromPath(expandedPath)
        val displayName = fileSystem.displayNameForPath(expandedPath)

        // Warn if the SQLite database files are not gitignored.
        // The .db, .db-wal, and .db-shm files must never be committed to git
        // as they are binary and will cause unresolvable merge conflicts.
        checkGitignoreForDatabase(expandedPath)

        val info = GraphInfo(
            id = graphId,
            path = expandedPath,
            displayName = displayName,
            addedAt = Clock.System.now().toEpochMilliseconds()
        )
        
        val registry = _graphRegistry.value
        if (!registry.graphIds.contains(graphId)) {
            val updated = registry.copy(
                graphs = registry.graphs + info
            )
            _graphRegistry.value = updated
            saveRegistry()
        }
        
        return graphId
    }
    
    fun removeGraph(id: String): Boolean {
        // Cancel any active coroutines for this graph
        activeGraphJobs.remove(id)?.cancel()
        
        val registry = _graphRegistry.value
        if (!registry.graphIds.contains(id)) return false
        
        // Don't allow removing active graph
        if (registry.activeGraphId == id) return false
        
        val updated = registry.copy(
            graphs = registry.graphs.filter { it.id != id }
        )
        _graphRegistry.value = updated
        saveRegistry()
        return true
    }
    
    fun renameGraph(id: String, newName: String): Boolean {
        val registry = _graphRegistry.value
        val graphIndex = registry.graphs.indexOfFirst { it.id == id }
        if (graphIndex == -1) return false
        
        val updatedGraphs = registry.graphs.toMutableList()
        updatedGraphs[graphIndex] = updatedGraphs[graphIndex].copy(displayName = newName)
        
        val updated = registry.copy(graphs = updatedGraphs)
        _graphRegistry.value = updated
        saveRegistry()
        return true
    }
    
    /**
     * Switch to a different graph.
     * Closes the current database connection and opens a new one for the target graph.
     */
    fun switchGraph(id: String) {
        val registry = _graphRegistry.value
        val graphInfo = registry.graphs.firstOrNull { it.id == id }
        if (graphInfo == null) return
        
        // Cancel any existing coroutines for the previous graph
        val currentGraphId = registry.activeGraphId
        currentGraphId?.let { activeGraphJobs.remove(it)?.cancel() }
        
        // Close current factory and its database connection
        currentFactory?.close()
        currentFactory = null
        _activeRepositorySet.value = null
        
        // Create a new scope for this graph's operations first so the actor can use it
        val graphScope = CoroutineScope(coroutineScope.coroutineContext)
        activeGraphJobs[id] = graphScope

        // Create new database for this graph (platform-agnostic URL)
        val dbUrl = driverFactory.getDatabaseUrl(id)
        val factory = dev.stapler.stelekit.repository.RepositoryFactoryImpl(driverFactory, dbUrl)
        currentFactory = factory
        val deviceInfo = try { dev.stapler.stelekit.performance.getDeviceInfo() } catch (_: Exception) { null }
        val repoSet = factory.createRepositorySet(
            backend = defaultBackend,
            scope = graphScope,
            fileSystem = fileSystem,
            appVersion = deviceInfo?.appVersion ?: "unknown",
            platform = deviceInfo?.platform ?: "unknown",
        )
        _activeRepositorySet.value = repoSet

        // Run one-shot UUID migration before graph content is loaded.
        // The migration goes through the writeActor so it is serialised ahead of any
        // loadGraph writes. We also expose the Deferred so callers can await completion.
        val writeActor = repoSet.writeActor
        val deferred = CompletableDeferred<Unit>()
        _pendingMigration = deferred
        if (writeActor != null && defaultBackend == GraphBackend.SQLDELIGHT) {
            val db = factory.steleDatabase()
            graphScope.launch {
                try {
                    UuidMigration(writeActor).runIfNeeded(db)
                    try {
                        MigrationRunner(
                            registry = MigrationRegistry,
                            changelogRepo = ChangelogRepository(db),
                            evaluator = DslEvaluator(repoSet),
                            applier = ChangeApplier(writeActor, opLogger = null),
                            flusher = null,
                        ).runPending(id, repoSet, graphInfo.path)
                    } catch (e: InterruptedMigrationException) {
                        logger.error("MigrationRunner: interrupted migration detected for graph $id", e)
                    } catch (e: MigrationTamperedError) {
                        logger.error("MigrationRunner: tampered migration detected for graph $id", e)
                    }
                } finally {
                    deferred.complete(Unit)
                }
            }
        } else {
            deferred.complete(Unit)
        }

        // Update active graph
        val updatedRegistry = registry.copy(activeGraphId = id)
        _graphRegistry.value = updatedRegistry
        saveRegistry()
    }

    /**
     * Suspends until the one-shot UUID migration for the currently active graph has completed.
     * Call this before loading graph content to ensure block UUIDs are stable.
     */
    suspend fun awaitPendingMigration() {
        _pendingMigration.await()
    }
    
    fun getGraphInfo(id: String): GraphInfo? {
        return _graphRegistry.value.graphs.firstOrNull { it.id == id }
    }
    
    fun getActiveGraphId(): String? {
        return _graphRegistry.value.activeGraphId
    }
    
    fun getActiveGraphInfo(): GraphInfo? {
        val activeId = _graphRegistry.value.activeGraphId ?: return null
        return getGraphInfo(activeId)
    }
    
    fun getGraphIds(): Set<String> = 
        _graphRegistry.value.graphs.map { it.id }.toSet()
    
    fun getActiveRepositorySet(): RepositorySet? = _activeRepositorySet.value
    
    private fun checkGitignoreForDatabase(graphPath: String) {
        val gitignorePath = "$graphPath/.gitignore"
        if (!fileSystem.fileExists(gitignorePath)) {
            println("WARNING: No .gitignore found at $graphPath — SQLite .db files may be accidentally committed to git. Add '*.db' to .gitignore.")
            return
        }
        val content = try {
            fileSystem.readFile(gitignorePath) ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (!content.contains("*.db") && !content.contains(".db")) {
            println("WARNING: $gitignorePath does not contain '*.db' — SQLite database files may be accidentally committed to git.")
        }
    }

    /**
     * Clean up all resources when shutting down
     */
    fun shutdown() {
        // Cancel all graph-specific coroutines
        activeGraphJobs.values.forEach { it.cancel() }
        activeGraphJobs.clear()
        
        // Close database connection
        currentFactory?.close()
        
        // Clear repository set
        _activeRepositorySet.value = null
        currentFactory = null
    }
}
