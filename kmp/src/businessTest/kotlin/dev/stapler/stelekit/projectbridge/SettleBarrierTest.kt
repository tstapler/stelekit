// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SettleBarrierTest {
    private val page = BoundPageRef("Closet", "/g/pages/Closet.md")
    private val other = BoundPageRef("Garage", "/g/pages/Garage.md")
    private val pauses = mutableListOf<Long>()

    private fun barrier(
        busy: () -> Boolean = { false },
        probe: PageSettleProbe,
        attempts: Int = 4,
    ) = SettleBarrier(busy, probe, pause = { pauses += it }, maxAttempts = attempts, intervalMillis = 250)

    @Test
    fun settles_Immediately_When_EveryPageIsCurrent() = runTest {
        val result = barrier(probe = { PageSettleState.Settled }).awaitSettled(listOf(page, other))

        assertEquals(SettleResult.Settled(unloaded = 0), result)
        assertEquals(emptyList(), pauses)
    }

    @Test
    fun settles_WithNoPages_When_SyncIsIdle() = runTest {
        assertEquals(SettleResult.Settled(0), barrier(probe = { error("no page to probe") }).awaitSettled(emptyList()))
    }

    @Test
    fun countsIndexOnlyStubs_AsSettled() = runTest {
        val result = barrier(probe = { if (it == page) PageSettleState.IndexOnlyStub else PageSettleState.Settled })
            .awaitSettled(listOf(page, other))

        assertEquals(SettleResult.Settled(unloaded = 1), result)
    }

    @Test
    fun waitsForTheImport_ThenSettlesOnce() = runTest {
        var imported = false
        val probes = mutableListOf<BoundPageRef>()
        val barrier = SettleBarrier(
            isGitSyncBusy = { false },
            probe = { probes += it; if (imported) PageSettleState.Settled else PageSettleState.Stale },
            pause = { pauses += it; if (pauses.size == 2) imported = true },
            maxAttempts = 5,
            intervalMillis = 250,
        )

        val result = barrier.awaitSettled(listOf(page))

        assertEquals(SettleResult.Settled(0), result)
        assertEquals(listOf(250L, 250L), pauses)
        assertEquals(3, probes.size, "stale twice, then current")
    }

    @Test
    fun onlyReprobesThePagesThatWereStale() = runTest {
        var imported = false
        val probes = mutableListOf<BoundPageRef>()
        val barrier = SettleBarrier(
            isGitSyncBusy = { false },
            probe = { probes += it; if (it == other || imported) PageSettleState.Settled else PageSettleState.Stale },
            pause = { imported = true },
            maxAttempts = 3,
            intervalMillis = 1,
        )

        barrier.awaitSettled(listOf(page, other))

        assertEquals(listOf(page, other, page), probes)
    }

    @Test
    fun givesUp_AfterTheBoundedAttempts_When_TheDatabaseNeverCatchesUp() = runTest {
        val result = barrier(probe = { PageSettleState.Stale }, attempts = 3).awaitSettled(listOf(page, other))

        val unsettled = assertIs<SettleResult.Unsettled>(result)
        assertEquals(2, unsettled.stalePages)
        assertEquals("unsettled=2", unsettled.note)
        assertEquals(listOf(250L, 250L), pauses, "no pause after the last attempt")
    }

    @Test
    fun neverProbes_WhileGitSyncIsBusy() = runTest {
        var busyReads = 0
        var probes = 0
        val result = barrier(
            busy = { ++busyReads <= 2 },
            probe = { probes++; PageSettleState.Settled },
        ).awaitSettled(listOf(page))

        assertEquals(SettleResult.Settled(0), result)
        assertEquals(1, probes, "first probe happens after the third busy read")
        assertEquals(2, pauses.size)
    }

    @Test
    fun isUnsettled_When_GitSyncStaysBusy_EvenWithNoPages() = runTest {
        val result = barrier(busy = { true }, probe = { error("never probed") }, attempts = 2).awaitSettled(emptyList())

        val unsettled = assertIs<SettleResult.Unsettled>(result)
        assertEquals("unsettled=git sync busy", unsettled.note)
    }

    @Test
    fun loaderProbe_IsSettled_OnlyWhenTheRegistryHashEqualsTheDiskHash() = runTest {
        val disk = "- width:: 24 in"
        fun probe(registry: Int?, onDisk: String? = disk, stub: Boolean = false) =
            LoaderPageSettleProbe({ stub }, { registry }, { onDisk })

        assertEquals(PageSettleState.Settled, probe(disk.hashCode()).stateOf(page))
        assertEquals(PageSettleState.Stale, probe(disk.hashCode() + 1).stateOf(page))
        assertEquals(PageSettleState.Stale, probe(null).stateOf(page), "the loader never saw the file")
        assertEquals(PageSettleState.Stale, probe(disk.hashCode(), onDisk = null).stateOf(page), "unreadable file")
        assertEquals(PageSettleState.IndexOnlyStub, probe(registry = null, stub = true).stateOf(page))
    }
}
