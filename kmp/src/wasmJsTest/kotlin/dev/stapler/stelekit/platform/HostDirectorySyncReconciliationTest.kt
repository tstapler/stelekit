// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.DirtyOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors HostDirectorySyncHandleRetentionTest.kt's established idiom for this codebase).

// ── Fake FileSystemDirectoryHandle/FileSystemFileHandle tree builders ─────────────────────────
// Mirrors the `listOpfsEntries`/`isFileEntry`/`isDirectoryEntry`/`getFile().text()`/
// `getFile().arrayBuffer()` surface `runHostReconciliation`/`PlatformFileSystem.pickDirectoryAsync`
// actually consume (OpfsInterop.kt) — a minimal test double for the real browser API, following
// PlatformFileSystemDirtyTrackingIntegrationTest.kt/HostDirectorySyncHandleRetentionTest.kt's
// precedent of testing against the real wasmJs interop surface (headless Chrome,
// `wasmJsBrowserTest`) rather than injecting a mock traversal function.

private fun newJsArray(): JsAny = js("[]")
private fun jsArrayPush(arr: JsAny, item: JsAny): Unit = js("arr.push(item)")

private fun toJsArray(items: List<JsAny>): JsAny {
    val arr = newJsArray()
    for (item in items) jsArrayPush(arr, item)
    return arr
}

private fun fakeTextFileEntry(name: String, content: String): JsAny = js(
    """
    ({
        kind: 'file',
        name: name,
        getFile: function() {
            return Promise.resolve({
                text: function() { return Promise.resolve(content); }
            });
        }
    })
    """,
)

private fun fakeBytesFileEntry(name: String, buffer: JsAny): JsAny = js(
    """
    ({
        kind: 'file',
        name: name,
        getFile: function() {
            return Promise.resolve({
                arrayBuffer: function() { return Promise.resolve(buffer); }
            });
        }
    })
    """,
)

private fun fakeDirEntry(name: String, children: JsAny): JsAny = js(
    """
    ({
        kind: 'directory',
        name: name,
        values: function() {
            var idx = 0;
            return {
                next: function() {
                    if (idx < children.length) {
                        return Promise.resolve({ done: false, value: children[idx++] });
                    }
                    return Promise.resolve({ done: true, value: undefined });
                }
            };
        }
    })
    """,
)

/** Used by the `connectHostDirectory`/`runHostReconciliation` error-path test. */
private fun fakeThrowingDirEntry(name: String): JsAny = js(
    """
    ({
        kind: 'directory',
        name: name,
        values: function() { throw new Error('boom: directory unreadable'); }
    })
    """,
)

private sealed interface Entry
private data class TextFile(val name: String, val content: String) : Entry
private data class BytesFile(val name: String, val bytes: ByteArray) : Entry
private data class Dir(val name: String, val children: List<Entry> = emptyList()) : Entry

private fun buildEntry(e: Entry): JsAny = when (e) {
    is TextFile -> fakeTextFileEntry(e.name, e.content)
    is BytesFile -> fakeBytesFileEntry(e.name, e.bytes.toJsArrayBuffer())
    is Dir -> fakeDirEntry(e.name, toJsArray(e.children.map { buildEntry(it) }))
}

/** Builds a fake root [FileSystemDirectoryHandle]-shaped `JsAny` from a declarative [Entry] tree. */
private fun rootDir(vararg children: Entry): JsAny = buildEntry(Dir("root", children.toList()))

private fun emptyRootDir(): JsAny = fakeDirEntry("root", newJsArray())

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

/**
 * Epic 3.3 (Story 3.3.1): dedicated regression/safety coverage for the Critical Finding —
 * reconciliation must never silently destroy browser-only edits when live sync is (re)established.
 * Scoped to [HostDirectorySync] directly (constructed against a [FakeCacheAccess], per Task
 * 1.6.1c), except for [pickDirectoryAsync_should_ProduceByteForByteIdenticalCacheToPreProjectBehavior_When_GraphIsFreshAndEmpty],
 * which deliberately targets the real [PlatformFileSystem] as a regression guard on the
 * pre-existing fresh-graph import path this project must not touch.
 *
 * **Deferred tests** (see this dispatch's final report for the full rationale — all require
 * `reconnectHostDirectory`, Epic 2.2, or `scheduleHostWriteThrough`'s real flush logic, Epic 4.1,
 * neither of which exist yet on this branch):
 * - `reconnectHostDirectory_should_RunHostReconciliationAndSetGranted_When_HandleFoundAndPermissionGranted`
 * - `reconnectHostDirectory_should_InvokeOnHostConflictIdenticallyToConnectHostDirectory_When_SilentResumeEncountersDivergence`
 * - `scheduleHostWriteThrough_should_EnqueuePathOnceDelayedOpfsWriteResolves_When_WriteFileWasCalledWithASlowOpfsWriteFileDouble`
 * - `scheduleHostWriteThrough_should_NotContainPathUntilOpfsWriteDeferredResolves_When_GivenTheSameSlowOpfsWriteFileDouble`
 *
 * `reconnectHostDirectory_should_ReenqueueHostWritePending_When_CacheHoldsBrowserOnlyEditButInMemoryQueueWasLostToCrash`
 * (validation.md row 71) is implemented below in its `runHostReconciliation`-only form — see
 * [runHostReconciliation_should_ReenqueueHostWritePending_When_CacheHoldsBrowserOnlyEditButInMemoryQueueWasLostToCrash]'s
 * doc comment for why the name differs from validation.md's literal row.
 */
class HostDirectorySyncReconciliationTest {

    private class FakeCacheAccess : HostDirectorySync.CacheAccess {
        val textStore = mutableMapOf<String, String>()
        val bytesStore = mutableMapOf<String, ByteArray>()
        var getCallCount = 0
        var setCallCount = 0
        var getBytesCallCount = 0
        var setBytesCallCount = 0
        val mirrorWrites = mutableListOf<Pair<String, String>>()
        val mirrorBytesWrites = mutableListOf<Pair<String, ByteArray>>()

        override fun get(path: String): String? {
            getCallCount++
            return textStore[path]
        }
        override fun set(path: String, content: String) {
            setCallCount++
            textStore[path] = content
        }
        override fun remove(path: String) {
            textStore.remove(path)
        }
        override fun getBytes(path: String): ByteArray? {
            getBytesCallCount++
            return bytesStore[path]
        }
        override fun setBytes(path: String, data: ByteArray) {
            setBytesCallCount++
            bytesStore[path] = data
        }
        override fun removeBytes(path: String) {
            bytesStore.remove(path)
        }
        override fun keysUnder(opfsPath: String): Set<String> =
            (textStore.keys + bytesStore.keys).filter { it.startsWith("$opfsPath/") }.toSet()
        override fun writeOpfsMirror(path: String, content: String) {
            mirrorWrites += path to content
        }
        override fun writeOpfsMirrorBytes(path: String, data: ByteArray) {
            mirrorBytesWrites += path to data
        }
        override fun opfsWriteDeferredFor(path: String): Deferred<Unit>? = null
    }

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
}
