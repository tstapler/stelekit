// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.ui.StelekitViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the current [McpServerController] for the desktop process. `StelekitApp` hands out a new
 * [StelekitViewModel] on every graph switch, so the controller (and its socket) is rebuilt each
 * time — the socket path is per graph, so this also moves the server to the newly active graph.
 */
class McpDesktopLifecycle {
    @Volatile private var graphManager: GraphManager? = null
    private var controller: McpServerController? = null
    private var latestViewModel: StelekitViewModel? = null

    @Synchronized
    fun onGraphManagerReady(gm: GraphManager) {
        graphManager = gm
        // A ViewModel that arrived first has no controller yet; build it now.
        if (controller == null) latestViewModel?.let { onViewModelReady(it) }
    }

    @Synchronized
    fun onViewModelReady(viewModel: StelekitViewModel) {
        latestViewModel = viewModel
        val gm = graphManager ?: return
        controller?.shutdown()
        controller = McpServerController(viewModel, gm, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            .also { it.start() }
    }

    @Synchronized
    fun shutdown() {
        controller?.shutdown()
        controller = null
    }
}
