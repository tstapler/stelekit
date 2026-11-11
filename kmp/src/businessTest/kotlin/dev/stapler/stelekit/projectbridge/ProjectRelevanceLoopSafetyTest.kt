// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.fuseObservations
import dev.stapler.stelekit.jobs.InProcessJobs
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.WriteOutcome
import dev.stapler.stelekit.merge.WriteRefusedReason
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story 5.7b: relevance by content hash, and the job never re-triggers itself from its own writes. */
class ProjectRelevanceLoopSafetyTest {
    private val digestInput = DigestInput(
        "proj-sample",
        fuseObservations(FusionInput(ClosetFusion.observations(), ClosetFusion.constraints()), ClosetFusion.registry).fold({ error("$it") }, { it }),
        ClosetFusion.registry,
        listOf(SumCheck("Zones sum to opening", 1, "niche.width", ClosetFusion.zones)),
    )

    private fun sources(version: Int) = CollectedSources(boundPages = listOf(BoundPageRef("Source $version", "pages/s$version.md")))

    private class Rig(version: () -> Int, private val sources: (Int) -> CollectedSources, private val refuse: () -> Boolean = { false }) {
        var writes = 0
        private var stored: MergePage? = null
        val settings = BridgeMapSettings().also { enableBridge(it) }
        private val writer = DigestWriter(
            replace = { _, _, page ->
                when {
                    refuse() -> DomainError.MergeError.WriteRefused(WriteRefusedReason.Conflicted("p")).left()
                    page == stored -> WriteOutcome.Unchanged.right()
                    else -> { writes++; stored = page; WriteOutcome.Updated("p", "h", 1).right() }
                }
            },
            readBack = { _, _ -> stored.right() },
            hasWriter = { true },
        )
        private val collector = object : ProjectSourceCollector() {
            override suspend fun collect() = sources(version())
        }
        var input: DigestInput? = null
        val job = PostSyncProjectJob(
            bridgeDeps(settings, InProcessJobs(), router = { newBridgeTestRouter() }),
            collector, digestWriter = writer, digestInput = { input },
        )
        suspend fun sync() = job.run(BRIDGE_GRAPH) { false }
    }

    @Test
    fun hasherIsOrderIndependentAndDetectsChange() {
        val a = CollectedSources(boundPages = listOf(BoundPageRef("A", "a"), BoundPageRef("B", "b")))
        val b = CollectedSources(boundPages = listOf(BoundPageRef("B", "b"), BoundPageRef("A", "a")))
        assertEquals(ProjectRelevanceHasher.hash(a), ProjectRelevanceHasher.hash(b))
        assertNotEquals(ProjectRelevanceHasher.hash(a), ProjectRelevanceHasher.hash(CollectedSources()))
        assertTrue(ProjectRelevanceHasher.isUnchanged("x", "x"))
        assertTrue(!ProjectRelevanceHasher.isUnchanged("x", ""))
        assertTrue(!ProjectRelevanceHasher.isUnchanged("x", "y"))
    }

    @Test
    fun secondSyncWithUnchangedSourcesRunsNothing() = runTest {
        val rig = Rig({ 1 }, ::sources).also { it.input = digestInput }
        assertIs<PostSyncRunOutcome.Completed>(rig.sync())
        assertEquals(1, rig.writes)
        assertEquals(PostSyncRunOutcome.NotRelevant, rig.sync())
        assertEquals(1, rig.writes)
    }

    @Test
    fun aRefusedWriteIsRetriedOnTheNextSync() = runTest {
        var refuse = true
        val rig = Rig({ 1 }, ::sources, { refuse }).also { it.input = digestInput }
        assertIs<PostSyncRunOutcome.DigestNotWritten>(rig.sync())
        refuse = false
        assertIs<PostSyncRunOutcome.Completed>(rig.sync())
        assertEquals(1, rig.writes)
    }

    @Test
    fun randomSyncTimelinesWriteOncePerSourceChangeAtMost() = runTest {
        for (seed in 1..25) {
            val random = Random(seed)
            var version = 0
            val rig = Rig({ version }, ::sources)
            var changes = 0
            repeat(40) { round ->
                if (round > 0 && random.nextInt(100) < 35) { version++; changes++ }
                rig.input = digestInput.copy(slug = "proj-$version")
                val outcome = rig.sync()
                assertTrue(outcome is PostSyncRunOutcome.Completed || outcome is PostSyncRunOutcome.NotRelevant, "seed $seed: $outcome")
            }
            // One write for the first run plus one per source change; the job's own writes never add another.
            assertEquals(1 + changes, rig.writes, "seed $seed")
        }
    }
}
