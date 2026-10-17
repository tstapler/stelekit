// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Callers that need the notes folder ask the manager for it, and never get the repo root by mistake. */
class GraphManagerNotesPathTest {
    private val active = GraphId("active")
    private val other = GraphId("other")

    private fun manager(activeId: GraphId?): GraphManager {
        val graphs = listOf(
            GraphInfo(id = active, path = "/graphs/active", displayName = "active", addedAt = 0L, detectedRepoRoot = "/graphs/active", detectedWikiSubdir = "logseq", effectivePath = "/graphs/active/logseq"),
            GraphInfo(id = other, path = "/graphs/other", displayName = "other", addedAt = 0L),
        )
        val settings = StubSettings()
        settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = activeId, graphs = graphs)))
        return GraphManager(platformSettings = settings, driverFactory = DriverFactory(), fileSystem = StubGraphManagerFileSystem(), defaultBackend = GraphBackend.IN_MEMORY)
    }

    @Test
    fun getActiveNotesPath_should_BeTheNotesFolder_NotTheRepoRoot() {
        assertEquals(EffectiveNotesPath("/graphs/active/logseq"), manager(active).getActiveNotesPath())
    }

    @Test
    fun getActiveNotesPath_should_BeNull_When_NoGraphIsActive() {
        assertNull(manager(activeId = null).getActiveNotesPath())
    }

    @Test
    fun getNotesPath_should_ResolveAnyRegisteredGraph_AndFallBackToItsPathWithoutASubdir() {
        val m = manager(active)

        assertEquals(EffectiveNotesPath("/graphs/active/logseq"), m.getNotesPath(active))
        assertEquals(EffectiveNotesPath("/graphs/other"), m.getNotesPath(other))
        assertNull(m.getNotesPath(GraphId("missing")))
    }
}
