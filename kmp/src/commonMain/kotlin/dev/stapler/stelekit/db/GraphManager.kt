// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.llm.buildLlmProviderRegistry
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
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.vault.VaultManager
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.security.CredentialStore
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Outcome of [GraphManager.updateGraphPath]. */
sealed interface UpdateGraphPathResult {
    data class Success(val newId: GraphId) : UpdateGraphPathResult
    data object GraphNotFound : UpdateGraphPathResult
    data object DemoGraphImmutable : UpdateGraphPathResult
    data object PathNotFound : UpdateGraphPathResult
    data object PathUnchanged : UpdateGraphPathResult
    data object AlreadyTracked : UpdateGraphPathResult
    data object DatabaseMoveFailed : UpdateGraphPathResult
}

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
) : StorageLocationStore {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = Logger("GraphManager")
    private val json = Json { ignoreUnknownKeys = true }
    private val _graphRegistry = MutableStateFlow(GraphRegistry())
    val graphRegistry: StateFlow<GraphRegistry> = _graphRegistry.asStateFlow()
    
    private val _activeRepositorySet = MutableStateFlow<RepositorySet?>(null)
    val activeRepositorySet: StateFlow<RepositorySet?> = _activeRepositorySet.asStateFlow()

    /**
     * `true` iff [removeGraph] just removed the last real (non-demo) graph while it was active,
     * leaving `activeGraphId = null`. Distinct from merely checking `activeGraphId == null`
     * because that condition is also transiently true for one frame on a brand-new install
     * (before `App.kt`'s `LaunchedEffect(currentGraphPath)` self-heals by adding/activating a
     * default graph) — a Compose-level empty-state screen gated on that alone would flash on
     * every first launch. This flag is only ever set by an explicit user removal, and cleared by
     * [switchGraph], so it stays `false` for the entire first-launch path and reliably
     * distinguishes "the user emptied their graph list" from "no graph has loaded yet."
     * Session-scoped: a fresh [GraphManager] (i.e. a page reload on wasmJs) starts at `false`.
     */
    private val _graphsExplicitlyEmptied = MutableStateFlow(false)
    val graphsExplicitlyEmptied: StateFlow<Boolean> = _graphsExplicitlyEmptied.asStateFlow()

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

    // Locations passed to addGraph() before that graph's own database is open (addGraph() runs
    // before the caller's own switchGraph() call reopens the driver — see onGraphLocationDetermined).
    // Flushed by switchGraph()'s init block once a writeActor exists for the graph, and evicted by
    // removeGraph() (see evictPendingStorageLocationFor) if the graph is removed before ever being
    // flushed — otherwise the entry leaks forever, and a later re-add of the same path (same
    // GraphId, since GraphId = sha256(path)) would replay a write meant for the removed instance.
    // onGraphLocationDetermined is callable from any dispatcher, and the flush (switchGraph, on
    // PlatformDispatcher.IO) and evict (removeGraph, dispatched onto coroutineScope) sites run on
    // different coroutines too — pendingStorageLocationsMutex guards every read/write of this map,
    // mirroring coordinatorMutex's guard on coordinatorFor below.
    private val pendingStorageLocationsMutex = Mutex()
    private val pendingStorageLocations = mutableMapOf<GraphId, StorageLocation>()

    // Memoized per-graph CaptureEnrichmentCoordinator construction (Epic 1.2). coordinatorMutex
    // guards only the cache read/insert below, never the Deferred.await() — see
    // getOrCreateEnrichmentCoordinator().
    private val coordinatorMutex = Mutex()
    private var coordinatorFor: Pair<GraphId, Deferred<CaptureEnrichmentCoordinator>>? = null

    /**
     * Evicts the memoized [CaptureEnrichmentCoordinator] cache entry for [graphId] if it's the
     * currently cached one — called from [switchGraph] and [removeGraph] alongside the
     * `activeGraphJobs.remove(id)?.cancel()` call that tears down the [CoroutineScope] the
     * coordinator (and its [dev.stapler.stelekit.domain.PageNameIndex]'s `stateIn`/collector)
     * was built on. Without this, a coordinator whose construction already completed survives
     * as a silently-frozen zombie tied to a cancelled scope — switching away and back to the
     * same graph would keep returning it, and suggestions would never update again for that
     * graph. Guarded by the same [coordinatorMutex] every other `coordinatorFor` access uses.
     *
     * Dispatched on [coroutineScope] (fire-and-forget) rather than acquired synchronously:
     * [switchGraph]/[removeGraph] are not `suspend`, and this class's commonMain code is built
     * for wasmJs too, where a blocking acquire (`runBlocking`) is not safe to use — see
     * `RepositoryFactory.kt`'s "WASM/JS skips PRAGMA" precedent for the same constraint.
     * [coordinatorMutex] is documented to be held only for a quick map read/insert, never across
     * a suspension point, so this eviction is queued and lands promptly.
     */
    private fun evictCoordinatorFor(graphId: GraphId) {
        coroutineScope.launch {
            coordinatorMutex.withLock {
                if (coordinatorFor?.first == graphId) coordinatorFor = null
            }
        }
    }

    /**
     * Removes any not-yet-flushed [StorageLocation] queued in [pendingStorageLocations] for
     * [graphId] — called from [removeGraph] so a graph removed before [switchGraph] ever ran its
     * flush block doesn't leak the entry, and a subsequent re-add of the same path (same
     * [GraphId]) doesn't have a stale queued write silently applied to the new instance.
     *
     * Dispatched on [coroutineScope] (fire-and-forget) rather than acquired synchronously, for the
     * same reason as [evictCoordinatorFor]: [removeGraph] is not `suspend`, and a blocking acquire
     * is not safe on wasmJs.
     */
    private fun evictPendingStorageLocationFor(graphId: GraphId) {
        coroutineScope.launch {
            pendingStorageLocationsMutex.withLock { pendingStorageLocations.remove(graphId) }
        }
    }

    // Same construction recipe App.kt:490-500 uses for the Compose tree's registry — CaptureActivity
    // never runs that composition, so GraphManager builds its own equivalent, self-contained.
    // A single shared instance (not re-constructed per call) so getOrCreateEnrichmentCoordinator()
    // can pass the same LlmSettings into resolveTopicEnricher() for per-feature provider selection.
    private val llmSettings: LlmSettings by lazy { LlmSettings(platformSettings) }
    private val llmProviderRegistry: LlmProviderRegistry by lazy {
        buildLlmProviderRegistry(
            LlmCredentialStore(CredentialStore()),
            llmSettings,
        )
    }

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
                // Only backfill blank names — a non-blank name may be user-chosen (rename / New graph)
                // and must survive restarts.
                val refreshed = cleanedRegistry.copy(
                    graphs = cleanedRegistry.graphs.map { graph ->
                        if (graph.displayName.isBlank()) graph.copy(displayName = fileSystem.displayNameForPath(graph.path)) else graph
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

    suspend fun addGraph(
        path: String,
        location: StorageLocation? = null,
        displayName: String? = null,
        description: String = "",
    ): GraphId {
        // Use expanded path for consistent ID generation
        val expandedPath = fileSystem.expandTilde(path)
        val graphId = graphIdFromPath(expandedPath)

        // Move SAF Binder IPC off the main thread — these calls can take hundreds of ms
        // on real hardware and cause ANR when called from a LaunchedEffect.
        val (derivedName, isParanoidMode) = withContext(PlatformDispatcher.IO) {
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
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: derivedName,
            description = description.trim(),
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

        if (location != null) {
            onGraphLocationDetermined(graphId.value, location).onLeft {
                logger.warn("addGraph: failed to persist storage location for graph $graphId: ${it::class.simpleName}")
            }
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
        // Story 2.2.2: the destination StorageLocation resolved by UnifiedLocationPicker, so the
        // cloned graph's storage_locations row is written at creation time instead of waiting for
        // a later relocate/link flow to lazily backfill it. Default null preserves every existing
        // caller's behavior unchanged.
        location: StorageLocation? = null,
        displayName: String? = null,
        description: String = "",
    ): Either<DomainError.GitError, GraphId> {
        val cloneResult = gitRepository.clone(url, localPath, auth, onProgress)
        return cloneResult.map { addGraph(localPath, location, displayName, description) }
    }

    /**
     * Resets the active-graph-scoped fields (git sync service, vault credential store,
     * repository set) and hands back [currentFactory] for the caller to close asynchronously.
     * Shared by [switchGraph] (tearing down the outgoing graph before opening the next one) and
     * [removeGraph] (tearing down the last graph with nothing to open next), so the two copies
     * of this sequence can't silently drift apart.
     *
     * `internal` (Story 3.1.5, Task 3.1.5c) so `GraphRelocationCoordinator` can reuse this exact
     * close mechanism directly instead of inventing a second one — it is the one caller that
     * awaits the returned factory's `close()` itself, synchronously, rather than deferring the
     * close to a background coroutine the way [switchGraph]/[removeGraph] do below.
     */
    internal fun tearDownActiveGraphResources(): dev.stapler.stelekit.repository.RepositoryFactory? {
        _activeGitSyncService.value?.shutdown()
        _activeGitSyncService.value = null
        _activeVaultCredentialStore.value = null
        // Null the repo set before closing the driver so Compose flow collectors see null and
        // stop querying before the database connection is torn down.
        _activeRepositorySet.value = null
        val factoryToClose = currentFactory
        currentFactory = null
        return factoryToClose
    }

    /**
     * Unregisters [id] from the graph registry. Always clears its queued
     * [pendingStorageLocations] entry, if any. Its `storage_locations` DB row is deleted too, but
     * only on the path where [id] is the sole active graph being torn down — a non-active graph's
     * per-graph database isn't open at removal time (see [onGraphLocationDetermined]'s KDoc), so
     * deleting that row would require briefly opening a database this method has no reachable
     * connection to; that gap is unaddressed here.
     */
    fun removeGraph(id: GraphId): Boolean {
        // Cancel any active coroutines for this graph
        activeGraphJobs.remove(id)?.cancel()
        evictCoordinatorFor(id)
        evictPendingStorageLocationFor(id)

        val registry = _graphRegistry.value
        val graphIndex = registry.graphs.indexOfFirst { it.id == id }
        if (graphIndex == -1) return false

        if (registry.graphs[graphIndex].isDemo) return false

        // The demo graph is always registered (addDemoGraph() runs unconditionally at boot) and
        // is never itself removable (guarded above), so it doesn't count as a "real" graph here —
        // registry.graphs.size alone would never reach 1 with the demo graph always present.
        val realGraphs = registry.graphs.filter { !it.isDemo }
        val isOnlyRealGraph = realGraphs.size == 1 && realGraphs[0].id == id

        if (registry.activeGraphId == id) {
            // Removing the active graph is only allowed when it's the last real graph left —
            // otherwise the caller must switch to another graph first, since there's no way to
            // pick which graph becomes active next. When it IS the last real graph, tear down its
            // repository set the same way switchGraph() tears down an outgoing graph, but without
            // opening a new one (deliberately not falling back to the demo graph — the app is left
            // with zero active graphs so the empty-state prompt can offer to create one).
            if (!isOnlyRealGraph) return false

            _graphsExplicitlyEmptied.value = true
            // storage_locations lives in each graph's own per-graph database file (see
            // onGraphLocationDetermined's KDoc) — only reachable here because this is the active
            // graph and tearDownActiveGraphResources() hasn't nulled currentFactory/writeActor
            // yet. Capture them first so the row can be deleted before the factory closes,
            // preventing a later re-add of the same path (same GraphId = sha256(path)) from
            // inheriting this stale/revoked location. A non-active graph's database isn't open at
            // removal time, so that case isn't covered by this call — see the removeGraph() KDoc.
            //
            // Gated on defaultBackend == SQLDELIGHT, mirroring switchGraph()'s identical guard on
            // its own storage_locations write below: currentFactory is a RepositoryFactoryImpl
            // and writeActor is non-null for EVERY backend (see RepositoryFactoryImpl.
            // createRepositorySet — the actor is constructed whenever a scope is passed, backend
            // notwithstanding), but factory.steleDatabase() lazily opens a real SQLite
            // connection and runs MigrationRunner on first access — which must never happen for
            // an IN_MEMORY-backend graph.
            val factoryForDelete = (currentFactory as? dev.stapler.stelekit.repository.RepositoryFactoryImpl)
                ?.takeIf { defaultBackend == GraphBackend.SQLDELIGHT }
            val actorForDelete = _activeRepositorySet.value?.writeActor
            val factoryToClose = tearDownActiveGraphResources()
            coroutineScope.launch(PlatformDispatcher.IO) {
                deleteStorageLocationRowThenCloseFactory(id, factoryForDelete, actorForDelete, factoryToClose)
            }
        }

        // Atomic update {} — not a plain .value = ... assignment — so this can't clobber a
        // concurrent registry mutation from a background IO coroutine (e.g. git detection
        // updating detectedRepoRoot), the same hazard switchGraph()'s own activeGraphId update
        // is guarded against. Newly reachable here: before this method allowed removing the
        // active graph, this branch never wrote the registry at all.
        _graphRegistry.update {
            it.copy(
                graphs = it.graphs.filter { g -> g.id != id },
                activeGraphId = if (it.activeGraphId == id) null else it.activeGraphId,
            )
        }
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
    
    fun updateGraphDescription(id: GraphId, description: String): Boolean {
        val registry = _graphRegistry.value
        val index = registry.graphs.indexOfFirst { it.id == id }
        if (index == -1 || registry.graphs[index].isDemo) return false
        val updatedGraphs = registry.graphs.toMutableList()
        updatedGraphs[index] = updatedGraphs[index].copy(description = description.trim())
        _graphRegistry.value = registry.copy(graphs = updatedGraphs)
        saveRegistry()
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
     * Records the real host-folder name last linked for [id] (web-local-folder-livesync only) —
     * called after [dev.stapler.stelekit.platform.FileSystem.pickDirectoryAsync]/
     * [dev.stapler.stelekit.platform.FileSystem.relinkHostDirectoryAsync] succeeds, so the sidebar
     * can show which real folder a graph is linked to instead of only its internal [GraphInfo.path].
     * No data migration is needed here — the actual re-link (importing the new folder's content
     * and attaching the fresh handle) already happened in [dev.stapler.stelekit.platform.FileSystem];
     * this just persists the display metadata.
     */
    fun updateHostDirName(id: GraphId, dirName: String?): Boolean {
        val registry = _graphRegistry.value
        val graphIndex = registry.graphs.indexOfFirst { it.id == id }
        if (graphIndex == -1) return false
        // Copilot review: matches renameGraph/updateGraphPath's immutability rule — the demo
        // graph's metadata is never user-editable.
        if (registry.graphs[graphIndex].isDemo) return false

        val updatedGraphs = registry.graphs.toMutableList()
        updatedGraphs[graphIndex] = updatedGraphs[graphIndex].copy(hostDirName = dirName)
        _graphRegistry.value = registry.copy(graphs = updatedGraphs)
        saveRegistry()
        return true
    }

    /**
     * Moves a graph to a new filesystem [newPath]. Because [GraphId] is derived from
     * sha256(canonicalPath), this re-keys the graph's identity: the SQLite DB (+ WAL/SHM
     * sidecars), the telemetry DB, and any stored git credentials are renamed/re-keyed from
     * the old id to the new one, then the registry entry is replaced in place. If the graph
     * being moved is currently active, it is reopened under the new id via [switchGraph].
     */
    suspend fun updateGraphPath(id: GraphId, newPath: String): UpdateGraphPathResult {
        val registry = _graphRegistry.value
        val graphIndex = registry.graphs.indexOfFirst { it.id == id }
        if (graphIndex == -1) return UpdateGraphPathResult.GraphNotFound
        val graphInfo = registry.graphs[graphIndex]
        if (graphInfo.isDemo) return UpdateGraphPathResult.DemoGraphImmutable

        val expandedNewPath = fileSystem.expandTilde(newPath)
        val newId = graphIdFromPath(expandedNewPath)
        if (newId == id) return UpdateGraphPathResult.PathUnchanged
        if (registry.graphIds.contains(newId)) return UpdateGraphPathResult.AlreadyTracked

        val pathExists = withContext(PlatformDispatcher.IO) { fileSystem.directoryExists(expandedNewPath) }
        if (!pathExists) return UpdateGraphPathResult.PathNotFound

        val moved = withContext(PlatformDispatcher.IO) { moveGraphFilesAndCredentials(id, newId) }
        if (!moved) return UpdateGraphPathResult.DatabaseMoveFailed

        val displayName = fileSystem.displayNameForPath(expandedNewPath)
        val updatedInfo = graphInfo.copy(
            id = newId,
            path = expandedNewPath,
            displayName = displayName,
            // The new folder may not share the old repo root — force re-detection.
            detectedRepoRoot = null,
            detectedWikiSubdir = null,
            gitDetectionDismissed = false,
        )
        val updatedGraphs = registry.graphs.toMutableList()
        updatedGraphs[graphIndex] = updatedInfo
        _graphRegistry.value = registry.copy(graphs = updatedGraphs)

        if (registry.activeGraphId == id) {
            // Defer persistence to switchGraph(), which saves the re-keyed graph list together
            // with the updated activeGraphId in one write. Saving here first would leave a crash
            // window where the on-disk registry has the graph re-keyed but activeGraphId still
            // pointing at the now-nonexistent old id, breaking startup auto-restore.
            switchGraph(newId)
        } else {
            saveRegistry()
        }

        coroutineScope.launch(PlatformDispatcher.IO) {
            val detected = detectGitRoot(expandedNewPath)
            if (detected != null) {
                updateGraphInfoDetection(newId, detected.first, detected.second)
            }
        }

        return UpdateGraphPathResult.Success(newId)
    }

    /**
     * Renames the on-disk DB (+ WAL/SHM), telemetry DB, and credential-store entries from
     * [oldId] to [newId]. Returns false only if the main DB file exists but could not be
     * renamed — telemetry and credential migration are best-effort and never fail the move.
     */
    private fun moveGraphFilesAndCredentials(oldId: GraphId, newId: GraphId): Boolean {
        val oldDbPath = driverFactory.getDatabaseUrl(oldId.value).substringAfter("jdbc:sqlite:")
        val newDbPath = driverFactory.getDatabaseUrl(newId.value).substringAfter("jdbc:sqlite:")
        if (fileSystem.fileExists(oldDbPath)) {
            val moved = AtomicFileRelocationStep.relocate(
                fileSystem,
                listOf(
                    FileMove(oldDbPath, newDbPath),
                    FileMove("$oldDbPath-wal", "$newDbPath-wal", optional = true),
                    FileMove("$oldDbPath-shm", "$newDbPath-shm", optional = true),
                ),
            )
            if (moved.isLeft()) return false
        }

        try {
            val oldTelemetryPath = driverFactory.getTelemetryDatabaseUrl(oldId.value).substringAfter("jdbc:sqlite:")
            val newTelemetryPath = driverFactory.getTelemetryDatabaseUrl(newId.value).substringAfter("jdbc:sqlite:")
            if (fileSystem.fileExists(oldTelemetryPath)) {
                val telemetryMoved = fileSystem.renameFile(oldTelemetryPath, newTelemetryPath)
                if (telemetryMoved) {
                    renameSidecarIfPresent("$oldTelemetryPath-wal", "$newTelemetryPath-wal")
                    renameSidecarIfPresent("$oldTelemetryPath-shm", "$newTelemetryPath-shm")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Non-critical — telemetry data loss should not block the path move
        }

        try {
            val cs = dev.stapler.stelekit.platform.security.CredentialStore()
            for (prefix in listOf("git_https_token_", "git_ssh_passphrase_")) {
                val value = cs.retrieve("$prefix${oldId.value}")
                if (value != null) {
                    cs.store("$prefix${newId.value}", value)
                    cs.delete("$prefix${oldId.value}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Non-critical — credential migration failure should not block the path move
        }

        return true
    }

    /**
     * Renames a WAL/SHM sidecar file if it exists at [oldPath]. Sidecars only exist when a WAL
     * checkpoint hasn't run, so a missing sidecar is not a failure. Returns false only when the
     * sidecar existed but the rename itself failed — callers that must not silently lose
     * uncommitted WAL data should treat that as a failed move.
     */
    private fun renameSidecarIfPresent(oldPath: String, newPath: String): Boolean {
        if (!fileSystem.fileExists(oldPath)) return true
        return fileSystem.renameFile(oldPath, newPath)
    }

    /**
     * Switch to a different graph.
     * Closes the current database connection and opens a new one for the target graph.
     *
     * @param forceReinit Bypasses the idempotency guard below even when [id] is already the
     *   active graph (Story 3.1.5, Task 3.1.5b). `GraphRelocationCoordinator` is the only caller
     *   that ever passes `true` — a relocate keeps the same [GraphId], so without this the guard
     *   would treat the coordinator's post-relocate reopen as a no-op "already active" switch and
     *   never actually reopen the connection to the just-moved files. Every other call site keeps
     *   the default `false`; the guard's racing-`LaunchedEffect` protection (see its own comment
     *   below) is unchanged for ordinary switches.
     */
    fun switchGraph(id: GraphId, forceReinit: Boolean = false) {
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
        val isAlreadyTargetGraph = currentGraphId == id && !forceReinit
        val hasReadyOrInitializingRepositories = _activeRepositorySet.value != null || activeGraphJobs.containsKey(id)
        if (isAlreadyTargetGraph && hasReadyOrInitializingRepositories) return
        currentGraphId?.let {
            activeGraphJobs.remove(it)?.cancel()
            evictCoordinatorFor(it)
        }

        // Closing the captured factory is deferred to the IO coroutine below —
        // pragmaOptimizeAndClose() now runs a PRAGMA wal_checkpoint(TRUNCATE), which can take
        // seconds on a large WAL, and switchGraph() is called synchronously from the Compose UI
        // dispatcher (rememberCoroutineScope in App.kt), so running it here would freeze the UI
        // on every graph switch.
        val factoryToClose = tearDownActiveGraphResources()

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
                        FilePathRootMigration(writeActor).runIfNeeded(db, graphInfo.path)
                        logger.info("init[${elapsed()}ms]: FilePathRootMigration done")
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
                // Flush any StorageLocation queued by addGraph() before this graph's driver was
                // open (see onGraphLocationDetermined's KDoc). Only pop the queued entry once the
                // write actually runs — popping unconditionally when writeActor is still null
                // (e.g. a non-SQLDELIGHT backend) would silently drop the location forever. The
                // whole check-write-remove sequence runs under one lock acquisition so a
                // concurrent onGraphLocationDetermined() call can't queue a fresher location
                // between this read and the remove() below, which would otherwise drop it.
                pendingStorageLocationsMutex.withLock {
                    pendingStorageLocations[id]?.let { location ->
                        repoSet.writeActor?.let { actor ->
                            writeStorageLocation(factory, actor, id.value, location).onLeft {
                                logger.warn("switchGraph: failed to flush pending storage location for graph $id: ${it::class.simpleName}")
                            }
                            pendingStorageLocations.remove(id)
                        }
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
        _graphsExplicitlyEmptied.value = false
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

    /**
     * Returns the current graph's memoized [CaptureEnrichmentCoordinator] (built once per
     * [GraphId], race-safe); `null` if there's no active graph/[RepositorySet]/scope yet.
     * [coordinatorMutex] guards only the cache read/insert, never the `await()` itself, so a
     * slow construction for one graph never blocks a concurrent call for a different graph — and
     * a [Deferred] that fails is evicted so the next call retries instead of replaying the failure.
     */
    suspend fun getOrCreateEnrichmentCoordinator(): CaptureEnrichmentCoordinator? {
        val (graphId, deferred) = coordinatorMutex.withLock {
            val graphId = _graphRegistry.value.activeGraphId ?: return@withLock null
            val repoSet = _activeRepositorySet.value ?: return@withLock null
            val scope = activeGraphJobs[graphId] ?: return@withLock null
            val existing = coordinatorFor?.takeIf { it.first == graphId }?.second
            val deferred = existing ?: scope.async(start = CoroutineStart.LAZY) {
                val topicEnricher = CaptureEnrichmentCoordinator.resolveTopicEnricher(llmProviderRegistry, llmSettings)
                CaptureEnrichmentCoordinator(repoSet.pageRepository, scope, topicEnricher)
            }.also { coordinatorFor = graphId to it }
            graphId to deferred
        } ?: return null
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            coordinatorMutex.withLock {
                if (coordinatorFor?.first == graphId) coordinatorFor = null
            }
            throw e
        }
    }

    private suspend fun detectGitRoot(graphPath: String): Pair<String, String>? {
        // Android SAF-opened graphs are "saf://{encodedTreeUri}/{relativePath}" (see
        // PlatformFileSystem.toSafRoot), never a literal "content://" prefix — this check never
        // matched a real SAF path. Walking such a path with the POSIX '/'-splitting logic below is
        // also structurally pointless: a SAF grant is scoped to exactly the folder the user picked,
        // so a `.git` above that folder is invisible to this app regardless of path-parsing
        // correctness — bail out explicitly instead of relying on the loop terminating safely once
        // it works its way back to (and then past) the "saf://" scheme delimiter.
        if (graphPath.startsWith("saf://") || graphPath.startsWith("content://")) {
            logger.info("detectGitRoot: skipping SAF/content path, git-repo auto-detection unsupported ($graphPath)")
            return null
        }
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

    suspend fun setBrowserOnlySyncBannerDismissed(graphId: GraphId, dismissed: Boolean) {
        val registry = _graphRegistry.value
        val updatedGraphs = registry.graphs.map { g ->
            if (g.id == graphId) g.copy(browserOnlySyncBannerDismissed = dismissed)
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
     * Overwrites [id]'s registered [GraphInfo.path] in place, preserving [GraphId] and every other
     * registry field — no file I/O, no id re-keying. This is the narrow complement
     * [GraphRelocationCoordinator] calls (alongside [onGraphLocationDetermined]) once its own
     * copy+verify+atomic-move has already physically placed the graph's content at [newPath] and
     * the driver has been reopened and confirmed against that new location.
     *
     * Deliberately distinct from [updateGraphPath]: that function re-derives a *new* [GraphId]
     * from the given path (since [GraphId] is `sha256(path)`) and re-keys the on-disk
     * database/telemetry/credential files to match — the mechanism behind the legacy, now-removed
     * freeform "Graph path" text field. A relocate keeps the same [GraphId] across a move (see
     * [GraphRelocationCoordinator]'s own doc), so reusing [updateGraphPath] here would both corrupt
     * that invariant and redundantly re-move files the coordinator already moved itself.
     */
    internal fun updateGraphContentPath(id: GraphId, newPath: String) {
        _graphRegistry.update { registry ->
            val idx = registry.graphs.indexOfFirst { it.id == id }
            if (idx == -1) return@update registry
            val updatedGraphs = registry.graphs.toMutableList()
            updatedGraphs[idx] = updatedGraphs[idx].copy(path = newPath)
            registry.copy(graphs = updatedGraphs)
        }
        saveRegistry()
    }

    /**
     * Persists [location] as [graphId]'s `storage_locations` row — the single place this table
     * is ever written from a creation or relocate flow (ADR-001, Story 1.1.3). If [graphId] isn't
     * the currently active graph (its driver may not be open yet — e.g. called from [addGraph]
     * before the caller's own [switchGraph]), the write is queued in [pendingStorageLocations]
     * and flushed by [switchGraph]'s init block once that graph's writeActor exists.
     */
    override suspend fun onGraphLocationDetermined(graphId: String, location: StorageLocation): Either<DomainError, Unit> {
        val id = GraphId(graphId)
        val factory = currentFactory as? dev.stapler.stelekit.repository.RepositoryFactoryImpl
        val actor = _activeRepositorySet.value?.writeActor
        return if (factory != null && actor != null && getActiveGraphId() == id) {
            writeStorageLocation(factory, actor, graphId, location)
        } else {
            pendingStorageLocationsMutex.withLock { pendingStorageLocations[id] = location }
            Unit.right()
        }
    }

    /**
     * Reads [graphId]'s persisted `storage_locations` row, if one exists — the read half of
     * [StorageLocationResolver]'s short-circuit (Story 1.1.4): a caller must never re-derive a
     * location that's already on record. Returns null both when no row exists and when [graphId]
     * isn't the currently active graph, mirroring [onGraphLocationDetermined]'s scope (only the
     * active graph's database is open at any given moment).
     */
    override suspend fun getStorageLocation(graphId: String): StorageLocation? {
        val factory = currentFactory as? dev.stapler.stelekit.repository.RepositoryFactoryImpl ?: return null
        if (getActiveGraphId() != GraphId(graphId)) return null
        return withContext(PlatformDispatcher.DB) {
            try {
                factory.steleDatabase().steleDatabaseQueries.selectStorageLocation(graphId)
                    .executeAsOneOrNull()
                    ?.toStorageLocationModel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("getStorageLocation: failed to read storage_locations row for graph $graphId", e)
                null
            }
        }
    }

    private suspend fun writeStorageLocation(
        factory: dev.stapler.stelekit.repository.RepositoryFactoryImpl,
        actor: DatabaseWriteActor,
        graphId: String,
        location: StorageLocation,
    ): Either<DomainError, Unit> {
        val restricted = RestrictedDatabaseQueries(factory.steleDatabase().steleDatabaseQueries)
        val row = location.toStorageLocationRow()
        return actor.execute(DatabaseWriteActor.Priority.HIGH) {
            try {
                @OptIn(DirectSqlWrite::class)
                restricted.upsertStorageLocation(
                    graph_id = graphId,
                    kind = row.kind,
                    tree_uri = row.treeUri,
                    real_path = row.realPath,
                    display_name = row.displayName,
                    updated_at_epoch_ms = Clock.System.now().toEpochMilliseconds(),
                )
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    /**
     * Deletes [graphId]'s `storage_locations` row (best-effort, logged on failure — never thrown)
     * via [factoryForDelete]/[actorForDelete] if both are non-null, then closes [factoryToClose].
     * The delete always runs to completion before the close, so it can never race a driver
     * shutdown out from under it. Extracted from [removeGraph]'s only-real-graph teardown path.
     */
    private suspend fun deleteStorageLocationRowThenCloseFactory(
        graphId: GraphId,
        factoryForDelete: dev.stapler.stelekit.repository.RepositoryFactoryImpl?,
        actorForDelete: DatabaseWriteActor?,
        factoryToClose: dev.stapler.stelekit.repository.RepositoryFactory?,
    ) {
        if (factoryForDelete != null && actorForDelete != null) {
            deleteStorageLocationRow(factoryForDelete, actorForDelete, graphId.value).onLeft {
                logger.warn("removeGraph: failed to delete storage_locations row for graph $graphId: ${it::class.simpleName}")
            }
        }
        try {
            factoryToClose?.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to close graph's factory while removing the last graph $graphId", e)
        }
    }

    /**
     * Deletes [graphId]'s `storage_locations` row, mirroring [writeStorageLocation]'s
     * actor-routed/`@DirectSqlWrite`-gated write pattern (ADR-001, Story 1.1.3).
     */
    private suspend fun deleteStorageLocationRow(
        factory: dev.stapler.stelekit.repository.RepositoryFactoryImpl,
        actor: DatabaseWriteActor,
        graphId: String,
    ): Either<DomainError, Unit> {
        val restricted = RestrictedDatabaseQueries(factory.steleDatabase().steleDatabaseQueries)
        return actor.execute(DatabaseWriteActor.Priority.HIGH) {
            try {
                @OptIn(DirectSqlWrite::class)
                restricted.deleteStorageLocation(graphId)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
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

/** The `storage_locations` table's per-kind nullable columns, derived from one [StorageLocation]. */
private data class StorageLocationRow(
    val kind: String,
    val treeUri: String?,
    val realPath: String?,
    val displayName: String?,
)

private val storageLocationLogger = Logger("GraphManager.StorageLocation")

/**
 * The inverse of [toStorageLocationRow] — reconstructs a [StorageLocation] from a persisted row.
 * A `null` here means either "no row" (handled by the caller before this is invoked) or genuine
 * corruption — an unrecognized `kind` or a `kind`/nullable-column mismatch — which is logged so it
 * isn't silently indistinguishable from the "no row" case at the call site.
 */
private fun Storage_locations.toStorageLocationModel(): StorageLocation? = when (kind) {
    "AppOwned" -> StorageLocation.AppOwned(graph_id)
    "SafFolder" -> tree_uri?.let { StorageLocation.SafFolder(graph_id, it) } ?: run {
        storageLocationLogger.warn("storage_locations row for $graph_id has kind=SafFolder but a null tree_uri — treating as corrupt")
        null
    }
    "DirectAccessFolder" -> real_path?.let { StorageLocation.DirectAccessFolder(graph_id, it) } ?: run {
        storageLocationLogger.warn("storage_locations row for $graph_id has kind=DirectAccessFolder but a null real_path — treating as corrupt")
        null
    }
    "HostFolder" -> display_name?.let { StorageLocation.HostFolder(graph_id, it) } ?: run {
        storageLocationLogger.warn("storage_locations row for $graph_id has kind=HostFolder but a null display_name — treating as corrupt")
        null
    }
    else -> {
        storageLocationLogger.warn("storage_locations row for $graph_id has unrecognized kind='$kind' — treating as corrupt")
        null
    }
}

private fun StorageLocation.toStorageLocationRow(): StorageLocationRow = when (this) {
    is StorageLocation.AppOwned -> StorageLocationRow(kind = "AppOwned", treeUri = null, realPath = null, displayName = null)
    is StorageLocation.SafFolder -> StorageLocationRow(kind = "SafFolder", treeUri = treeUri, realPath = null, displayName = null)
    is StorageLocation.DirectAccessFolder -> StorageLocationRow(kind = "DirectAccessFolder", treeUri = null, realPath = realPath, displayName = null)
    is StorageLocation.HostFolder -> StorageLocationRow(kind = "HostFolder", treeUri = null, realPath = null, displayName = displayName)
}
