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
        opfsFileSystem.preload(opfsGraphPath)

        // On first visit the OPFS graph is empty — seed it with demo content so new users
        // see example pages rather than a blank graph. Skipped on subsequent loads once
        // preload() finds existing files (directoryExists returns true).
        if (!opfsFileSystem.directoryExists(opfsGraphPath)) {
            val demo = DemoFileSystem()
            for (subdir in listOf("pages", "journals")) {
                for (file in demo.listFiles("/demo/$subdir")) {
                    val content = demo.readFile("/demo/$subdir/$file") ?: continue
                    opfsFileSystem.writeFile("$opfsGraphPath/$subdir/$file", content)
                }
            }
            println("[SteleKit] Seeded demo content into $opfsGraphPath")
        }

        // Wire GitHub config for section sync and lazy content fetching.
        // Keys stored in localStorage by the web host (e.g. embed script).
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
            // Seed settings so the viewmodel initializes to the demo path on first read,
            // and clear the persisted graph registry so the GraphManager starts fresh.
            val settings = PlatformSettings()
            settings.putString("lastGraphPath", graphPath)
            settings.putBoolean("onboardingCompleted", true)
            localStorage.removeItem("graph_registry")
            localStorage.removeItem("cached_graph_path")
        } else {
            fileSystem = opfsFileSystem
            graphPath = opfsGraphPath
            // Ensure OPFS path overwrites any stale demo fallback stored from a previous session
            PlatformSettings().putString("lastGraphPath", graphPath)
        }

        val graphManager = GraphManager(
            platformSettings = PlatformSettings(),
            driverFactory = driverFactory,
            fileSystem = fileSystem,
            defaultBackend = backend,
        )

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
