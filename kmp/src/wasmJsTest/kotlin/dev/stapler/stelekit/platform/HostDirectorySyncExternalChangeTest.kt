// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.db.ExternalFileChange
import dev.stapler.stelekit.db.FileRegistry
import dev.stapler.stelekit.db.GraphFileWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — TextFile/BytesFile/Dir/rootDir/
// FakeCacheAccess/newReadCounter/readCounterValue live in HostDirectoryTestFixtures.kt, same
// package, no import needed.

/** Minimal placeholder `FileSystemDirectoryHandle`-shaped `JsAny` — only used as a non-null
 * `hostDirHandle` value to satisfy [HostDirectorySync.listFilesWithModTimes]'s "a host directory
 * is connected" gate; [HostDirectorySync.pollHostDirectoryOnce] is always called with an explicit,
 * separately-built fixture `dirHandle` argument in this file, never this placeholder. */
private fun fakePlaceholderHandle(): JsAny = js("({})")

/**
 * Epic 5.4 (Story 5.4.1): end-to-end proof that a host-side change reaches
 * `GraphFileWatcher.externalFileChanges` (the `DiskConflictDialog` trigger point) without any
 * `FileRegistry`/`GraphFileWatcher` code change — Epic 5.1's core architectural bet
 * (`pollHostDirectoryOnce` feeding `FileRegistry`/`GraphFileWatcher`'s existing synchronous
 * `getLastModifiedTime`/`listFilesWithModTimes` contract). Task 5.4.1a wires a real
 * `PlatformFileSystem` (with its real `hostDirectorySync`) since `FileRegistry`/`GraphFileWatcher`
 * consume the `FileSystem` interface, not `HostDirectorySync` directly; Tasks 5.4.1b/c manipulate
 * `hostDirectorySync`'s state/`pollHostDirectoryOnce` more directly, closer to the unit level, per
 * their own narrower acceptance criteria (own-write suppression via `FileRegistry`'s existing
 * content-hash guard; the `.md.stek` poller branch).
 */
class HostDirectorySyncExternalChangeTest {

    private fun freshGraphId(): String = "ext-change-${Random.nextInt(0, Int.MAX_VALUE)}"

    /** Polls [block] on a real dispatcher until true or the timeout elapses — GraphFileWatcher
     * owns its own Dispatchers.Default-backed scope, independent of runTest's virtual scheduler,
     * so waiting for its emission requires a real (not virtual-time) wait. Mirrors
     * HostDirectorySyncWriteThroughTest.kt/PlatformFileSystemHostSyncDelegationTest.kt's
     * established `awaitCondition` helper. */
    private suspend fun awaitCondition(timeoutMs: Long = 3000, stepMs: Long = 20, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    // ── Task 5.4.1a: end-to-end mtime-bump-to-ExternalFileChange ──────────────────────────────

    @Test
    fun pollHostDirectoryOnce_should_ProduceExternalFileChangeThroughUnmodifiedWatcher_When_HostMtimeBumps() = runTest {
        val graphId = freshGraphId()
        val graphPath = "/stelekit/$graphId"
        val pagesDir = "$graphPath/pages"
        val filePath = "$pagesDir/Foo.md"

        val fs = PlatformFileSystem()
        fs.preload(graphPath)
        // hostDirHandle must be non-null for HostDirectorySync.listFilesWithModTimes to delegate
        // (rather than PlatformFileSystem falling through to the pre-Phase-5 default) — see that
        // method's "no host directory connected" gate.
        fs.hostDirectorySync.hostDirHandle = fakePlaceholderHandle()
        fs.hostDirectorySync.hostGraphOpfsPath = graphPath

        // Seed the local cache with the file's original content, exactly as if it had been
        // imported/reconciled from the host directory already — establishes FileRegistry's
        // baseline (mtime 0, matching hostModTimes' as-yet-empty state) on the scan below.
        fs.writeFile(filePath, "- original content")

        val fileRegistry = FileRegistry(fs)
        fileRegistry.scanDirectory(pagesDir)

        val watcher = GraphFileWatcher(
            fileSystem = fs,
            fileRegistry = fileRegistry,
            readFile = fs::readFile,
            onReloadFile = { _, _ -> },
            pollIntervalMs = 30L,
        )
        val received = mutableListOf<ExternalFileChange>()
        val collectJob = launch(Dispatchers.Default) {
            watcher.externalFileChanges.collect { received += it }
        }

        try {
            // Simulate a poll tick observing an external edit: bumped mtime, new content.
            val hostRoot = rootDir(Dir("pages", listOf(TextFile("Foo.md", "- changed content", lastModified = 5_000L))))
            fs.hostDirectorySync.pollHostDirectoryOnce(hostRoot, graphPath)

            watcher.startWatching(graphPath)
            awaitCondition { received.isNotEmpty() }

            assertEquals(1, received.size, "exactly one ExternalFileChange expected")
            assertEquals(filePath, received.first().filePath)
            assertEquals("- changed content", received.first().content)
        } finally {
            watcher.stopWatching()
            collectJob.cancel()
            watcher.close()
        }
    }

    // ── Task 5.4.1b: own-write suppression (FileRegistry's existing content-hash guard) ───────

    @Test
    fun detectChanges_should_SuppressChange_When_PollerObservesTheSameJustWrittenContent() = runTest {
        val graphId = freshGraphId()
        val graphPath = "/stelekit/$graphId"
        val pagesDir = "$graphPath/pages"
        val filePath = "$pagesDir/Foo.md"

        val fs = PlatformFileSystem()
        fs.preload(graphPath)
        fs.hostDirectorySync.hostDirHandle = fakePlaceholderHandle()
        fs.hostDirectorySync.hostGraphOpfsPath = graphPath

        // The app's own edit.
        fs.writeFile(filePath, "- v1")

        val fileRegistry = FileRegistry(fs)
        // First-ever detectChanges call: fileRegistry has no baseline yet, so this file is
        // classified as "new" — this is what records BOTH the mtime baseline AND (unlike
        // scanDirectory) the content hash FileRegistry's own-write guard later compares against.
        val firstPass = fileRegistry.detectChanges(pagesDir)
        assertEquals(1, firstPass.newFiles.size, "first-ever detectChanges call must record the baseline as a new file")
        assertTrue(firstPass.changedFiles.isEmpty())

        // Simulate the poller later observing the SAME content this tab itself just wrote —
        // e.g. reconciling its own OPFS write reflected back on the host filesystem — with a
        // bumped mtime (the only thing that actually changed).
        val hostRoot = rootDir(Dir("pages", listOf(TextFile("Foo.md", "- v1", lastModified = 1_000L))))
        fs.hostDirectorySync.pollHostDirectoryOnce(hostRoot, graphPath)

        val secondPass = fileRegistry.detectChanges(pagesDir)

        assertTrue(secondPass.changedFiles.isEmpty(), "own-write with unchanged content must never surface as changed")
        assertTrue(secondPass.newFiles.isEmpty())
    }

    // ── Task 5.4.1c: .md.stek paranoid-mode poller branch (bytes, never text) ─────────────────

    @Test
    fun pollHostDirectoryOnce_should_UseBytesBranch_When_ChangedPathIsMdStek() = runTest {
        val opfsPath = "/stelekit/${freshGraphId()}"
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = HostDirectorySync(
            graphIdProvider = { "g" },
            cacheAccess = cache,
            scope = testScope,
        )

        val bytesCounter = newReadCounter()
        val secretPath = "$opfsPath/pages/Secret.md.stek"
        val cipherBytes = byteArrayOf(1, 2, 3, 4, 5)

        // fakeBytesFileEntry's fixture object only implements arrayBuffer() (no .text() method
        // at all) — if production code mistakenly tried the text branch for this path, it would
        // throw (undefined is not a function) rather than silently succeed, so the absence of a
        // thrown error here is itself part of the "never reads via .text()" guarantee, reinforced
        // by the setBytesCallCount/setCallCount assertions below.
        val stekEntry = fakeBytesFileEntry(
            name = "Secret.md.stek",
            buffer = cipherBytes.toJsArrayBuffer(),
            lastModified = 9_000L,
            size = cipherBytes.size.toLong(),
            counter = bytesCounter,
        )
        val pagesDir = fakeDirEntry("pages", toJsArray(listOf(stekEntry)))
        val root = fakeDirEntry("root", toJsArray(listOf(pagesDir)))

        sync.pollHostDirectoryOnce(root, opfsPath)

        assertEquals(1, readCounterValue(bytesCounter), "changed .md.stek path must read via arrayBuffer()")
        assertEquals(1, cache.setBytesCallCount, "changed .md.stek path must update bytesCache via setBytes")
        assertEquals(0, cache.setCallCount, "a .md.stek path must never update the text cache via set")
        assertTrue(cache.bytesStore[secretPath].contentEquals(cipherBytes))
        assertEquals(9_000L, sync.hostModTimes[secretPath])

        testScope.cancel()
    }
}
