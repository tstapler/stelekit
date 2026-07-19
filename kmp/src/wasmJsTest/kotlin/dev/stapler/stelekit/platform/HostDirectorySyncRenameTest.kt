// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors HostDirectoryTestFixtures.kt's established idiom for this codebase).
//
// makeWritableHostRoot/writableRoot* (Epic 4.5) and rootDir/Dir/TextFile/FakeCacheAccess (Epic 3.4)
// live in HostDirectoryTestFixtures.kt, same package, no import needed.

/**
 * A writable host root whose readback (`getFile().text()`) always resolves to [fixedReadback],
 * regardless of what was actually written via `createWritable()`/`write()` — used by
 * [HostDirectorySyncRenameTest.renameHostFile_should_LeaveOldPathInPlace_When_NewFileVerificationFailsAfterWrite]
 * to simulate Task 7.1.1b's verify-before-delete step observing a mismatch after an otherwise
 * successful write (e.g. a host-side write that silently landed corrupted, or a `getFile()`/
 * `.text()` implementation quirk) — the *write itself* still succeeds and is durably recorded
 * (`_getWrittenContent`), so this fixture exercises "write succeeded, verification's independent
 * readback disagrees," not "write failed." Deliberately flat (top-level filenames only), same
 * scope-minimizing precedent as `makeWritableHostRoot`.
 */
private fun makeWritableHostRootWithMismatchedReadback(fixedReadback: String): JsAny = js(
    """
    (function() {
        var files = {};
        var deleteCalls = [];
        return {
            kind: 'directory',
            name: 'root',
            getFileHandle: function(name, opts) {
                if (!(name in files)) {
                    if (opts && opts.create) {
                        files[name] = { content: null };
                    } else {
                        return Promise.reject(new Error('NotFoundError: no such file'));
                    }
                }
                var entry = files[name];
                return Promise.resolve({
                    kind: 'file',
                    name: name,
                    getFile: function() {
                        return Promise.resolve({
                            lastModified: 0,
                            size: 0,
                            text: function() { return Promise.resolve(fixedReadback); }
                        });
                    },
                    createWritable: function() {
                        return Promise.resolve({
                            write: function(data) { entry.content = data; return Promise.resolve(); },
                            close: function() { return Promise.resolve(); }
                        });
                    }
                });
            },
            removeEntry: function(name) {
                deleteCalls.push(name);
                if (!(name in files)) return Promise.reject(new Error('NotFoundError: no such entry'));
                delete files[name];
                return Promise.resolve();
            },
            queryPermission: function() { return Promise.resolve('granted'); },
            requestPermission: function() { return Promise.resolve('granted'); },
            _hasFile: function(name) { return name in files; },
            _getWrittenContent: function(name) { return (files[name] && files[name].content != null) ? files[name].content : null; },
            _deleteCallCount: function() { return deleteCalls.length; }
        };
    })()
    """,
)

private fun mismatchedRootHasFile(root: JsAny, name: String): Boolean = js("root._hasFile(name)")
private fun mismatchedRootWrittenContent(root: JsAny, name: String): String? = js("root._getWrittenContent(name)")
private fun mismatchedRootDeleteCallCount(root: JsAny): Int = js("root._deleteCallCount()")

/**
 * Epic 7.1/7.2 (Story 7.1.1, Story 7.1.2, Story 7.2.1): `HostRenameOp`'s write-new/verify/
 * delete-old protocol ([HostDirectorySync.renameHostFile]), its [PlatformFileSystem.renameFile]
 * delegation, and the log-only (never-destructive) stale-rename-duplicate signal in
 * [HostDirectorySync.runHostReconciliation].
 */
class HostDirectorySyncRenameTest {

    private fun freshOpfsPath(): String = "/stelekit/rn-${Random.nextInt(0, Int.MAX_VALUE)}"
    private fun freshGraphId(): String = "it-rename-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun newSync(
        opfsPath: String,
        cacheAccess: FakeCacheAccess,
        scope: CoroutineScope,
        rootHandle: JsAny,
    ): HostDirectorySync {
        val graphId = opfsPath.substringAfterLast("/")
        val sync = HostDirectorySync(
            graphIdProvider = { graphId },
            cacheAccess = cacheAccess,
            scope = scope,
        )
        sync.hostDirHandle = rootHandle
        sync.hostGraphOpfsPath = opfsPath
        return sync
    }

    /** Mirrors HostDirectorySyncWriteThroughTest.kt's helper — scheduleHostWriteThrough/
     * renameHostFile launch onto a real, independently-dispatched CoroutineScope. */
    private suspend fun awaitCondition(timeoutMs: Long = 2000, stepMs: Long = 10, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    // ── Story 7.1.1/Task 7.2.1a: round-trip rename ─────────────────────────────────────────────

    @Test
    fun renameHostFile_should_WriteNewContentThenDeleteOldPath_When_VerificationOfNewFileSucceeds() = runTest {
        val opfsPath = freshOpfsPath()
        val root = makeWritableHostRoot()
        writableRootSetContent(root, "Old.md", "body")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)

        sync.renameHostFile("$opfsPath/Old.md", "$opfsPath/New.md", "body")

        assertEquals("body", writableRootGetContent(root, "New.md"))
        assertFalse(writableRootHasFile(root, "Old.md"), "old path must be deleted once verification succeeds")

        testScope.cancel()
    }

    @Test
    fun renameHostFile_should_LeaveOldPathInPlace_When_NewFileVerificationFailsAfterWrite() = runTest {
        val opfsPath = freshOpfsPath()
        // Every readback resolves to "CORRUPTED", regardless of what was actually written —
        // simulates Task 7.1.1b's verification step observing a mismatch after an otherwise
        // successful write.
        val root = makeWritableHostRootWithMismatchedReadback("CORRUPTED")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPath, FakeCacheAccess(), testScope, root)

        sync.renameHostFile("$opfsPath/Old.md", "$opfsPath/New.md", "body")

        // The write itself did succeed (durably recorded) — only the delete is fail-safed.
        assertEquals("body", mismatchedRootWrittenContent(root, "New.md"))
        assertTrue(mismatchedRootHasFile(root, "New.md"))
        // Old.md was never created on this fixture (fail-safe never even called removeEntry on it,
        // since the old path was never present here to begin with — the load-bearing assertion is
        // that no delete call happened at all).
        assertEquals(0, mismatchedRootDeleteCallCount(root), "verification failure must never delete anything")

        testScope.cancel()
    }

    // ── Task 7.2.1a: PlatformFileSystem.renameFile delegation (thin) ───────────────────────────

    @Test
    fun renameFile_should_UpdateCacheSynchronouslyAndKickOffHostRename_When_HostDirHandleIsSet() = runTest {
        val graphId = freshGraphId()
        val graphPath = "/stelekit/$graphId"
        val fs = PlatformFileSystem()
        fs.preload(graphPath)
        // hostDirHandle not yet set — writeFile below must not attempt host write-through.
        fs.writeFile("$graphPath/Old.md", "body")

        val root = makeWritableHostRoot()
        writableRootSetContent(root, "Old.md", "body")
        fs.hostDirectorySync.hostDirHandle = root
        fs.hostDirectorySync.hostGraphOpfsPath = graphPath

        val result = fs.renameFile("$graphPath/Old.md", "$graphPath/New.md")

        assertTrue(result)
        // Cache updated synchronously — visible immediately, no await needed.
        assertEquals("body", fs.readFile("$graphPath/New.md"))
        assertNull(fs.readFile("$graphPath/Old.md"))
        // Host rename kicked off asynchronously (fire-and-forget from renameFile's perspective).
        awaitCondition { writableRootHasFile(root, "New.md") && !writableRootHasFile(root, "Old.md") }
        assertEquals("body", writableRootGetContent(root, "New.md"))
        assertFalse(writableRootHasFile(root, "Old.md"))
    }

    @Test
    fun renameFile_should_ReturnFalseAndNeverTouchHost_When_SourcePathNotInCache() = runTest {
        val graphId = freshGraphId()
        val graphPath = "/stelekit/$graphId"
        val fs = PlatformFileSystem()
        fs.preload(graphPath)
        val root = makeWritableHostRoot()
        fs.hostDirectorySync.hostDirHandle = root
        fs.hostDirectorySync.hostGraphOpfsPath = graphPath

        val result = fs.renameFile("$graphPath/Missing.md", "$graphPath/New.md")

        assertFalse(result)
        assertEquals(0, writableRootCreateWritableCallCount(root), "nothing to rename — host must never be touched")
    }

    // ── Story 7.1.2/Task 7.2.1b: interrupted rename leaves a non-destructive duplicate ─────────

    @Test
    fun runHostReconciliation_should_ImportBothPathsAndLogOnly_When_HostOnlyNewPathContentHashMatchesAnotherCachePath() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        // cache reflects only the post-rename state: pages/New.md — matching web-git-writeback's
        // already-completed in-app rename. pages/Old.md is host-only (the interrupted rename left
        // it behind, having crashed after write-new but before delete-old).
        cache.textStore["$opfsPath/pages/New.md"] = "body"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = HostDirectorySync(graphIdProvider = { "g" }, cacheAccess = cache, scope = testScope)

        val host = rootDir(Dir("pages", listOf(TextFile("Old.md", "body"), TextFile("New.md", "body"))))
        val summary = sync.runHostReconciliation(host, opfsPath)

        assertEquals(1, summary.identical, "New.md matches cache — Identical")
        assertEquals(1, summary.hostOnlyNew, "Old.md is host-only — imported normally, never deleted")
        assertEquals(0, summary.hostChangedConflict)
        // Both paths present, byte-identical, on both "host" (the fixture tree) and in cache.
        assertEquals("body", cache.textStore["$opfsPath/pages/Old.md"])
        assertEquals("body", cache.textStore["$opfsPath/pages/New.md"])
        // No host deletion call is made for either path as a side effect of the coincidental match
        // — this class's runHostReconciliation walk has no delete/host-mutation code path at all
        // for HostOnlyNew, so this is also structurally guaranteed, not merely observed here.
        assertTrue(sync.hostWritePending.isEmpty(), "no queue entry may result from this coincidence")

        testScope.cancel()
    }

    @Test
    fun runHostReconciliation_should_NeverDeleteEitherPath_When_TwoUnrelatedHostOnlyNewPagesShareIdenticalContent() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess() // empty — both pages are genuinely new and unrelated
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = HostDirectorySync(graphIdProvider = { "g" }, cacheAccess = cache, scope = testScope)

        val host = rootDir(Dir("pages", listOf(TextFile("Empty1.md", ""), TextFile("Empty2.md", ""))))
        val summary = sync.runHostReconciliation(host, opfsPath)

        assertEquals(2, summary.hostOnlyNew, "both are new, unrelated, coincidentally-empty pages")
        assertEquals(0, summary.hostChangedConflict)
        // Both imported normally — the coincidental-match false positive has zero destructive
        // effect, which is the entire point of dropping the auto-delete heuristic.
        assertEquals("", cache.textStore["$opfsPath/pages/Empty1.md"])
        assertEquals("", cache.textStore["$opfsPath/pages/Empty2.md"])
        assertTrue(sync.hostWritePending.isEmpty(), "no queue entry, no deletion, for either path")

        testScope.cancel()
    }
}
