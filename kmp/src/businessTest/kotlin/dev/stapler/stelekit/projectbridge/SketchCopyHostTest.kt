// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.merge.SketchCopyError
import dev.stapler.stelekit.merge.SketchOpenTarget
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** What the Sketch screen's "Copy to graph..." offers: the other graphs, nothing without a router, and Open switching to the target. */
class SketchCopyHostTest {
    private val open = GraphId("aaaaaaaaaaaaaaaa")
    private val other = GraphId("bbbbbbbbbbbbbbbb")

    private fun info(id: GraphId, name: String, demo: Boolean = false) = GraphInfo(id, "/g/$name", name, 0L, isDemo = demo)

    private val registry = GraphRegistry(open, listOf(info(open, "Open"), info(other, "Other"), info(GraphId("cccccccccccccccc"), "Demo", demo = true)))

    private fun host(switched: MutableList<GraphId> = mutableListOf()) =
        SketchCopyHost({ null }, { null }, StubFileSystem(), { "/g/Open" }, { registry }, { switched += it })

    @Test
    fun targets_should_BeTheRegisteredGraphsOtherThanTheOpenOneAndTheDemo() {
        assertEquals(listOf(SketchCopyTarget(other, "Other")), host().targets())
    }

    @Test
    fun available_should_BeFalse_When_NoRouterOrStoreExists() {
        assertFalse(host().available())
    }

    @Test
    fun copy_should_ReportUnavailableStorageAndWriteNothing_When_NoRouterExists() = runTest {
        assertIs<SketchCopyError.V2StorageUnavailable>(host().copy("proj-1", other).swap().getOrNull())
    }

    @Test
    fun open_should_SwitchToTheGraphThatNowHoldsTheSketch() {
        val switched = mutableListOf<GraphId>()
        host(switched).open(SketchOpenTarget(other, "proj-1"))
        assertEquals(listOf(other), switched)
    }
}
