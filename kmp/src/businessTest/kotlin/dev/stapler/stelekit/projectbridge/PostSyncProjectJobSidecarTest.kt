// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.jobs.InProcessJobs
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 2.5: the post-sync job reconciles each project's sidecar before it collects anything. */
class PostSyncProjectJobSidecarTest {
    @Test
    fun run_should_ReconcileTheSidecar_BeforeCollecting() = runTest {
        val device = Device()
        device.repos.projects.replaceProjectContents(SketchTestSupport.P, SketchTestSupport.bundle()).getOrNull()
        val seenAtCollect = mutableListOf<Boolean>()
        val collector = object : ProjectSourceCollector() {
            override suspend fun collect(): CollectedSources {
                seenAtCollect += device.fs.fileExists(sidecarPath("proj-0a1b2c", "project-model.json"))
                return CollectedSources()
            }
        }
        val settings = BridgeMapSettings().also { enableBridge(it) }
        val deps = bridgeDeps(
            settings, InProcessJobs(), router = { newBridgeTestRouter() },
            sketchRepositories = device.repos, sidecarStore = device.store,
        )

        val outcome = PostSyncProjectJob(deps, collector).run(BRIDGE_GRAPH) { false }

        assertIs<PostSyncRunOutcome.Completed>(outcome)
        assertEquals(listOf(true), seenAtCollect)
    }

    @Test
    fun run_should_StillComplete_WhenNoStoreIsWired() = runTest {
        val settings = BridgeMapSettings().also { enableBridge(it) }
        val outcome = PostSyncProjectJob(bridgeDeps(settings, InProcessJobs(), router = { newBridgeTestRouter() })).run(BRIDGE_GRAPH) { false }
        assertTrue(outcome is PostSyncRunOutcome.Completed)
    }
}
