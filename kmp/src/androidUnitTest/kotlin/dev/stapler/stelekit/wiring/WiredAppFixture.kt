// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import android.content.Context
import androidx.compose.runtime.Composable
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.git.BackgroundSyncSchedulerFactory
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.ui.GraphContentDeps
import dev.stapler.stelekit.ui.GraphContentGitSyncStack
import dev.stapler.stelekit.ui.NotificationManager
import dev.stapler.stelekit.ui.StelekitAppPlatformIntegrations
import dev.stapler.stelekit.ui.rememberGraphContentGitSyncStack
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import java.io.File

/**
 * Composes the real `GraphContent*Setup` stack files over a [GraphManager] with fake platform ports
 * (Glossary `WiredAppFixture`). Real `platformCaps()` / device-factory root functions are exercised
 * separately by their own root-function tests; this fixture proves the stack files wire.
 *
 * Call [android] from `runBlocking` before composing; the caller owns the compose rule and calls
 * [close] in `@After`.
 */
class WiredAppFixture private constructor(
    val graphManager: GraphManager,
    val repos: RepositorySet,
    val ports: FakePlatformPorts,
    private val dataDir: File,
    schedulerFactory: BackgroundSyncSchedulerFactory,
) {
    internal val deps = GraphContentDeps(
        repos = repos,
        fileSystem = ports.fileSystem,
        platformSettings = ports.settings,
        graphManager = graphManager,
        notificationManager = NotificationManager(),
        platformIntegrations = StelekitAppPlatformIntegrations(
            backgroundSyncSchedulerFactory = schedulerFactory,
            platformCaps = dev.stapler.stelekit.asset.pipeline.PlatformCaps.NONE,
            gitRepository = ports.gitRepository,
        ),
    )

    /** Composes the vault, graph-IO and sync stacks exactly as `GraphContent` does and returns the sync bundle. */
    @Composable
    internal fun composeGitSyncStack(): GraphContentGitSyncStack {
        val vault = rememberGraphContentVaultSetup(graphManager, ports.fileSystem, null)
        val io = rememberGraphContentGraphIoStack(
            deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
            dev.stapler.stelekit.logging.Logger("WiredAppFixture"),
        )
        return rememberGraphContentGitSyncStack(deps, io, vault.vaultCredentialStore)
    }

    suspend fun close() {
        graphManager.shutdown()
        dataDir.deleteRecursively()
    }

    companion object {
        /**
         * Opens a graph at [graphPath] on the in-memory repository backend. Real SQLDelight is not
         * possible under Robolectric: its native SQLite has no FTS5 module (`no such module: fts5`,
         * verified on SDK 29 and 34 when this fixture first tried it), so the schema cannot be
         * created. `GraphManager.createGitConfigRepository()` therefore returns null and the sync
         * stack yields no `GitSyncService` here. Behavior that needs a service or a database is
         * asserted by `DesktopWiredAppFixture` (jvmTest, real SQLDelight); Android-driver behavior
         * belongs to `androidInstrumentedTest`.
         */
        suspend fun android(
            context: Context,
            dataDir: File,
            ports: FakePlatformPorts = FakePlatformPorts(),
            graphPath: String = "/test/wired-graph",
            schedulerFactory: BackgroundSyncSchedulerFactory = BackgroundSyncSchedulerFactory.None,
        ): WiredAppFixture {
            SteleKitContext.init(context)
            DriverFactory.setContext(context)
            System.setProperty("stelekit.devDataDir", dataDir.absolutePath)
            val manager = GraphManager(
                platformSettings = ports.settings,
                driverFactory = DriverFactory(),
                fileSystem = ports.fileSystem,
                defaultBackend = GraphBackend.IN_MEMORY,
            )
            return WiredAppFixture(manager, manager.openGraph(graphPath), ports, dataDir, schedulerFactory)
        }
    }
}
