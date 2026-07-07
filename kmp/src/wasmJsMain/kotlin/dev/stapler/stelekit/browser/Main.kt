// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.browser

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.DemoFileSystem
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.sync.WasmSectionSyncService
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.model.DEMO_GRAPH_ID
import dev.stapler.stelekit.service.WasmMediaAttachmentService
import dev.stapler.stelekit.ui.StelekitApp
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private fun markSteleKitReady(): Unit = js("window.__stelekit_ready = true")
private fun markGraphDialogCapable(capable: Boolean): Unit = js("window.__stelekit_native_graph_picker = capable")
private fun markDriverBackend(backend: String): Unit = js("window.__stelekit_driver_backend = backend")

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

        // preload() must run after GitHub config is wired; directoryExists() requires preload().
        opfsFileSystem.preload(opfsGraphPath)
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
            )
        }
    }
}
