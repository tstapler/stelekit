// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.browser

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.StelekitApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private fun markSteleKitReady(): Unit = js("window.__stelekit_ready = true")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val scope = MainScope()
    scope.launch {
        val graphId = "default"
        val graphPath = "/stelekit/$graphId"

        val fileSystem = PlatformFileSystem()
        fileSystem.preload(graphPath)

        val driverFactory = DriverFactory()
        val backend = try {
            val driver = driverFactory.createDriverAsync(graphId)
            if (driver.actualBackend == "memory") {
                println("[SteleKit] OPFS worker fell back to :memory: — data will not persist")
                GraphBackend.IN_MEMORY
            } else {
                GraphBackend.SQLDELIGHT
            }
        } catch (e: Throwable) {
            println("[SteleKit] OPFS driver init failed, using IN_MEMORY: ${e.message}")
            GraphBackend.IN_MEMORY
        }

        val graphManager = GraphManager(
            platformSettings = PlatformSettings(),
            driverFactory = driverFactory,
            fileSystem = fileSystem,
            defaultBackend = backend,
        )

        markSteleKitReady()

        CanvasBasedWindow(canvasElementId = "ComposeTarget") {
            StelekitApp(
                fileSystem = fileSystem,
                graphPath = graphPath,
                graphManager = graphManager,
            )
        }
    }
}
