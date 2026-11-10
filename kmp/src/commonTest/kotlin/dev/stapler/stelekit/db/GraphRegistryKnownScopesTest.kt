// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.jobs.schedule.KnownScopesResult
import dev.stapler.stelekit.jobs.schedule.MapSettings
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphRegistryKnownScopesTest {
    private fun graph(id: String, demo: Boolean = false) =
        GraphInfo(id = GraphId(id), path = "/g/$id", displayName = id, addedAt = 1L, isDemo = demo)

    private fun settingsWith(raw: String) = MapSettings().also { it.store["graph_registry"] = raw }

    private fun encoded(vararg graphs: GraphInfo) = Json.encodeToString(GraphRegistry(graphs = graphs.toList()))

    @Test
    fun read_should_returnKnownIds_When_registryHasRealGraphs() = runTest {
        val result = GraphRegistryKnownScopes(settingsWith(encoded(graph("g1"), graph("g2")))).read()
        assertEquals(KnownScopesResult.Known(setOf(TaskScope("g1"), TaskScope("g2"))), result)
    }

    @Test
    fun read_should_excludeDemoGraph_When_mixedWithRealGraph() = runTest {
        val result = GraphRegistryKnownScopes(settingsWith(encoded(graph("g1"), graph("d", demo = true)))).read()
        assertEquals(KnownScopesResult.Known(setOf(TaskScope("g1"))), result)
    }

    @Test
    fun read_should_returnUnknown_When_registryBlankEmptyDemoOnlyOrCorrupt() = runTest {
        assertEquals(KnownScopesResult.Unknown, GraphRegistryKnownScopes(MapSettings()).read())
        assertEquals(KnownScopesResult.Unknown, GraphRegistryKnownScopes(settingsWith(encoded())).read())
        assertEquals(KnownScopesResult.Unknown, GraphRegistryKnownScopes(settingsWith(encoded(graph("d", demo = true)))).read())
        assertEquals(KnownScopesResult.Unknown, GraphRegistryKnownScopes(settingsWith("{not json")).read())
    }

    @Test
    fun read_should_returnUnknownWithoutThrowing_When_settingsReadFails() = runTest {
        val settings = settingsWith(encoded(graph("g1"))).also { it.failReads = true }
        assertEquals(KnownScopesResult.Unknown, GraphRegistryKnownScopes(settings).read())
    }
}
