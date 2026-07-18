// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.DirtyOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors HostDirectorySyncHandleRetentionTest.kt's established idiom for this codebase).

// The fake FileSystemDirectoryHandle/FileSystemFileHandle tree builders (fakeTextFileEntry,
// fakeBytesFileEntry, fakeDirEntry, fakeThrowingDirEntry, Entry/TextFile/BytesFile/Dir, buildEntry,
// rootDir, emptyRootDir) and FakeCacheAccess live in HostDirectoryTestFixtures.kt (Task 3.4.3a) —
// shared with HostDirectorySyncReconciliationBenchmarkTest.kt, same package, no import needed.

// ── window.showDirectoryPicker stubbing (Task 3.3.1d/connectHostDirectory tests) ──────────────
// Mirrors HostDirectorySyncHandleRetentionTest.kt's stubIndexedDbOpenToThrow/restoreIndexedDb idiom.

private fun stubShowDirectoryPickerToResolve(handle: JsAny): JsAny? = js(
    """
    (function() {
        var original = window.showDirectoryPicker;
        window.showDirectoryPicker = function() { return Promise.resolve(handle); };
        return original || null;
    })()
    """,
)

private fun stubShowDirectoryPickerToReject(): JsAny? = js(
    """
    (function() {
        var original = window.showDirectoryPicker;
        window.showDirectoryPicker = function() { return Promise.reject(new Error('user cancelled')); };
        return original || null;
    })()
    """,
)

private fun restoreShowDirectoryPicker(original: JsAny?): Unit = js(
    """
    (function() { window.showDirectoryPicker = original; })()
    """,
)

// ── Permission-method stamping for reconnectHostDirectory tests (Epic 2.2) ─────────────────────
// The rootDir()/fakeDirEntry() fixtures above only shape the walkable directory-entry surface
// (kind/name/values()) that runHostReconciliation consumes — they carry no queryPermission/
// requestPermission methods, since connectHostDirectory (Epic 3.1) never calls those (it gets its
// handle straight from showDirectoryPicker(), which is always implicitly granted). This stamps
// those two methods onto an existing fixture tree's root so it also satisfies
// HostDirectorySync.lookupPersistedHandle's Pair<JsAny, String> contract for
// reconnectHostDirectory's Epic 2.2 tests below.
private fun withGrantedPermission(dirHandle: JsAny): JsAny = js(
    """
    (function() {
        dirHandle.queryPermission = function(opts) { return Promise.resolve('granted'); };
        dirHandle.requestPermission = function(opts) { return Promise.resolve('granted'); };
        return dirHandle;
    })()
    """,
)

/**
 * Epic 3.3 (Story 3.3.1): dedicated regression/safety coverage for the Critical Finding —
 * reconciliation must never silently destroy browser-only edits when live sync is (re)established.
 * Scoped to [HostDirectorySync] directly (constructed against a [FakeCacheAccess], per Task
 * 1.6.1c), except for [pickDirectoryAsync_should_ProduceByteForByteIdenticalCacheToPreProjectBehavior_When_GraphIsFreshAndEmpty],
 * which deliberately targets the real [PlatformFileSystem] as a regression guard on the
 * pre-existing fresh-graph import path this project must not touch.
 *
 * **Deferred tests** (still deferred — require `scheduleHostWriteThrough`'s real flush logic, Epic
 * 4.1, which does not exist yet on this branch):
 * - `scheduleHostWriteThrough_should_EnqueuePathOnceDelayedOpfsWriteResolves_When_WriteFileWasCalledWithASlowOpfsWriteFileDouble`
 * - `scheduleHostWriteThrough_should_NotContainPathUntilOpfsWriteDeferredResolves_When_GivenTheSameSlowOpfsWriteFileDouble`
 *
 * The two `reconnectHostDirectory`-driven tests previously deferred here (Epic 2.2 did not exist
 * on this branch when this file was first written) are now implemented below, in the
 * "reconnectHostDirectory (Epic 2.2)" section near the end of this class:
 * - [reconnectHostDirectory_should_RunHostReconciliationAndSetGranted_When_HandleFoundAndPermissionGranted]
 * - [reconnectHostDirectory_should_InvokeOnHostConflictIdenticallyToConnectHostDirectory_When_SilentResumeEncountersDivergence]
 *
 * `reconnectHostDirectory_should_ReenqueueHostWritePending_When_CacheHoldsBrowserOnlyEditButInMemoryQueueWasLostToCrash`
 * (validation.md row 71) is implemented below in its `runHostReconciliation`-only form — see
 * [runHostReconciliation_should_ReenqueueHostWritePending_When_CacheHoldsBrowserOnlyEditButInMemoryQueueWasLostToCrash]'s
 * doc comment for why the name differs from validation.md's literal row.
 */
class HostDirectorySyncReconciliationTest {

    // FakeCacheAccess is defined in HostDirectoryTestFixtures.kt (Task 3.4.3a — shared with
    // HostDirectorySyncReconciliationBenchmarkTest.kt).

    private fun newSync(
        graphId: String,
        cacheAccess: FakeCacheAccess,
        scope: CoroutineScope,
    ): HostDirectorySync = HostDirectorySync(
        graphIdProvider = { graphId },
        cacheAccess = cacheAccess,
        scope = scope,
    )

    // ── connectHostDirectory (Story 3.1.1, Critical Finding) ───────────────────────────────────

    @Test
    fun connectHostDirectory_should_PreserveBrowserOnlyEditInCache_When_EnablingLiveSyncOnAlreadyPopulatedGraph() = runTest {
        val opfsPath = "/stelekit/my-notes"
        val cache = FakeCacheAccess()
        cache.textStore["$opfsPath/pages/BrowserOnly.md"] = "browser version"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("my-notes", cache, testScope)

        val original = stubShowDirectoryPickerToResolve(emptyRootDir())
        val state = try {
            sync.connectHostDirectory(opfsPath)
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertEquals(HostAccessState.Granted, state)
        assertNotNull(sync.hostDirHandle)
        // Not deleted or overwritten — read/written only via CacheAccess, still present.
        assertEquals("browser version", cache.textStore["$opfsPath/pages/BrowserOnly.md"])
        // Queued for push to the host.
        assertTrue(sync.hostWritePending.containsKey("pages/BrowserOnly.md"))
        assertEquals(DirtyOp.WRITE, sync.hostWritePending.getValue("pages/BrowserOnly.md").op)
        testScope.cancel()
    }

    @Test
    fun connectHostDirectory_should_LeaveHostDirHandleNullAndStateNotApplicable_When_ShowDirectoryPickerOrReconciliationFails() = runTest {
        val opfsPath = "/stelekit/g"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Sub-case A: showDirectoryPicker itself rejects.
        run {
            val cache = FakeCacheAccess()
            val sync = newSync("g", cache, testScope)
            val original = stubShowDirectoryPickerToReject()
            val state = try {
                sync.connectHostDirectory(opfsPath)
            } finally {
                restoreShowDirectoryPicker(original)
            }
            assertEquals(HostAccessState.NotApplicable, state)
            assertNull(sync.hostDirHandle)
        }

        // Sub-case B: picker resolves but reconciliation throws mid-walk (directory unreadable).
        run {
            val cache = FakeCacheAccess()
            val sync = newSync("g", cache, testScope)
            val original = stubShowDirectoryPickerToResolve(fakeThrowingDirEntry("g"))
            val state = try {
                sync.connectHostDirectory(opfsPath)
            } finally {
                restoreShowDirectoryPicker(original)
            }
            assertEquals(HostAccessState.NotApplicable, state)
            assertNull(sync.hostDirHandle)
            assertNull(sync.hostGraphOpfsPath)
        }

        testScope.cancel()
    }

    // ── runHostReconciliation four-way classification (Story 3.2.1) ────────────────────────────

    @Test
    fun runHostReconciliation_should_LeaveCacheAndHostWritePendingUntouched_When_PathClassifiesAsIdentical() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        cache.textStore["$opfsPath/pages/Same.md"] = "identical content"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val host = rootDir(Dir("pages", listOf(TextFile("Same.md", "identical content"))))
        val summary = sync.runHostReconciliation(host, opfsPath)

        assertEquals(ReconciliationSummary(identical = 1, hostChangedConflict = 0, hostOnlyNew = 0, browserOnlyNeedsPush = 0), summary)
        assertEquals("identical content", cache.textStore["$opfsPath/pages/Same.md"])
        assertTrue(sync.hostWritePending.isEmpty())
        assertTrue(cache.mirrorWrites.isEmpty())
        testScope.cancel()
    }

    @Test
    fun runHostReconciliation_should_ProduceIdenticalConflictHostOnlyAndBrowserOnlyOutcomes_When_WalkingAFourPathMixedDirectory() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        cache.textStore["$opfsPath/pages/A.md"] = "same"
        cache.textStore["$opfsPath/pages/B.md"] = "old version"
        cache.textStore["$opfsPath/pages/D.md"] = "browser only"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val host = rootDir(
            Dir(
                "pages",
                listOf(
                    TextFile("A.md", "same"),
                    TextFile("B.md", "host new version"),
                    TextFile("C.md", "new content"),
                ),
            ),
        )

        val onHostConflictCalls = mutableListOf<Pair<String, String>>()
        sync.onHostConflict = { path, content -> onHostConflictCalls += path to content }

        val summary = sync.runHostReconciliation(host, opfsPath)

        assertEquals(
            ReconciliationSummary(identical = 1, hostChangedConflict = 1, hostOnlyNew = 1, browserOnlyNeedsPush = 1),
            summary,
        )
        // A.md: untouched.
        assertEquals("same", cache.textStore["$opfsPath/pages/A.md"])
        // B.md: exactly one conflict callback, cache not silently overwritten.
        assertEquals(listOf("pages/B.md" to "host new version"), onHostConflictCalls)
        assertEquals("old version", cache.textStore["$opfsPath/pages/B.md"])
        // C.md: imported into cache and scheduled for an OPFS mirror write.
        assertEquals("new content", cache.textStore["$opfsPath/pages/C.md"])
        assertTrue(cache.mirrorWrites.contains("$opfsPath/pages/C.md" to "new content"))
        // D.md: appears in hostWritePending.
        assertTrue(sync.hostWritePending.containsKey("pages/D.md"))
        testScope.cancel()
    }

    @Test
    fun runHostReconciliation_should_UseClassifyReconciliationBytes_When_PathEndsWithMdStekSuffix() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        cache.bytesStore["$opfsPath/pages/Secret.md.stek"] = byteArrayOf(1, 2, 3)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val hostBytes = byteArrayOf(9, 9, 9)
        val host = rootDir(Dir("pages", listOf(BytesFile("Secret.md.stek", hostBytes))))

        val onHostConflictCalls = mutableListOf<Pair<String, String>>()
        sync.onHostConflict = { path, content -> onHostConflictCalls += path to content }

        val summary = sync.runHostReconciliation(host, opfsPath)

        assertEquals(1, summary.hostChangedConflict)
        // Bytes accessors exercised — never the String-typed get/set for this path.
        assertTrue(cache.getBytesCallCount >= 1)
        assertEquals(0, cache.setBytesCallCount) // conflict, not host-only-new — never written
        assertEquals(0, cache.getCallCount)
        assertEquals(0, cache.setCallCount)
        // Never decoded as UTF-8 text and handed to the String-typed conflict callback.
        assertTrue(onHostConflictCalls.isEmpty())
        // Cache bytes untouched (not overwritten).
        assertTrue(cache.bytesStore["$opfsPath/pages/Secret.md.stek"]!!.contentEquals(byteArrayOf(1, 2, 3)))
        testScope.cancel()
    }

    @Test
    fun runHostReconciliation_should_ClassifyPathsNotVisitedByHostWalkAsBrowserOnlyNeedsPush_When_CacheHasPathsAbsentFromHost() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        cache.textStore["$opfsPath/pages/OnlyA.md"] = "a"
        cache.textStore["$opfsPath/pages/OnlyB.md"] = "b"
        // A different graph's cache entry must never leak into this opfsPath's classification.
        cache.textStore["/stelekit/other-graph/pages/Unrelated.md"] = "unrelated"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val summary = sync.runHostReconciliation(emptyRootDir(), opfsPath)

        assertEquals(2, summary.browserOnlyNeedsPush)
        assertTrue(sync.hostWritePending.containsKey("pages/OnlyA.md"))
        assertTrue(sync.hostWritePending.containsKey("pages/OnlyB.md"))
        assertFalse(sync.hostWritePending.containsKey("pages/Unrelated.md"))
        testScope.cancel()
    }

    // ── mtime/size pre-filter (Story 3.4.1, Task 3.4.1b) ───────────────────────────────────────

    @Test
    fun runHostReconciliation_should_PerformZeroContentReads_When_AllFilesMatchMtimeSizeBaseline() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val fileCount = 25
        val files = (1..fileCount).map { i -> TextFile("File$i.md", "content-$i", lastModified = 1_000L + i) }
        // Baseline exactly matches every file's mtime/size — a steady-state session-resume with
        // nothing changed since the last reconciliation/poll.
        files.forEach { f ->
            val path = "$opfsPath/pages/${f.name}"
            sync.hostModTimes[path] = f.lastModified
            sync.hostFileSizes[path] = f.size
            cache.textStore[path] = f.content
        }

        val counter = newReadCounter()
        val host = rootDir(Dir("pages", files), counter = counter)

        val summary = sync.runHostReconciliation(host, opfsPath)

        assertEquals(fileCount, summary.identical)
        assertEquals(0, readCounterValue(counter))
        assertTrue(cache.mirrorWrites.isEmpty())
        testScope.cancel()
    }

    @Test
    fun runHostReconciliation_should_PerformExactlyNContentReads_When_NOfManyFilesDifferFromBaseline() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val totalCount = 30
        val changedCount = 7
        val files = (1..totalCount).map { i ->
            val changed = i <= changedCount
            TextFile("File$i.md", "content-$i", lastModified = if (changed) 2_000L + i else 1_000L + i)
        }
        // Baseline reflects each file's PREVIOUS mtime — changedCount of them no longer match the
        // walk's freshly-observed mtime, forcing a fall-through content read for exactly those.
        files.forEachIndexed { idx, f ->
            val i = idx + 1
            val path = "$opfsPath/pages/${f.name}"
            sync.hostModTimes[path] = 1_000L + i
            sync.hostFileSizes[path] = f.size
            cache.textStore[path] = f.content
        }

        val counter = newReadCounter()
        val host = rootDir(Dir("pages", files), counter = counter)

        val summary = sync.runHostReconciliation(host, opfsPath)

        // The pre-filter is a short-circuit for the unchanged case only — a mtime miss still
        // routes through the normal four-way classification, which lands on Identical here since
        // content itself didn't change, proving the pre-filter never substitutes for classification.
        assertEquals(changedCount, readCounterValue(counter))
        assertEquals(totalCount, summary.identical)
        testScope.cancel()
    }

    @Test
    fun runHostReconciliation_should_FallBackToFullContentRead_When_NoBaselineExistsForAnyPath() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)
        // sync.hostModTimes/hostFileSizes intentionally left empty — first-ever reconciliation for
        // this graph (e.g. a fresh connectHostDirectory) must never treat "no baseline" as
        // "unchanged."

        val fileCount = 12
        val files = (1..fileCount).map { i -> TextFile("File$i.md", "content-$i", lastModified = 5_000L + i) }
        val counter = newReadCounter()
        val host = rootDir(Dir("pages", files), counter = counter)

        val summary = sync.runHostReconciliation(host, opfsPath)

        assertEquals(fileCount, readCounterValue(counter))
        // Cache started empty, so every file is HostOnlyNew — identical to Task 3.2.1a's
        // pre-existing first-connect behavior (no behavior change for this case).
        assertEquals(fileCount, summary.hostOnlyNew)
        // The baseline is now populated for the next (steady-state) pass.
        files.forEach { f ->
            val path = "$opfsPath/pages/${f.name}"
            assertEquals(f.lastModified, sync.hostModTimes[path])
            assertEquals(f.size, sync.hostFileSizes[path])
        }
        testScope.cancel()
    }

    // ── ReconciliationOutcome dispatch (Story 3.2.2) ────────────────────────────────────────────

    @Test
    fun runHostReconciliation_should_InvokeOnHostConflictExactlyOnce_When_PathClassifiesAsHostChangedConflict() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        cache.textStore["$opfsPath/pages/Foo.md"] = "browser version"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val host = rootDir(Dir("pages", listOf(TextFile("Foo.md", "host version"))))
        var callCount = 0
        var lastArgs: Pair<String, String>? = null
        sync.onHostConflict = { path, content -> callCount++; lastArgs = path to content }

        sync.runHostReconciliation(host, opfsPath)

        assertEquals(1, callCount)
        assertEquals("pages/Foo.md" to "host version", lastArgs)
        // Never overwritten.
        assertEquals("browser version", cache.textStore["$opfsPath/pages/Foo.md"])
        testScope.cancel()
    }

    @Test
    fun runHostReconciliation_should_ImportViaSetBytesAndWriteOpfsMirrorBytes_When_HostOnlyNewPathIsMdStekSuffixed() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val hostBytes = byteArrayOf(5, 6, 7)
        val host = rootDir(Dir("pages", listOf(BytesFile("New.md.stek", hostBytes))))

        val summary = sync.runHostReconciliation(host, opfsPath)

        assertEquals(1, summary.hostOnlyNew)
        assertEquals(1, cache.setBytesCallCount)
        assertTrue(cache.bytesStore["$opfsPath/pages/New.md.stek"]!!.contentEquals(hostBytes))
        assertTrue(cache.mirrorBytesWrites.any { it.first == "$opfsPath/pages/New.md.stek" && it.second.contentEquals(hostBytes) })
        // Text-typed accessors never touched for this path.
        assertEquals(0, cache.setCallCount)
        assertTrue(cache.mirrorWrites.isEmpty())
        testScope.cancel()
    }

    @Test
    fun runHostReconciliation_should_EnqueueHostWritePendingEntry_When_PathClassifiesAsBrowserOnlyNeedsPush() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        cache.textStore["$opfsPath/pages/Draft.md"] = "unsaved edit"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val summary = sync.runHostReconciliation(emptyRootDir(), opfsPath)

        assertEquals(1, summary.browserOnlyNeedsPush)
        val entry = sync.hostWritePending["pages/Draft.md"]
        assertNotNull(entry)
        assertEquals(DirtyOp.WRITE, entry.op)
        assertTrue(entry.updatedAtMillis > 0)
        testScope.cancel()
    }

    // ── Fresh-empty-graph regression (Task 3.3.1d) ──────────────────────────────────────────────

    @Test
    fun pickDirectoryAsync_should_ProduceByteForByteIdenticalCacheToPreProjectBehavior_When_GraphIsFreshAndEmpty() = runTest {
        val graphName = "it-fresh-${Random.nextInt(0, Int.MAX_VALUE)}"
        val fs = PlatformFileSystem()

        val host = fakeDirEntry(
            graphName,
            toJsArray(
                listOf(
                    fakeTextFileEntry("Root.md", "# root"),
                    buildEntry(Dir("pages", listOf(TextFile("Foo.md", "# foo"), TextFile("Bar.md", "# bar")))),
                ),
            ),
        )

        val original = stubShowDirectoryPickerToResolve(host)
        val opfsPath = try {
            fs.pickDirectoryAsync()
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertEquals("/stelekit/$graphName", opfsPath)
        // All files land in cache exactly as importUserDirToCache always has — no reconciliation
        // logic runs on this path (it is a HostDirectorySync method, never called from
        // pickDirectoryAsync).
        assertEquals("# root", fs.readFile("/stelekit/$graphName/Root.md"))
        assertEquals("# foo", fs.readFile("/stelekit/$graphName/pages/Foo.md"))
        assertEquals("# bar", fs.readFile("/stelekit/$graphName/pages/Bar.md"))
        // The handle is still attached (Epic 2.1's attachFreshHandle, unaffected by this project).
        assertNotNull(fs.hostDirectorySync.hostDirHandle)
    }

    // ── hostWritePending crash recovery — resolved half (Blocker 2, Task 3.3.1g) ───────────────

    /**
     * Validation.md row 71 names this test `reconnectHostDirectory_should_ReenqueueHostWritePending_...`
     * driven through `reconnectHostDirectory` (Epic 2.2, not yet implemented on this branch). Per
     * this dispatch's instructions, the `reconnectHostDirectory`-driven variant is deferred to
     * whoever implements Epic 2.2; this test instead exercises the same recovery mechanism one
     * layer down, directly through [HostDirectorySync.runHostReconciliation] — the actual
     * mechanism `reconnectHostDirectory` would delegate to per the plan's "reconnectHostDirectory
     * reconciliation" Pattern Decisions row. Once Epic 2.2 lands, this test's assertions still
     * hold; a new `reconnectHostDirectory`-driven test can be added alongside it without
     * replacing it.
     */
    @Test
    fun runHostReconciliation_should_ReenqueueHostWritePending_When_CacheHoldsBrowserOnlyEditButInMemoryQueueWasLostToCrash() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        cache.textStore["$opfsPath/pages/Draft.md"] = "unsaved edit"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        // Simulates a tab crash that lost the in-memory hostWritePending map after the edit's OPFS
        // write had already completed: hostWritePending starts empty, host has no Draft.md.
        assertTrue(sync.hostWritePending.isEmpty())

        sync.runHostReconciliation(emptyRootDir(), opfsPath)

        assertTrue(sync.hostWritePending.containsKey("pages/Draft.md"))
        testScope.cancel()
    }

    // ── reconnectHostDirectory (Epic 2.2) ───────────────────────────────────────────────────────
    // Previously deferred here (see this class's doc comment) pending Epic 2.2's implementation —
    // filled in alongside it.

    @Test
    fun reconnectHostDirectory_should_RunHostReconciliationAndSetGranted_When_HandleFoundAndPermissionGranted() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        cache.textStore["$opfsPath/pages/BrowserOnly.md"] = "browser version"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val host = withGrantedPermission(
            rootDir(Dir("pages", listOf(TextFile("New.md", "new content")))),
        )
        sync.lookupPersistedHandle = { host to opfsPath }

        val state = sync.reconnectHostDirectory("g")

        // Granted resolves immediately, without waiting on the launched reconciliation (Story
        // 2.2.1's Blocker 3/pre-mortem P1 #1 non-blocking-launch contract) — no UI-blocking wait.
        assertEquals(HostAccessState.Granted, state)
        assertEquals(HostAccessState.Granted, sync.hostAccessStateFlow.value)
        assertNotNull(sync.hostDirHandle)

        // Give the launched (scope.launch, non-blocking) reconciliation real wall-clock time to
        // finish before asserting its outcome — it runs on testScope's real Dispatchers.Default.
        withContext(Dispatchers.Default) { delay(300) }

        // New.md landed in cache — proof reconciliation actually ran (not just permission-queried).
        assertEquals("new content", cache.textStore["$opfsPath/pages/New.md"])
        // BrowserOnly.md untouched and queued for push — the same divergence-preserving contract
        // connectHostDirectory's four-way classification test already proves for that entry point.
        assertEquals("browser version", cache.textStore["$opfsPath/pages/BrowserOnly.md"])
        assertTrue(sync.hostWritePending.containsKey("pages/BrowserOnly.md"))
        testScope.cancel()
    }

    @Test
    fun reconnectHostDirectory_should_InvokeOnHostConflictIdenticallyToConnectHostDirectory_When_SilentResumeEncountersDivergence() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        cache.textStore["$opfsPath/pages/B.md"] = "old version"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val host = withGrantedPermission(
            rootDir(Dir("pages", listOf(TextFile("B.md", "host new version")))),
        )
        sync.lookupPersistedHandle = { host to opfsPath }

        val onHostConflictCalls = mutableListOf<Pair<String, String>>()
        sync.onHostConflict = { path, content -> onHostConflictCalls += path to content }

        val state = sync.reconnectHostDirectory("g")
        assertEquals(HostAccessState.Granted, state)

        withContext(Dispatchers.Default) { delay(300) }

        // Same conflict-dispatch contract connectHostDirectory's four-way classification test
        // already proves — onHostConflict invoked exactly once, cache never silently overwritten —
        // now proven identically for the silent-resume entry point (Blocker 3's core claim: both
        // entry points share this data-loss protection, not just the one-time connect flow).
        assertEquals(listOf("pages/B.md" to "host new version"), onHostConflictCalls)
        assertEquals("old version", cache.textStore["$opfsPath/pages/B.md"])
        testScope.cancel()
    }
}
