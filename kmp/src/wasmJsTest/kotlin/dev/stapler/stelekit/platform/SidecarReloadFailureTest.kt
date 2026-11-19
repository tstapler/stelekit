@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Review finding B7: a takeover whose sidecar reload fails or finds the root over the cap must not report `Held`, and the
 * storage status must say why, so a reconcile can never push the database over the real OPFS files from an empty cache.
 */
class SidecarReloadFailureTest {
    private fun freshGraph(): String = "/stelekit/it-reload-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun sidecar(graph: String, rel: String) = "${SidecarLiveSet.rootOf(graph)}/$rel"

    private fun probe() = OpfsProbeResult(
        facts = OpfsProbeFacts(rootPresent = true, createWritable = true, moveVerified = true, copyVerifyVerified = true, sliceRead = true),
        quotaBytes = null,
        directory = "/stelekit-probe-test",
        files = emptyList(),
    )

    @Test
    fun aTooLargeReload_should_KeepTheLeaseNotHeld_AndMakeTheStatusUnavailable() = runTest {
        val graph = freshGraph()
        opfsWriteFile(sidecar(graph, "kitchen/project-model.json"), "{}")
        val fs = PlatformFileSystem()
        fs.recordProbe(probe())
        fs.preload(graph)
        assertEquals(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false), fs.storageStatus)
        // The live files grow past the cap while this tab is not the writer: the takeover reload sees a TooLarge root.
        opfsWriteFile(sidecar(graph, "kitchen/sheets/a.v2.json"), "y".repeat(200))
        fs.maxPreloadBytes = 100L
        val lease = WasmSketchWriterLease(MutableStateFlow(graph.substringAfterLast('/')), fs::reloadSidecarRoot)

        val state = lease.acquire()

        assertFalse(state == SketchWriterLeaseState.Held, "an empty cache must never be written from")
        assertEquals(V2StorageStatus.Unavailable(StorageReason.PreloadTooLarge), fs.storageStatus)
        assertFalse(fs.supportsTempRenameWrites)
        lease.release()
    }

    @Test
    fun reloadSidecarRoot_should_ThrowWhenTheRootIsOverTheCap() = runTest {
        val graph = freshGraph()
        opfsWriteFile(sidecar(graph, "kitchen/project-model.json"), "x".repeat(200))
        val fs = PlatformFileSystem()
        fs.preload(graph)
        fs.maxPreloadBytes = 100L

        assertFailsWith<SidecarReloadFailed> { fs.reloadSidecarRoot() }
        assertIs<SidecarPreloadOutcome.TooLarge>(fs.sidecarPreloadOutcome)
    }
}
