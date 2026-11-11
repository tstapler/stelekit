// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.fuseObservations
import dev.stapler.stelekit.merge.ActiveTargetWriterContractTest.ActiveHarness
import dev.stapler.stelekit.merge.PageKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Story 5.7b: two devices syncing one repo converge on the same digest and then stop writing. */
class TwoDeviceConvergenceTest {
    private val title = "Sample Digest"
    private val graph = "graph-a"
    private val key = PageKey(title)

    private val input = DigestInput(
        "proj-sample",
        fuseObservations(FusionInput(ClosetFusion.observations(), ClosetFusion.constraints()), ClosetFusion.registry).fold({ error("$it") }, { it }),
        ClosetFusion.registry,
        listOf(SumCheck("Zones sum to opening", 1, "niche.width", ClosetFusion.zones)),
    )

    private fun writerOver(h: ActiveHarness) = DigestWriter(
        replace = { _, k, page -> h.writer.replaceOwnedPage(k, page, null) },
        readBack = { _, k -> h.writer.readExisting(k) },
        hasWriter = { true },
    )

    /** The fake git remote: copies the file from [from] to [to] the way a pull plus the loader's import would. */
    private suspend fun sync(from: ActiveHarness, to: ActiveHarness) {
        from.fileText(key)?.let { to.seed(key, it) }
    }

    @Test
    fun deviceBImportsTheDigestAndWritesNothing() = runBlocking<Unit> {
        val a = ActiveHarness()
        val b = ActiveHarness()
        try {
            val wa = writerOver(a)
            val wb = writerOver(b)
            assertIs<DigestWriteResult.Written>(wa.write(graph, title, input))
            sync(a, b)
            val pulled = assertNotNull(b.fileText(key))
            assertEquals(DigestWriteResult.Unchanged, wb.write(graph, title, input))
            assertEquals(pulled, b.fileText(key))
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun afterAFewRoundsNeitherDeviceWritesAgain() = runBlocking<Unit> {
        val a = ActiveHarness()
        val b = ActiveHarness()
        try {
            val wa = writerOver(a)
            val wb = writerOver(b)
            val results = mutableListOf<Pair<DigestWriteResult, DigestWriteResult>>()
            repeat(4) {
                val ra = wa.write(graph, title, input)
                sync(a, b)
                val rb = wb.write(graph, title, input)
                sync(b, a)
                results += ra to rb
            }
            assertIs<DigestWriteResult.Written>(results.first().first)
            results.drop(1).forEach { (ra, rb) ->
                assertEquals(DigestWriteResult.Unchanged, ra)
                assertEquals(DigestWriteResult.Unchanged, rb)
            }
            assertEquals(a.fileText(key), b.fileText(key))
        } finally {
            a.close()
            b.close()
        }
    }
}
