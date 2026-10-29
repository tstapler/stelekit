// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.wiring

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.git.BackgroundSyncSchedulerFactory
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.ui.GitSyncServiceSeams
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.ui.GraphContentDeps
import dev.stapler.stelekit.ui.GraphContentGitSyncStack
import dev.stapler.stelekit.ui.NotificationManager
import dev.stapler.stelekit.ui.StelekitAppPlatformIntegrations
import dev.stapler.stelekit.ui.rememberGraphContentGitSyncStack
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** A Compose applier with no nodes: composition runs, nothing is drawn, no X11 display is needed. */
private class NoNodeApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}

/** Runs a composition on the Compose runtime alone (no Skia, no window), driven by an explicit frame clock. */
class HeadlessComposition : AutoCloseable {
    private val clock = BroadcastFrameClock()
    private val scope = CoroutineScope(Dispatchers.Unconfined + clock)
    private val recomposer = Recomposer(scope.coroutineContext)
    private var composition: Composition? = null
    private var frame = 0L

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
    }

    fun setContent(content: @Composable () -> Unit) {
        composition = Composition(NoNodeApplier(), recomposer).also { it.setContent(content) }
        advance()
    }

    /**
     * Delivers one frame so pending recompositions and effects run. State written from another
     * thread (a collected Flow) only invalidates the composition once apply notifications are sent;
     * a real window does that through Compose's global snapshot manager, which this headless host lacks.
     */
    fun advance() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        clock.sendFrame(frame++)
    }

    /** Leaves composition, running every `DisposableEffect` `onDispose`. */
    fun dispose() {
        composition?.dispose()
        composition = null
    }

    override fun close() {
        dispose()
        recomposer.close()
        scope.cancel()
    }
}

/**
 * Desktop variant of the wired-stack fixture (Glossary `WiredAppFixture`): the same
 * `GraphContent*Setup` stack files over a real SQLDelight database (sqlite-jdbc) with fake platform
 * ports. The stack is composed without pixels, so no display is required.
 */
class DesktopWiredAppFixture private constructor(
    val graphManager: GraphManager,
    val repos: RepositorySet,
    val ports: FakePlatformPorts,
    private val dataDir: File,
    seams: GitSyncServiceSeams,
    busyCounter: GitSyncBusyCounter?,
) {
    internal val deps = GraphContentDeps(
        repos = repos,
        fileSystem = ports.fileSystem,
        platformSettings = ports.settings,
        graphManager = graphManager,
        notificationManager = NotificationManager(),
        platformIntegrations = StelekitAppPlatformIntegrations(
            backgroundSyncSchedulerFactory = BackgroundSyncSchedulerFactory.None,
            gitRepository = ports.gitRepository,
            gitSyncBusyCounter = busyCounter,
        ),
        gitSyncSeams = seams,
    )

    /** Composes the vault, graph-IO and sync stacks exactly as `GraphContent` does and returns the sync bundle. */
    @Composable
    internal fun composeGitSyncStack(): GraphContentGitSyncStack {
        val vault = rememberGraphContentVaultSetup(graphManager, ports.fileSystem, null)
        val io = rememberGraphContentGraphIoStack(
            deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
            dev.stapler.stelekit.logging.Logger("DesktopWiredAppFixture"),
        )
        return rememberGraphContentGitSyncStack(deps, io, vault.vaultCredentialStore)
    }

    suspend fun close() {
        graphManager.shutdown()
        dataDir.deleteRecursively()
        System.clearProperty("stelekit.devDataDir")
    }

    companion object {
        suspend fun desktop(
            dataDir: File,
            ports: FakePlatformPorts = FakePlatformPorts(),
            graphPath: String = "/test/wired-graph",
            seams: GitSyncServiceSeams = GitSyncServiceSeams(),
            busyCounter: GitSyncBusyCounter? = null,
        ): DesktopWiredAppFixture {
            System.setProperty("stelekit.devDataDir", dataDir.absolutePath)
            val manager = GraphManager(
                platformSettings = ports.settings,
                driverFactory = DriverFactory(),
                fileSystem = ports.fileSystem,
                defaultBackend = GraphBackend.SQLDELIGHT,
            )
            return DesktopWiredAppFixture(manager, manager.openGraph(graphPath), ports, dataDir, seams, busyCounter)
        }
    }
}
