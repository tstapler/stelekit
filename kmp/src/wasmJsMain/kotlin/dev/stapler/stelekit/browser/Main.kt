// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.browser

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.DemoFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.StelekitApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    CanvasBasedWindow(canvasElementId = "ComposeTarget") {
        val scope = rememberCoroutineScope()
        val demoFileSystem = remember { DemoFileSystem() }
        val graphManager = remember(scope) {
            GraphManager(
                platformSettings = PlatformSettings(),
                driverFactory = DriverFactory(),
                fileSystem = demoFileSystem,
                coroutineScope = scope,
                defaultBackend = GraphBackend.IN_MEMORY,
            )
        }
        StelekitApp(
            fileSystem = demoFileSystem,
            graphPath = "/demo",
            graphManager = graphManager,
        )
    }
}
