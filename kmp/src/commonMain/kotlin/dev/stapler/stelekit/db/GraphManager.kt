// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.migration.ChangeApplier
import dev.stapler.stelekit.migration.ChangelogRepository
import dev.stapler.stelekit.migration.ConcurrentMigrationRunException
import dev.stapler.stelekit.migration.DslEvaluator
import dev.stapler.stelekit.migration.InterruptedMigrationException
import dev.stapler.stelekit.migration.MigrationRegistry
import dev.stapler.stelekit.migration.MigrationRunner
import dev.stapler.stelekit.migration.MigrationTamperedError
import dev.stapler.stelekit.model.DEMO_GRAPH_ID
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.vault.VaultManager
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
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
    /** Awaited before any driver is created — lets the Application flush write-behind pages
     *  on a background thread while GraphManager initialization proceeds. */
    private val preFlightJob: Deferred<Unit>? = null,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = Logger("GraphManager")
    private val json = Json { ignoreUnknownKeys = true }
    private val _graphRegistry = MutableStateFlow(GraphRegistry())
    val graphRegistry: StateFlow<GraphRegistry> = _graphRegistry.asStateFlow()
    
    private val _activeRepositorySet = MutableStateFlow<RepositorySet?>(null)
    val activeRepositorySet: StateFlow<RepositorySet?> = _activeRepositorySet.asStateFlow()
    
    // Track current factory for lifecycle management.
    // Written from a background coroutine (switchGraph's IO launch) and read on the Compose
    // main thread in createGitConfigRepository(); @Volatile is required for JVM visibility.
    @kotlin.concurrent.Volatile
    private var currentFactory: dev.stapler.stelekit.repository.RepositoryFactory? = null

    // Deferred that resolves when the one-shot UUID migration for the active graph completes.
    // Callers can await this before loading graph content to ensure UUIDs are stable.
    @kotlin.concurrent.Volatile
    private var _pendingMigration: Deferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

    // Track active coroutines for cleanup during graph switches
    private val activeGraphJobs = mutableMapOf<GraphId, CoroutineScope>()

    // Git sync service for the currently active graph.
    // Set externally via registerGitSyncService() after GraphLoader/GraphWriter are wired.
    private val _activeGitSyncService = MutableStateFlow<dev.stapler.stelekit.git.GitSyncService?>(null)
    val activeGitSyncService: StateFlow<dev.stapler.stelekit.git.GitSyncService?> = _activeGitSyncService.asStateFlow()

    // Vault credential store for the currently active graph (non-null when paranoid mode is on).
    // Populated by wiring in App.kt after vault unlock.
    private val _activeVaultCredentialStore = kotlinx.coroutines.flow.MutableStateFlow<dev.stapler.stelekit.git.VaultCredentialStore?>(null)
    val activeVaultCredentialStore: kotlinx.coroutines.flow.StateFlow<dev.stapler.stelekit.git.VaultCredentialStore?> = _activeVaultCredentialStore.asStateFlow()

    init {
        loadRegistry()
        val activeId = _graphRegistry.value.activeGraphId
        if (activeId != null) {
            val graphInfo = _graphRegistry.value.graphs.firstOrNull { it.id == activeId }
            // Paranoid-mode (encrypted vault) graphs must NOT be auto-restored on startup.
            // The user must unlock the vault through the main app UI before the DB is exposed.
            if (graphInfo?.isParanoidMode != true) {
                switchGraph(activeId)
            }
        }
    }
    
    private fun loadRegistry() {
        val registryJson = platformSettings.getString("graph_registry", "")
        if (registryJson.isNotEmpty()) {
            try {
                val registry = json.decodeFromString<GraphRegistry>(registryJson)
                // Strip any stale demo entries that were persisted before the isDemo guard
                val demoStripped = registry.graphs.filter { !it.isDemo && it.path != "/demo" }
                val cleanedRegistry = if (demoStripped.size < registry.graphs.size) {
                    val strippedCount = registry.graphs.size - demoStripped.size
                    logger.info("loadRegistry: stripped $strippedCount demo entries")
                    val strippedIds = registry.graphs.filter { it.isDemo || it.path == "/demo" }
                                                  .map { it.id }.toSet()
                val adjustedActiveId = if (registry.activeGraphId in strippedIds) null
                                       else registry.activeGraphId
                    if (demoStripped.isEmpty()) {
                        platformSettings.putBoolean("onboardingCompleted", false)
                    }
                    registry.copy(graphs = demoStripped, activeGraphId = adjustedActiveId)
                } else {
                    registry
                }
                // Refresh display names in case paths were stored before displayNameForPath was fixed
                val refreshed = cleanedRegistry.copy(
                    graphs = cleanedRegistry.graphs.map { graph ->
                        val freshName = fileSystem.displayNameForPath(graph.path)
                        if (freshName != graph.displayName) graph.copy(displayName = freshName) else graph
                    }
                )
                _graphRegistry.value = refreshed
                if (refreshed != registry) saveRegistry()
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
            val newDbPath = driverFactory.getDatabaseUrl(graphId.value).substringAfter("jdbc:sqlite:")
            
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
    private fun migrateWalShmFiles(dbDir: String, graphId: GraphId) {
        try {
            val newDbPath = driverFactory.getDatabaseUrl(graphId.value).substringAfter("jdbc:sqlite:")
            fileSystem.renameFile("$dbDir/logseq.db-wal", "$newDbPath-wal")
            fileSystem.renameFile("$dbDir/logseq.db-shm", "$newDbPath-shm")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Non-critical - WAL/SHM files may not exist
        }
    }
    
    private fun saveRegistry() {
        val toSave = _graphRegistry.value.let { r ->
            r.copy(
                graphs = r.graphs.filter { !it.isDemo },
                activeGraphId = if (r.activeGraphId == DEMO_GRAPH_ID) null else r.activeGraphId
            )
        }
        platformSettings.putString("graph_registry", json.encodeToString(toSave))
    }
    
    fun graphIdFromPath(path: String): GraphId =
        GraphId(ContentHasher.sha256(path).take(16))

    suspend fun addGraph(path: String): GraphId {
        // Use expanded path for consistent ID generation
        val expandedPath = fileSystem.expandTilde(path)
        val graphId = graphIdFromPath(expandedPath)

        // Move SAF Binder IPC off the main thread — these calls can take hundreds of ms
        // on real hardware and cause ANR when called from a LaunchedEffect.
        val (displayName, isParanoidMode) = withContext(PlatformDispatcher.IO) {
            val dn = fileSystem.displayNameForPath(expandedPath)
            // Warn if the SQLite database files are not gitignored.
            // The .db, .db-wal, and .db-shm files must never be committed to git
            // as they are binary and will cause unresolvable merge conflicts.
            checkGitignoreForDatabase(expandedPath)
            val ipm = fileSystem.fileExists(VaultManager.vaultFilePath(expandedPath))
            dn to ipm
        }
        val info = GraphInfo(
            id = graphId,
            path = expandedPath,
            displayName = displayName,
            addedAt = Clock.System.now().toEpochMilliseconds(),
            isParanoidMode = isParanoidMode,
        )
        
        val registry = _graphRegistry.value
        if (!registry.graphIds.contains(graphId)) {
            val updated = registry.copy(
                graphs = registry.graphs + info
            )
            _graphRegistry.value = updated
            saveRegistry()
        }

        // Fire-and-forget git detection; updates registry when complete
        coroutineScope.launch(PlatformDispatcher.IO) {
            val detected = detectGitRoot(expandedPath)
            if (detected != null) {
                updateGraphInfoDetection(graphId, detected.first, detected.second)
            }
        }

        return graphId
    }

    /**
     * Registers the demo graph in-memory only. The entry is never written to the persistent
     * registry (saveRegistry() strips isDemo entries). Idempotent — calling twice is safe.
     */
    fun addDemoGraph(): GraphId {
        val demoId = DEMO_GRAPH_ID
        val info = GraphInfo(
            id = demoId,
            path = "/demo",
            displayName = "Demo Graph",
            addedAt = Clock.System.now().toEpochMilliseconds(),
            isDemo = true
        )
        _graphRegistry.update { registry ->
            if (!registry.graphs.any { it.id == demoId })
                registry.copy(graphs = registry.graphs + info)
            else registry
        }
        logger.info("addDemoGraph: registered DEMO_GRAPH_ID in-memory")
        return demoId
    }

    /**
     * Clones a remote git repository to [localPath] and registers it as a new graph.
     * Returns the new graphId on success, or a [DomainError.GitError] on failure.
     * The clone step runs on [PlatformDispatcher.IO]; registration runs on the calling dispatcher.
     */
    suspend fun cloneAndAdd(
        gitRepository: GitRepository,
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (String) -> Unit,
    ): Either<DomainError.GitError, GraphId> {
        val cloneResult = gitRepository.clone(url, localPath, auth, onProgress)
        return cloneResult.map { addGraph(localPath) }
    }

    fun removeGraph(id: GraphId): Boolean {
        // Cancel any active coroutines for this graph
        activeGraphJobs.remove(id)?.cancel()
        
        val registry = _graphRegistry.value
        val graphIndex = registry.graphs.indexOfFirst { it.id == id }
        if (graphIndex == -1) return false

        // Don't allow removing active graph
        if (registry.activeGraphId == id) return false

        if (registry.graphs[graphIndex].isDemo) return false

        val updated = registry.copy(
            graphs = registry.graphs.filter { it.id != id }
        )
        _graphRegistry.value = updated
        saveRegistry()

        // Clean up git credentials stored for this graph
        try {
            val cs = dev.stapler.stelekit.platform.security.CredentialStore()
            cs.delete("git_https_token_${id.value}")
            cs.delete("git_ssh_passphrase_${id.value}")
        } catch (_: Exception) {
            // Non-critical — credential cleanup failure should not prevent graph removal
        }

        return true
    }
    
    fun renameGraph(id: GraphId, newName: String): Boolean {
        val registry = _graphRegistry.value
        val graphIndex = registry.graphs.indexOfFirst { it.id == id }
        if (graphIndex == -1) return false
        if (registry.graphs[graphIndex].isDemo) return false

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
    fun switchGraph(id: GraphId) {
        val registry = _graphRegistry.value
        val graphInfo = registry.graphs.firstOrNull { it.id == id }
        if (graphInfo == null) return

        // Idempotency guard: skip re-initialization if this graph is already active AND either
        // (a) its repositories are ready, or (b) an init job is already running for it.
        // Without this guard, StelekitApp's LaunchedEffect fires a second switchGraph() right
        // after GraphManager.init {} already called it. On fast devices (b) is rarely needed —
        // DB init completes before the LaunchedEffect fires. On real devices with large graphs
        // init is slow, so the LaunchedEffect arrives while _activeRepositorySet is still null,
        // and checking only (a) lets the second call cancel the first init scope → crash.
        val currentGraphId = registry.activeGraphId
        if (currentGraphId == id && (_activeRepositorySet.value != null || activeGraphJobs.containsKey(id))) return
        currentGraphId?.let { activeGraphJobs.remove(it)?.cancel() }

        // Shutdown any git sync service from the previous graph
        _activeGitSyncService.value?.shutdown()
        _activeGitSyncService.value = null
        _activeVaultCredentialStore.value = null

        // Null the repo set BEFORE closing the driver so Compose flow collectors see null
        // and stop querying before the database connection is torn down. Closing first
        // caused a race where in-flight LaunchedEffect queries hit an already-closed DB.
        // The actual close() call is deferred to the IO coroutine below — pragmaOptimizeAndClose()
        // now runs a PRAGMA wal_checkpoint(TRUNCATE), which can take seconds on a large WAL, and
        // switchGraph() is called synchronously from the Compose UI dispatcher (rememberCoroutineScope
        // in App.kt), so running it here would freeze the UI on every graph switch.
        _activeRepositorySet.value = null
        val factoryToClose = currentFactory
        currentFactory = null

        // Create a new scope for this graph's operations first so the actor can use it.
        // MUST use a fresh SupervisorJob — CoroutineScope(coroutineScope.coroutineContext) would
        // share the same Job, so cancelling any previous graphScope (line above) would cancel
        // coroutineScope itself, making all subsequent launches immediately fail.
        val graphScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        activeGraphJobs[id] = graphScope

        // Expose a Deferred for callers that need to await the full initialization.
        val deferred = CompletableDeferred<Unit>()
        _pendingMigration = deferred

        // Driver creation (which runs MigrationRunner via runBlocking internally) and the
        // subsequent UUID/changelog migrations all move to an IO coroutine so they never
        // execute on the main/calling thread. preFlightJob is awaited first to ensure any
        // startup write-behind flush completes before we open the database.
        //
        // The outer try/finally guarantees deferred.complete(Unit) is called in ALL cases —
        // including unhandled exceptions from factory/repoSet creation — so awaitPendingMigration()
        // never hangs permanently.
        graphScope.launch(PlatformDispatcher.IO) {
            try {
                val t0 = kotlin.time.Clock.System.now().toEpochMilliseconds()
                fun elapsed() = kotlin.time.Clock.System.now().toEpochMilliseconds() - t0

                // Close the previous graph's database off the UI thread — see comment above
                // where factoryToClose was captured. Guarded independently (not by the outer
                // try/catch below) so a close failure — e.g. wal_checkpoint(TRUNCATE) lock
                // contention — can never abort opening the new graph. Letting it propagate
                // would skip the rest of this block entirely, leaving currentFactory/
                // _activeRepositorySet null while activeGraphJobs[id] is already set — the
                // idempotency guard at the top of switchGraph() would then treat this graph
                // as "already initializing" forever and silently no-op every retry.
                try {
                    factoryToClose?.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to close previous graph's factory before switching to $id", e)
                }
                logger.info("init[${elapsed()}ms]: previous factory closed")

                preFlightJob?.await()
                logger.info("init[${elapsed()}ms]: preFlightJob done")

                val dbUrl = driverFactory.getDatabaseUrl(id.value)
                val factory = dev.stapler.stelekit.repository.RepositoryFactoryImpl(driverFactory, dbUrl, graphId = id.value)
                val deviceInfo = try {
                    dev.stapler.stelekit.performance.getDeviceInfo()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val backend = if (graphInfo.isDemo) {
                    logger.info("switchGraph: demo mode — forcing IN_MEMORY backend")
                    GraphBackend.IN_MEMORY
                } else {
                    defaultBackend
                }
                val repoSet = factory.createRepositorySet(
                    backend = backend,
                    scope = graphScope,
                    fileSystem = fileSystem,
                    appVersion = deviceInfo?.appVersion ?: "unknown",
                    platform = deviceInfo?.platform ?: "unknown",
                )
                logger.info("init[${elapsed()}ms]: createRepositorySet done")
                currentFactory = factory
                _activeRepositorySet.value = repoSet

                if (defaultBackend == GraphBackend.SQLDELIGHT && !graphInfo.isDemo) {
                    val writeActor = repoSet.writeActor
                    if (writeActor != null) {
                        val db = factory.steleDatabase()
                        UuidMigration(writeActor).runIfNeeded(db)
                        logger.info("init[${elapsed()}ms]: UuidMigration done")
                        try {
                            MigrationRunner(
                                registry = MigrationRegistry,
                                changelogRepo = ChangelogRepository(db),
                                evaluator = DslEvaluator(repoSet),
                                applier = ChangeApplier(writeActor, opLogger = null),
                                flusher = null,
                            ).runPending(id.value, repoSet, graphInfo.path)
                        } catch (e: InterruptedMigrationException) {
                            logger.error("MigrationRunner: interrupted migration detected for graph $id", e)
                        } catch (e: MigrationTamperedError) {
                            logger.error("MigrationRunner: tampered migration detected for graph $id", e)
                        } catch (e: ConcurrentMigrationRunException) {
                            // Another switchGraph() call is already migrating this graph — expected
                            // under racing startup/UI triggers, not a failure. The winning call
                            // completes the migration; this one just backs off.
                            logger.warn("MigrationRunner: concurrent run detected for graph $id — backing off", e)
                        }
                        logger.info("init[${elapsed()}ms]: content migrations done")
                    }
                }
                repoSet.spanEmitter?.emit("db.init", t0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("switchGraph initialization failed for graph $id", e)
            } finally {
                deferred.complete(Unit)
            }
        }

        // Update active graph — use atomic update {} to avoid clobbering concurrent registry
        // mutations (e.g. git detection updating detectedRepoRoot on a background IO coroutine).
        _graphRegistry.update { it.copy(activeGraphId = id) }
        saveRegistry()
    }

    /**
     * Suspends until the one-shot UUID migration for the currently active graph has completed.
     * Returns the [RepositorySet] that is ready to use, or null if initialization failed.
     */
    suspend fun awaitPendingMigration(): RepositorySet? {
        _pendingMigration.await()
        return _activeRepositorySet.value
    }

    /**
     * Registers [path] as a graph, switches to it, and waits until the database and migrations
     * are ready. Returns the live [RepositorySet] for the opened graph.
     *
     * Use this instead of calling [addGraph] + [switchGraph] + [awaitPendingMigration] manually —
     * the type system enforces the ordering and you cannot access the repository before it is ready.
     *
     * @throws IllegalStateException if the database failed to open after registration.
     */
    suspend fun openGraph(path: String): RepositorySet {
        val id = addGraph(path)
        switchGraph(id)
        return awaitPendingMigration()
            ?: error("Failed to open graph at '$path' — database did not initialise")
    }
    
    fun getGraphInfo(id: GraphId): GraphInfo? {
        return _graphRegistry.value.graphs.firstOrNull { it.id == id }
    }

    fun getActiveGraphId(): GraphId? {
        return _graphRegistry.value.activeGraphId
    }

    fun getActiveGraphInfo(): GraphInfo? {
        val activeId = _graphRegistry.value.activeGraphId ?: return null
        return getGraphInfo(activeId)
    }

    fun getGraphIds(): Set<GraphId> =
        _graphRegistry.value.graphs.map { it.id }.toSet()
    
    fun getActiveRepositorySet(): RepositorySet? = _activeRepositorySet.value
    
    private suspend fun detectGitRoot(graphPath: String): Pair<String, String>? {
        if (graphPath.startsWith("content://")) return null
        return withContext(PlatformDispatcher.IO) {
            try {
                val normalizedPath = graphPath.replace('\\', '/')
                var currentPath = normalizedPath.trimEnd('/')
                var depth = 0
                while (depth <= 10 && currentPath.isNotEmpty()) {
                    val gitPath = "$currentPath/.git"
                    if (fileSystem.fileExists(gitPath) || fileSystem.directoryExists(gitPath)) {
                        val wikiSubdir = normalizedPath
                            .removePrefix(currentPath)
                            .trimStart('/')
                        return@withContext Pair(currentPath, wikiSubdir)
                    }
                    val lastSlash = currentPath.lastIndexOf('/')
                    if (lastSlash <= 0) break
                    currentPath = currentPath.substring(0, lastSlash)
                    depth++
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun updateGraphInfoDetection(graphId: GraphId, repoRoot: String, wikiSubdir: String) {
        // Atomic update: prevents clobbering concurrent activeGraphId changes from switchGraph.
        _graphRegistry.update { registry ->
            val updatedGraphs = registry.graphs.map { g ->
                if (g.id == graphId) g.copy(detectedRepoRoot = repoRoot, detectedWikiSubdir = wikiSubdir)
                else g
            }
            registry.copy(graphs = updatedGraphs)
        }
        saveRegistry()
    }

    suspend fun setGitDetectionDismissed(graphId: GraphId, dismissed: Boolean) {
        val registry = _graphRegistry.value
        val updatedGraphs = registry.graphs.map { g ->
            if (g.id == graphId) g.copy(gitDetectionDismissed = dismissed)
            else g
        }
        _graphRegistry.value = registry.copy(graphs = updatedGraphs)
        saveRegistry()
    }

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
        if (!content.contains(".stele-vault")) {
            println("WARNING: $gitignorePath does not contain '.stele-vault' — vault header file may be accidentally committed to git.")
        }
        if (!content.contains(".stele-credentials")) {
            println("WARNING: $gitignorePath does not contain '.stele-credentials' — vault credential file may be accidentally committed to git.")
        }
        if (!content.contains("_hidden_reserve")) {
            println("WARNING: $gitignorePath does not contain '_hidden_reserve/' — hidden volume directory may be accidentally committed to git, exposing its existence.")
        }
    }

    /**
     * Registers the [GitSyncService] for the currently active graph.
     *
     * Called from [GraphContent] after [GraphLoader] and [GraphWriter] are constructed,
     * because those objects are Compose-managed and cannot be created inside [GraphManager].
     * The service is automatically shut down on the next [switchGraph] call or on [shutdown].
     */
    fun registerGitSyncService(service: dev.stapler.stelekit.git.GitSyncService?) {
        _activeGitSyncService.value = service
    }

    /**
     * Registers the [VaultCredentialStore] for the currently active graph.
     * Called from App.kt when a paranoid-mode graph is active.
     * Set to null for non-paranoid graphs.
     */
    fun registerVaultCredentialStore(store: dev.stapler.stelekit.git.VaultCredentialStore?) {
        _activeVaultCredentialStore.value = store
    }

    /**
     * Creates a [GitConfigRepository] backed by the currently active graph's database.
     * Returns null if the database is not yet open or the backend is not SQLDELIGHT.
     *
     * Called from [GraphContent] to wire the [GitSyncService] construction.
     */
    fun createGitConfigRepository(): dev.stapler.stelekit.git.GitConfigRepository? {
        val factory = currentFactory as? dev.stapler.stelekit.repository.RepositoryFactoryImpl ?: return null
        val actor = _activeRepositorySet.value?.writeActor ?: return null
        return dev.stapler.stelekit.git.SqlDelightGitConfigRepository(
            database = factory.steleDatabase(),
            writeActor = actor,
        )
    }

    /**
     * Clean up all resources when shutting down
     */
    fun shutdown() {
        // Cancel all graph-specific coroutines
        activeGraphJobs.values.forEach { it.cancel() }
        activeGraphJobs.clear()

        // Shutdown git sync service
        _activeGitSyncService.value?.shutdown()
        _activeGitSyncService.value = null
        _activeVaultCredentialStore.value = null

        // Null repo set before closing so in-flight Compose flow collectors stop querying first.
        _activeRepositorySet.value = null
        currentFactory?.close()
        currentFactory = null
    }
}
