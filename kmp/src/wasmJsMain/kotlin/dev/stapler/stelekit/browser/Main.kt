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
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.StelekitApp
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private fun markSteleKitReady(): Unit = js("window.__stelekit_ready = true")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val scope = MainScope()
    scope.launch(CoroutineExceptionHandler { _, throwable ->
        println("[SteleKit] Fatal startup error: ${throwable.message}")
        // ComposeViewport will not be mounted — the loading overlay remains visible
    }) {
        val graphId = "default"
        val opfsGraphPath = "/stelekit/$graphId"

        val opfsFileSystem = PlatformFileSystem()
        opfsFileSystem.preload(opfsGraphPath)

        val driverFactory = DriverFactory()
        var useDemoFallback = false
        val backend = try {
            val driver = driverFactory.createDriverAsync(graphId)
            if (driver.actualBackend == "memory") {
                // OPFS VFS unavailable — SQLite in-memory still works, show demo content.
                println("[SteleKit] OPFS worker fell back to :memory: — loading demo graph")
                useDemoFallback = true
                GraphBackend.SQLDELIGHT
            } else {
                GraphBackend.SQLDELIGHT
            }
        } catch (e: Throwable) {
            println("[SteleKit] SQLite driver init failed, loading demo graph: ${e.message}")
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

        ComposeViewport(document.body!!) {
            StelekitApp(
                fileSystem = fileSystem,
                graphPath = graphPath,
                graphManager = graphManager,
            )
        }
    }
}
