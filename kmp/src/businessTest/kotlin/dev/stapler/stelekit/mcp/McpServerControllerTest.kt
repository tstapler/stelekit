// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.StelekitViewModelTestHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Toggle-driven lifecycle of [McpServerController] with no graph open, so no socket is ever bound
 * (the "no graph open, never starts" rule). The unconfined dispatcher makes collection synchronous.
 */
class McpServerControllerTest {
    private class MapSettings : Settings {
        private val values = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = values[key] ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun containsKey(key: String) = key in values
    }

    private val harness = StelekitViewModelTestHarness()
    private val viewModel = harness.viewModel
    private val manager = GraphManager(MapSettings(), DriverFactory(), StubFileSystem(), defaultBackend = GraphBackend.IN_MEMORY)
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val controller = McpServerController(viewModel, manager, scope)

    @AfterTest
    fun tearDown() {
        controller.shutdown()
        scope.cancel()
        harness.scope.cancel()
        manager.shutdown()
    }

    @Test
    fun startWithToggleOff_clearsAStaleBindFailureBanner() {
        viewModel.setMcpBindFailureMessage("address in use")

        controller.start()

        assertNull(viewModel.uiState.value.mcpBindFailureMessage)
    }

    @Test
    fun enablingWithNoOpenGraph_neitherStartsNorReportsAFailure() {
        controller.start()

        viewModel.setMcpServerEnabled(true)

        assertNull(manager.getActiveGraphId())
        assertNull(viewModel.uiState.value.mcpBindFailureMessage)
        assertEquals(true, viewModel.uiState.value.mcpServerEnabled)
    }

    @Test
    fun retryWhileDisabled_leavesTheBannerAlone() {
        controller.start()
        viewModel.setMcpBindFailureMessage("address in use")

        viewModel.requestMcpRetry()

        assertEquals("address in use", viewModel.uiState.value.mcpBindFailureMessage)
    }

    @Test
    fun disablingTheToggle_clearsTheBanner() {
        controller.start()
        viewModel.setMcpServerEnabled(true)
        viewModel.setMcpBindFailureMessage("address in use")

        viewModel.setMcpServerEnabled(false)

        assertNull(viewModel.uiState.value.mcpBindFailureMessage)
    }

    @Test
    fun afterShutdown_toggleChangesNoLongerReachTheController() {
        controller.start()
        controller.shutdown()
        viewModel.setMcpBindFailureMessage("address in use")

        viewModel.setMcpServerEnabled(true)
        viewModel.setMcpServerEnabled(false)

        assertEquals("address in use", viewModel.uiState.value.mcpBindFailureMessage)
    }
}
