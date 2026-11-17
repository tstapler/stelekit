// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.projectbridge.FileProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Task 1.4.7i (D-123, D-142, D-150): two tabs on one graph, modelled as two [PlatformFileSystem] instances and two
 * [WasmSketchWriterLease] instances in one page. They share one OPFS and one lock manager, and each has its own preloaded
 * cache. A real second tab never gets this far (the driver lock stops it at boot, D-149); this is defence in depth.
 */
class TwoTabSketchLockTest {
    private class Tab(val id: String, val ready: MutableStateFlow<String?>, val fs: PlatformFileSystem) {
        var reloads = 0
        val lease = WasmSketchWriterLease(ready) {
            reloads++
            fs.reloadSidecarRoot()
        }
        val graph get() = "/stelekit/$id"
    }

    private suspend fun tab(id: String): Tab {
        val fs = PlatformFileSystem()
        fs.preload("/stelekit/$id")
        return Tab(id, MutableStateFlow(id), fs)
    }

    private fun freshId() = "it-twotab-${Random.nextInt(0, Int.MAX_VALUE)}"

    private suspend fun awaitState(tab: Tab, wanted: SketchWriterLeaseState) {
        withContext(Dispatchers.Default) { withTimeout(AWAIT_MS) { tab.lease.state.first { it == wanted } } }
    }

    private fun modelPath(tab: Tab) = "${SidecarLiveSet.rootOf(tab.graph)}/proj/project-model.json"

    private fun storeOf(tab: Tab) =
        FileProjectSidecarStore(tab.fs, tab.graph, AtomicFileWriter(tab.fs, { it }, ".tmp"), tab.lease) { false }

    @Test
    fun secondInstance_should_BeHeldElsewhere_AndItsStoreWriteReturnFailedWriterLeaseNotHeld() = runTest {
        val id = freshId()
        val first = tab(id)
        val second = tab(id)
        assertEquals(SketchWriterLeaseState.Held, first.lease.acquire())
        assertEquals(SketchWriterLeaseState.HeldElsewhere, second.lease.acquire())

        val outcome = storeOf(second).write("proj", mapOf("project-model.json" to "{}".encodeToByteArray()))

        assertIs<DomainError.FileSystemError.WriterLeaseNotHeld>(assertIs<SidecarWriteOutcome.Failed>(outcome).error)
        first.lease.release()
        second.lease.release()
    }

    @Test
    fun acquire_should_ReportNotHeldWhileNoGraphIsReady() = runTest {
        val t = tab(freshId())
        t.ready.value = null
        assertEquals(SketchWriterLeaseState.Acquiring, t.lease.acquire())
    }

    @Test
    fun readyGraphChange_should_ReleaseTheLeaseAndLetTheOtherInstanceEdit() = runTest {
        val id = freshId()
        val first = tab(id)
        val second = tab(id)
        first.lease.acquire()
        assertEquals(SketchWriterLeaseState.HeldElsewhere, second.lease.acquire())

        first.ready.value = "another-graph"

        awaitState(first, SketchWriterLeaseState.Acquiring)
        awaitState(second, SketchWriterLeaseState.Held)
        second.lease.release()
    }

    @Test
    fun afterTheFirstReleases_should_ReloadTheSidecarRootBeforeWriting() = runTest {
        val id = freshId()
        val first = tab(id)
        val second = tab(id)
        first.lease.acquire()
        second.lease.acquire()
        first.fs.writeFile(modelPath(first), """{"newer":true}""")
        first.fs.awaitDurable(modelPath(first))
        assertNull(second.fs.readFile(modelPath(second)), "the second tab's cache is stale before the takeover")

        first.lease.release()
        awaitState(second, SketchWriterLeaseState.Held)

        assertEquals("""{"newer":true}""", second.fs.readFile(modelPath(second)), "Held is reported only after the reload")
        second.lease.release()
    }

    @Test
    fun reacquire_should_ReloadTheSidecarRootAndGrowTheEpoch() = runTest {
        val id = freshId()
        val t = tab(id)
        assertEquals(SketchWriterLeaseState.Held, t.lease.acquire())
        assertEquals(0L, t.lease.epoch)
        assertEquals(1, t.reloads)

        t.lease.release()
        assertEquals(SketchWriterLeaseState.Acquiring, t.lease.state.value)
        assertEquals(SketchWriterLeaseState.Held, t.lease.acquire())

        assertEquals(1L, t.lease.epoch)
        assertEquals(2, t.reloads)
        t.lease.release()
    }

    @Test
    fun takeoverAfterHeldElsewhere_should_GrowTheEpoch() = runTest {
        val id = freshId()
        val first = tab(id)
        val second = tab(id)
        first.lease.acquire()
        second.lease.acquire()
        assertEquals(0L, second.lease.epoch)

        first.lease.release()
        awaitState(second, SketchWriterLeaseState.Held)

        assertEquals(1L, second.lease.epoch, "a bundle parked while read-only must not be replayed under the new epoch")
        second.lease.release()
    }

    @Test
    fun staleCache_should_NeverOverwriteANewerFile() = runTest {
        val id = freshId()
        val first = tab(id)
        val second = tab(id)
        first.lease.acquire()
        second.lease.acquire()
        first.fs.writeFile(modelPath(first), """{"v":2}""")
        first.fs.awaitDurable(modelPath(first))

        val refused = storeOf(second).write("proj", mapOf("project-model.json" to """{"v":1}""".encodeToByteArray()))

        assertIs<SidecarWriteOutcome.Failed>(refused)
        assertEquals("""{"v":2}""", first.fs.readFile(modelPath(first)))
        assertNull(second.fs.readFile(modelPath(second)), "the refused write left the stale cache untouched")
        first.lease.release()
        second.lease.release()
    }

    private companion object {
        const val AWAIT_MS = 10_000L
    }
}
