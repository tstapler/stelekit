@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 1.4.7j: `preloadState`, the `V2StorageStatus` wiring, the live-set cap, the late recovery and the once-per-preload sweep. */
class OpfsPreloadStateTest {
    private fun freshGraph(): String = "/stelekit/it-preload-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun sidecar(graph: String, rel: String) = "${SidecarLiveSet.rootOf(graph)}/$rel"

    private fun probe(available: Boolean = true) = OpfsProbeResult(
        facts = OpfsProbeFacts(rootPresent = available, createWritable = available, moveVerified = available, copyVerifyVerified = available, sliceRead = available),
        quotaBytes = null,
        directory = "/stelekit-probe-test",
        files = emptyList(),
    )

    private class Clock(var t: Long = 0L)

    private fun fsWith(clock: Clock = Clock()): PlatformFileSystem = PlatformFileSystem().also { it.nowMs = { clock.t } }

    @Test
    fun beforeAnyPreloadOrProbe_should_BeNotStartedAndChecking() {
        val fs = PlatformFileSystem()
        assertEquals(PreloadState.NotStarted, fs.preloadState.value)
        assertEquals(V2StorageStatus.Checking, fs.storageStatus)
        assertFalse(fs.supportsTempRenameWrites)
    }

    @Test
    fun preload_should_MoveNotStartedToLoadingToOk_WhenTheSidecarRootIsMissing() = runTest {
        val fs = PlatformFileSystem()
        val seen = mutableListOf<PreloadState>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch { fs.preloadState.collect { seen += it } }
        fs.preload(freshGraph())
        watcher.cancel()
        assertEquals(PreloadState.NotStarted, seen.first())
        assertTrue(seen.any { it == PreloadState.Loading }, "Loading was seen: $seen")
        assertIs<PreloadState.Ok>(seen.last(), "a missing graph directory is Ok with an empty cache")
    }

    @Test
    fun preload_should_BeFailed_WhenTheSidecarRootIsNotADirectory() = runTest {
        val graph = freshGraph()
        opfsWriteFile(SidecarLiveSet.rootOf(graph), "not a directory")
        val fs = PlatformFileSystem()
        fs.recordProbe(probe())
        fs.preload(graph)
        assertEquals(StorageReason.PreloadFailed, assertIs<PreloadState.Failed>(fs.preloadState.value).reason)
        assertEquals(V2StorageStatus.Unavailable(StorageReason.PreloadFailed), fs.storageStatus)
        assertFalse(fs.supportsTempRenameWrites, "a failed preload can never let a v2 sheet be created over unread data")
    }

    @Test
    fun readyProbeAndOkPreload_should_TurnTheTempRenameSurfaceOnUnderTheSidecarRootOnly() = runTest {
        val graph = freshGraph()
        val fs = PlatformFileSystem()
        fs.recordProbe(probe())
        fs.preload(graph)
        assertEquals(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false), fs.storageStatus)
        assertTrue(fs.supportsTempRenameWrites)
        assertTrue(fs.supportsAtomicReplace(sidecar(graph, "kitchen/sheets/a.json")))
        assertFalse(fs.supportsAtomicReplace("$graph/pages/a.md"), "a page write keeps its plain write on the web")
    }

    @Test
    fun anUnavailableVerdict_should_KeepEverythingOff() = runTest {
        val graph = freshGraph()
        val fs = PlatformFileSystem()
        fs.recordProbe(probe(available = false))
        fs.preload(graph)
        assertEquals(V2StorageStatus.Unavailable(StorageReason.NoOpfs), fs.storageStatus)
        assertFalse(fs.supportsTempRenameWrites)
        assertFalse(fs.supportsAtomicReplace(sidecar(graph, "kitchen/sheets/a.json")))
    }

    @Test
    fun overTheCap_should_BePreloadTooLarge_AndReadNothing() = runTest {
        val graph = freshGraph()
        opfsWriteFile(sidecar(graph, "kitchen/project-model.json"), "x".repeat(60))
        opfsWriteFile(sidecar(graph, "kitchen/sheets/a.v2.json"), "y".repeat(60))
        val fs = PlatformFileSystem()
        fs.maxPreloadBytes = 100L
        fs.recordProbe(probe())
        fs.preload(graph)
        assertEquals(StorageReason.PreloadTooLarge, assertIs<PreloadState.Failed>(fs.preloadState.value).reason)
        assertEquals(V2StorageStatus.Unavailable(StorageReason.PreloadTooLarge), fs.storageStatus)
        assertEquals(SidecarPreloadOutcome.TooLarge(120L), fs.sidecarPreloadOutcome)
        assertNull(fs.readFile(sidecar(graph, "kitchen/project-model.json")), "nothing was read")
    }

    @Test
    fun projectWithHugeBackupDir_should_StayAvailable() = runTest {
        val graph = freshGraph()
        opfsWriteFile(sidecar(graph, "kitchen/project-model.json"), "{}")
        opfsWriteFile(sidecar(graph, "kitchen/backup/a.v1.json"), "b".repeat(500))
        val fs = PlatformFileSystem()
        fs.maxPreloadBytes = 100L
        fs.recordProbe(probe())
        fs.preload(graph)
        assertEquals(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false), fs.storageStatus)
        assertEquals(SidecarPreloadOutcome.Loaded(2L), fs.sidecarPreloadOutcome, "only the live file is summed")
    }

    @Test
    fun backupConflictsAndSources_should_NotBeReadByThePreload() = runTest {
        val graph = freshGraph()
        for (rel in listOf("backup/a.json", "conflicts/a.json", "sources/a.json", "exports/a.json", "preview.svg")) {
            opfsWriteFile(sidecar(graph, "kitchen/$rel"), "payload")
        }
        val fs = PlatformFileSystem()
        fs.preload(graph)
        for (rel in listOf("backup/a.json", "conflicts/a.json", "sources/a.json", "exports/a.json", "preview.svg")) {
            val path = sidecar(graph, "kitchen/$rel")
            assertNull(fs.readFileBytes(path), "$rel is not preloaded")
            assertTrue(fs.fileExists(path), "$rel is listed by name")
        }
    }

    @Test
    fun lateOkWithinSixtySeconds_should_RecoverOnce() = runTest {
        val clock = Clock()
        val fs = fsWith(clock)
        fs.recordProbe(probe())
        val job = launch { fs.preload(freshGraph()) }
        yield()
        clock.t = 20_000L
        job.join()
        assertIs<PreloadState.Ok>(fs.preloadState.value)
        assertEquals(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false), fs.storageStatus)
    }

    @Test
    fun lateOkAfterSixtySeconds_should_StayUnavailable() = runTest {
        val clock = Clock()
        val fs = fsWith(clock)
        fs.recordProbe(probe())
        val job = launch { fs.preload(freshGraph()) }
        yield()
        clock.t = 75_000L
        job.join()
        assertEquals(V2StorageStatus.Unavailable(StorageReason.CheckTimedOut), fs.storageStatus)
    }

    @Test
    fun pendingPastTenSeconds_should_BeCheckTimedOut() {
        val fs = PlatformFileSystem()
        val clock = Clock(fs.nowMs())
        fs.nowMs = { clock.t }
        fs.recordProbe(probe())
        assertEquals(V2StorageStatus.Checking, fs.storageStatus)
        clock.t += V2StorageStatusReducer.CHECK_TIMEOUT_MS
        assertEquals(V2StorageStatus.Unavailable(StorageReason.CheckTimedOut), fs.storageStatus)
    }

    @Test
    fun textPreload_should_NotHoldTheStatus() = runTest {
        val graph = freshGraph()
        opfsWriteFile("$graph/pages/a.md", "- a page")
        val fs = PlatformFileSystem()
        fs.recordProbe(probe())
        var pageReadWhenReady: String? = "unset"
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            fs.storageStatusFlow.filterIsInstance<V2StorageStatus.Ready>().collect { pageReadWhenReady = fs.readFile("$graph/pages/a.md") }
        }
        fs.preload(graph)
        watcher.cancel()
        assertNull(pageReadWhenReady, "the status was Ready before the text preload had read the page")
        assertEquals("- a page", fs.readFile("$graph/pages/a.md"))
    }

    @Test
    fun anEphemeralGraph_should_BeReadyAndSessionOnly() = runTest {
        val fs = PlatformFileSystem()
        fs.markEphemeral()
        fs.recordProbe(probe())
        fs.preload(freshGraph())
        assertEquals(V2StorageStatus.Ready(sessionOnly = true, privateSuspected = false), fs.storageStatus)
    }

    @Test
    fun staleTemp_should_BeSweptOncePerPreload_AndAYoungOneKept() = runTest {
        val graph = freshGraph()
        val stale = sidecar(graph, "kitchen/sheets/a.json.00000000-0001-7000-8000-000000000000.tmp")
        val young = sidecar(graph, "kitchen/sheets/b.json.${UuidGenerator.generateV7()}.tmp")
        opfsWriteFile(stale, "old")
        opfsWriteFile(young, "new")
        val fs = PlatformFileSystem()
        fs.recordProbe(probe())
        fs.preload(graph)
        fs.awaitSweep()
        fs.awaitDurable(stale)

        assertNull(opfsReadSlice(stale, 1), "the stale temp is gone from OPFS")
        assertNotNull(opfsReadSlice(young, 1), "a temp younger than MAX_AGE may be another tab's live one")
    }

    @Test
    fun aTabWithoutTheSweepLock_should_SkipTheSweep() = runTest {
        val graph = freshGraph()
        val stale = sidecar(graph, "kitchen/sheets/a.json.00000000-0001-7000-8000-000000000000.tmp")
        opfsWriteFile(stale, "old")
        val graphId = graph.removePrefix("/stelekit/")
        val held = assertNotNull(WebLock.tryAcquireHeld(PlatformFileSystem.sweepLockName(graphId)))
        try {
            val fs = PlatformFileSystem()
            fs.preload(graph)
            fs.awaitSweep()
            assertNotNull(opfsReadSlice(stale, 1), "the other tab's sweep owns the directory")
        } finally {
            held.release()
        }
    }
}
