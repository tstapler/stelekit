// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.browser

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.git.GitHostAdapter
import dev.stapler.stelekit.git.WasmGitRepository
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.GitHostConfig
import dev.stapler.stelekit.git.resolve
import dev.stapler.stelekit.platform.DemoFileSystem
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.sync.WasmSectionSyncService
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.model.DEMO_GRAPH_ID
import dev.stapler.stelekit.service.WasmMediaAttachmentService
import dev.stapler.stelekit.ui.StelekitApp
import dev.stapler.stelekit.ui.components.settings.ReconciliationUiState
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private fun markSteleKitReady(): Unit = js("window.__stelekit_ready = true")
private fun markGraphDialogCapable(capable: Boolean): Unit = js("window.__stelekit_native_graph_picker = capable")
private fun markDriverBackend(backend: String): Unit = js("window.__stelekit_driver_backend = backend")

// Story 5.1.3: `beforeunload` warning gated on PlatformFileSystem.dirtyFileCountFlow.
//
// The beforeunload callback is a plain JS event handler — it cannot suspend to read a Kotlin
// StateFlow directly — so [setShouldWarnMirror] keeps a JS-global boolean mirror current every
// time `dirtyFileCountFlow` emits (see the collector in main() below), computed via
// [shouldWarnOnUnload] so the exact same gating decision the unit test exercises is what actually
// runs in production. The listener installed by [registerBeforeUnloadWarning] reads that mirror
// synchronously when the event fires. This is the *only* place the dirty count is mirrored; the
// mirror is written from, and only from, `dirtyFileCountFlow`, so it cannot drift from the same
// flow Surface 1's badge already reads.

/**
 * Pure gating decision for the `beforeunload` warning (Task 5.1.3c). Kept as a standalone,
 * side-effect-free function so it is unambiguous and reviewable in isolation; `Main.kt` lives in
 * wasmJsMain and cannot be imported from `commonTest`, so `WasmGitWriteServiceAlgorithmsTest.kt`
 * re-verifies this exact one-line contract via a pure-Kotlin double, following the Epic 6.1
 * precedent for wasmJsMain-only orchestration logic.
 */
internal fun shouldWarnOnUnload(dirtyCount: Int): Boolean = dirtyCount > 0

private fun setShouldWarnMirror(shouldWarn: Boolean): Unit = js("window.__stelekit_should_warn = shouldWarn")

private fun registerBeforeUnloadWarning(): Unit = js(
    """
    (function() {
        window.addEventListener("beforeunload", function(event) {
            if (window.__stelekit_should_warn) {
                event.preventDefault();
                event.returnValue = "";
            }
        });
    })()
    """
)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val scope = MainScope()
    scope.launch(CoroutineExceptionHandler { _, throwable ->
        println("[SteleKit] Fatal startup error: ${throwable.message}")
        // ComposeViewport will not be mounted — the loading overlay remains visible
    }) {
        // Allow E2E tests to open a named OPFS graph via localStorage override.
        // Tests set window.localStorage['__stelekit_test_graph'] = 'name' before loading.
        val graphId = localStorage.getItem("__stelekit_test_graph") ?: "default"
        val opfsGraphPath = "/stelekit/$graphId"

        val opfsFileSystem = PlatformFileSystem()

        // Wire GitHub config before preload() so the lazy GitHub-fetch fallback in
        // PlatformFileSystem.readFileSuspend() has credentials available for returning
        // users whose OPFS cache may already contain stale entries.
        val ghSettings = PlatformSettings()
        val ghOwner = ghSettings.getString("githubOwner", "")
        val ghRepo = ghSettings.getString("githubRepo", "")
        val ghBranch = ghSettings.getString("githubBranch", "main")
        val ghToken = ghSettings.getString("githubToken", "").ifEmpty { null }
        PlatformFileSystem.githubOwner = ghOwner
        PlatformFileSystem.githubRepo = ghRepo
        PlatformFileSystem.githubBranch = ghBranch
        PlatformFileSystem.githubToken = ghToken
        WasmSectionSyncService.githubOwner = ghOwner
        WasmSectionSyncService.githubRepo = ghRepo
        WasmSectionSyncService.githubBranch = ghBranch
        WasmSectionSyncService.githubToken = ghToken
        WasmSectionSyncService.graphId = graphId

        // Story 4.3.1: shared configResolver for the write engine (WasmGitRepository), built from
        // the same PlatformFileSystem.githubOwner/githubRepo/githubToken companion fields the read
        // path (readFileSuspend() above, WasmSectionSyncService) already trusts — one credential
        // source for both read and write, per GitHostAdapter's shared-adapter design (Epic 1.1).
        // GitConfig itself carries no raw remote URL (see GitConfigRepository's KDoc), so the URL is
        // synthesized from owner/repo; null when no GitHub repo is configured yet (git sync unset up).
        val configResolver: suspend (GitConfig) -> GitHostConfig? = resolver@{ config ->
            val owner = PlatformFileSystem.githubOwner
            val repo = PlatformFileSystem.githubRepo
            if (owner.isEmpty() || repo.isEmpty()) return@resolver null
            val remoteUrl = "https://github.com/$owner/$repo"
            GitHostAdapter.resolve(config, remoteUrl, PlatformFileSystem.githubToken ?: "")
        }
        val wasmGitRepository = WasmGitRepository.withDefaultClient(opfsFileSystem, configResolver)

        // Story 5.1.3: warn before closing/navigating away while there are unsynced changes.
        // Registered once at startup; gated at fire-time on a JS-mirrored copy of
        // shouldWarnOnUnload(dirtyFileCountFlow.value) (see setShouldWarnMirror's comment above)
        // kept current by this collector for the lifetime of the page — same flow Surface 1's
        // badge already reads, no second "hasUnsavedChanges"-shaped field.
        registerBeforeUnloadWarning()
        scope.launch {
            opfsFileSystem.dirtyFileCountFlow.collect { count -> setShouldWarnMirror(shouldWarnOnUnload(count)) }
        }

        // preload() must run after GitHub config is wired; directoryExists() requires preload().
        opfsFileSystem.preload(opfsGraphPath)

        // Epic 2.2 (Task 2.2.1c): silently resume a previously-connected host directory, if any —
        // its own sequential startup step, matching this function's existing "config wiring →
        // preload → driver → ..." step ordering. A no-op (resolves to NotApplicable) for the vast
        // majority of users who have never connected a host directory.
        val hostAccessState = opfsFileSystem.hostDirectorySync.reconnectHostDirectory(graphId)
        println("[SteleKit] reconnectHostDirectory('$graphId'): $hostAccessState")

        val isNewUser = !opfsFileSystem.directoryExists(opfsGraphPath)

        val driverFactory = DriverFactory()
        var useDemoFallback = false
        val backend = try {
            val driver = driverFactory.createDriverAsync(graphId)
            if (driver.actualBackend == "memory") {
                // OPFS VFS unavailable — SQLite in-memory still works, show demo content.
                println("[SteleKit] OPFS worker fell back to :memory: — loading demo graph")
                markDriverBackend("memory")
                useDemoFallback = true
                GraphBackend.SQLDELIGHT
            } else {
                markDriverBackend("opfs")
                GraphBackend.SQLDELIGHT
            }
        } catch (e: Throwable) {
            println("[SteleKit] SQLite driver init failed, loading demo graph: ${e.message}")
            markDriverBackend("memory")
            useDemoFallback = true
            GraphBackend.IN_MEMORY
        }

        val fileSystem: FileSystem
        val graphPath: String
        if (useDemoFallback) {
            fileSystem = DemoFileSystem()
            graphPath = fileSystem.getDefaultGraphPath()
            // Clear persisted registry so GraphManager starts fresh in memory-only mode.
            val settings = PlatformSettings()
            settings.putBoolean("onboardingCompleted", true)
            localStorage.removeItem("graph_registry")
            localStorage.removeItem("cached_graph_path")
        } else {
            fileSystem = opfsFileSystem
            graphPath = opfsGraphPath
            // lastGraphPath feeds the single-graph → multi-graph migration in loadRegistry().
            // Only write it for returning users; new users start fresh and have no old registry
            // to migrate, so leaving lastGraphPath unset is correct.
            if (!isNewUser) {
                PlatformSettings().putString("lastGraphPath", graphPath)
            }
        }

        val graphManager = GraphManager(
            platformSettings = PlatformSettings(),
            driverFactory = driverFactory,
            fileSystem = fileSystem,
            defaultBackend = backend,
        )

        // Always register the demo graph in the switcher.
        // New users and fallback mode start on it so the app isn't blank on first load.
        graphManager.addDemoGraph()
        if (isNewUser || useDemoFallback) {
            graphManager.switchGraph(DEMO_GRAPH_ID)
        }

        markSteleKitReady()
        markGraphDialogCapable(dev.stapler.stelekit.platform.showDirectoryPickerSupported())

        ComposeViewport(document.body!!) {
            StelekitApp(
                fileSystem = fileSystem,
                graphPath = graphPath,
                graphManager = graphManager,
                attachmentService = WasmMediaAttachmentService(fileSystem),
                gitRepository = wasmGitRepository,
                localChangesCountFlow = opfsFileSystem.dirtyFileCountFlow,
                hostAccessStateFlow = opfsFileSystem.hostDirectorySync.hostAccessStateFlow,
                hostWritePendingCountFlow = opfsFileSystem.hostDirectorySync.hostWritePendingCountFlow,
                hostWriteStuckFlow = opfsFileSystem.hostDirectorySync.hostWriteStuckFlow,
                onReconnectHostDirectory = {
                    scope.launch { opfsFileSystem.hostDirectorySync.requestHostDirectoryAccess(graphId) }
                },
                // Task 3.1.1c: "Enable live folder sync" — wired the same way the badge's flows
                // above are, straight to HostDirectorySync.connectHostDirectory. Its own internal
                // showDirectoryPicker → runHostReconciliation sequence already leaves hostDirHandle
                // unset on any failure, so a non-Granted result here always means "nothing changed."
                // lastReconciliationSummary is stashed by runHostReconciliation on the same call,
                // so it is always fresh when result == Granted.
                onConnectHostDirectory = connectHostDirectory@{
                    val result = opfsFileSystem.hostDirectorySync.connectHostDirectory(opfsGraphPath)
                    val summary = opfsFileSystem.hostDirectorySync.lastReconciliationSummary
                    if (result != HostAccessState.Granted || summary == null) {
                        return@connectHostDirectory ReconciliationUiState.Failed(
                            "Couldn't finish comparing your files"
                        )
                    }
                    ReconciliationUiState.Summary(
                        identical = summary.identical,
                        hostChangedConflict = summary.hostChangedConflict,
                        hostOnlyNew = summary.hostOnlyNew,
                        browserOnlyNeedsPush = summary.browserOnlyNeedsPush,
                    )
                },
            )
        }
    }
}
