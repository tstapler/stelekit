// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import dev.stapler.stelekit.model.GraphId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for Story 4.1.5's spec-compliance gap: tapping a stuck/failed-sync
 * notification must deep-link to Git Setup Step 5 for the graph the notification names, not the
 * app's generic home/last screen. [resolveGitSetupDeepLinkAction] is the pure decision function
 * `MainActivity`'s deep-link `LaunchedEffect` calls — extracted (mirroring
 * [MainActivityGitRepositoryWiringTest]'s [buildGitRepository] precedent) so the branching can be
 * exercised without a live Activity/Compose tree. Plain JUnit — no Android API surface involved.
 */
class MainActivityGitSetupDeepLinkTest {

    @Test
    fun `no pending graph id means no action`() {
        val action = resolveGitSetupDeepLinkAction(
            pendingGraphId = null,
            activeGraphId = "graph-1",
            viewModelAvailable = true,
        )
        assertEquals(GitSetupDeepLinkAction.None, action)
    }

    @Test
    fun `pending graph id but ViewModel not ready yet means no action`() {
        val action = resolveGitSetupDeepLinkAction(
            pendingGraphId = "graph-1",
            activeGraphId = "graph-1",
            viewModelAvailable = false,
        )
        assertEquals(GitSetupDeepLinkAction.None, action)
    }

    @Test
    fun `pending graph differs from active graph triggers a switch`() {
        val action = resolveGitSetupDeepLinkAction(
            pendingGraphId = "graph-2",
            activeGraphId = "graph-1",
            viewModelAvailable = true,
        )
        assertEquals(GitSetupDeepLinkAction.SwitchGraph(GraphId("graph-2")), action)
    }

    @Test
    fun `pending graph already active and ViewModel ready opens Step 5`() {
        val action = resolveGitSetupDeepLinkAction(
            pendingGraphId = "graph-1",
            activeGraphId = "graph-1",
            viewModelAvailable = true,
        )
        assertEquals(GitSetupDeepLinkAction.OpenStep5, action)
    }

    @Test
    fun `no active graph yet (cold start) triggers a switch rather than opening Step 5 early`() {
        val action = resolveGitSetupDeepLinkAction(
            pendingGraphId = "graph-1",
            activeGraphId = null,
            viewModelAvailable = true,
        )
        assertEquals(GitSetupDeepLinkAction.SwitchGraph(GraphId("graph-1")), action)
    }
}
