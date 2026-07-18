// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors HostDirectorySyncReconciliationTest.kt/HostDirectorySyncHandleRetentionTest.kt's
// established idiom for this codebase). Duplicated from HostDirectorySyncReconciliationTest.kt
// rather than imported — those two stubs are `private` (file-scoped) there, and this file's
// rollback-leg test (validation.md's explicit "deserves its own explicit assertion in the
// migration-scoped file, not just a cross-reference") needs its own copy.

private fun stubShowDirectoryPickerToResolve(handle: JsAny): JsAny? = js(
    """
    (function() {
        var original = window.showDirectoryPicker;
        window.showDirectoryPicker = function() { return Promise.resolve(handle); };
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
 * Validation.md's "Migration & Known-Limitation Coverage" § "Migration test — 4-way
 * reconciliation classification, realistic mixed-state fixture" — the single reviewer-readable
 * artifact proving the Critical Finding (Migration Plan, upgrade-boundary reconciliation) is
 * closed end-to-end. Distinct from `HostDirectorySyncReconciliationTest.kt`'s smaller,
 * implementation-level 4-path combined test
 * ([HostDirectorySyncReconciliationTest.runHostReconciliation_should_ProduceIdenticalConflictHostOnlyAndBrowserOnlyOutcomes_When_WalkingAFourPathMixedDirectory])
 * and from Epic 3.3's per-branch unit tests (which test each [ReconciliationOutcome] in
 * isolation) — this fixture is named after the actual upgrade-boundary scenario the Migration
 * Plan describes (`Stable`/`EditedBoth`/`NewOnDisk`/`BrowserDraft`/`Secret`), reusing
 * [HostDirectoryTestFixtures.kt]'s existing builders rather than duplicating fixture-building
 * logic.
 */
class HostDirectorySyncMigrationReconciliationTest {

    private fun newSync(
        graphId: String,
        cacheAccess: FakeCacheAccess,
        scope: CoroutineScope,
    ): HostDirectorySync = HostDirectorySync(
        graphIdProvider = { graphId },
        cacheAccess = cacheAccess,
        scope = scope,
    )

    @Test
    fun runHostReconciliation_should_ClassifyAllFourOutcomesCorrectly_When_ReconcilingARealisticMixedStateGraphAtTheUpgradeBoundary() = runTest {
        val opfsPath = "/stelekit/upgrade-graph"
        val cache = FakeCacheAccess()

        // Stable.md — identical on both sides since last sync.
        cache.textStore["$opfsPath/pages/Stable.md"] = "stable content, untouched since last sync"
        // EditedBoth.md — the user edited it in-browser AND it was independently edited on disk
        // (e.g. by another tool, or pre-upgrade) before this reconciliation runs.
        cache.textStore["$opfsPath/pages/EditedBoth.md"] = "browser-side edit"
        // BrowserDraft.md — created in-browser after the original one-time import, never written
        // to the host directory at all.
        cache.textStore["$opfsPath/pages/BrowserDraft.md"] = "draft that only ever existed in-browser"
        // Secret.md.stek — paranoid-mode encrypted content, present on both sides with differing
        // bytes (folds Blocker 4's bytes-path requirement into this same realistic fixture).
        cache.bytesStore["$opfsPath/pages/Secret.md.stek"] = byteArrayOf(1, 2, 3)

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("upgrade-graph", cache, testScope)

        val host = rootDir(
            Dir(
                "pages",
                listOf(
                    TextFile("Stable.md", "stable content, untouched since last sync"),
                    TextFile("EditedBoth.md", "host-side edit — landed via git pull before opt-in"),
                    // NewOnDisk.md — present only on the host, e.g. added via `git pull` before
                    // the user ever opted into live sync.
                    TextFile("NewOnDisk.md", "page added on disk before live sync was enabled"),
                    BytesFile("Secret.md.stek", byteArrayOf(9, 9, 9)),
                ),
            ),
        )

        val onHostConflictCalls = mutableListOf<Pair<String, String>>()
        sync.onHostConflict = { path, content -> onHostConflictCalls += path to content }

        val summary = sync.runHostReconciliation(host, opfsPath)

        // Exactly one classified outcome per path — 5 paths total, none dropped or double-counted.
        assertEquals(
            ReconciliationSummary(identical = 1, hostChangedConflict = 2, hostOnlyNew = 1, browserOnlyNeedsPush = 1),
            summary,
        )

        // Stable.md: no-op — cache untouched, no mirror write.
        assertEquals("stable content, untouched since last sync", cache.textStore["$opfsPath/pages/Stable.md"])
        assertTrue(cache.mirrorWrites.none { it.first == "$opfsPath/pages/Stable.md" })

        // EditedBoth.md: the core Critical Finding assertion — onHostConflict fires exactly once
        // with the host content, and the browser's edit in `cache` is left completely untouched
        // (never silently overwritten by the host's divergent version).
        assertEquals(
            listOf("pages/EditedBoth.md" to "host-side edit — landed via git pull before opt-in"),
            onHostConflictCalls,
        )
        assertEquals("browser-side edit", cache.textStore["$opfsPath/pages/EditedBoth.md"])

        // NewOnDisk.md: imported into cache and scheduled for an OPFS mirror write — never lost.
        assertEquals(
            "page added on disk before live sync was enabled",
            cache.textStore["$opfsPath/pages/NewOnDisk.md"],
        )
        assertTrue(
            cache.mirrorWrites.contains(
                "$opfsPath/pages/NewOnDisk.md" to "page added on disk before live sync was enabled",
            ),
        )

        // BrowserDraft.md: enqueued for push to the host — not lost.
        assertTrue(sync.hostWritePending.containsKey("pages/BrowserDraft.md"))

        // Secret.md.stek: classified via the bytes-aware path (Blocker 4) — the text-typed
        // onHostConflict callback is never invoked for it, and its ciphertext is never decoded to
        // a String nor silently overwritten.
        assertTrue(onHostConflictCalls.none { it.first == "pages/Secret.md.stek" })
        assertTrue(cache.bytesStore["$opfsPath/pages/Secret.md.stek"]!!.contentEquals(byteArrayOf(1, 2, 3)))

        testScope.cancel()
    }

    // ── Rollback leg — Migration Plan's "if connectHostDirectory is never invoked, nothing
    // changes" claim (duplicates Task 3.3.1d's regression test by design — validation.md is
    // explicit that this claim deserves its own assertion in the migration-scoped file, not just
    // a cross-reference). ──────────────────────────────────────────────────────────────────────

    @Test
    fun pickDirectoryAsync_should_LeaveFreshEmptyGraphImportUnaffected_When_ConnectHostDirectoryIsNeverInvoked() = runTest {
        val graphName = "it-migration-rollback-${Random.nextInt(0, Int.MAX_VALUE)}"
        val fs = PlatformFileSystem()

        val host = fakeDirEntry(
            graphName,
            toJsArray(
                listOf(
                    fakeTextFileEntry("Root.md", "# root"),
                    buildEntry(Dir("pages", listOf(TextFile("Foo.md", "# foo")))),
                ),
            ),
        )

        val original = stubShowDirectoryPickerToResolve(host)
        val opfsPath = try {
            // The one-time-import entry point (pre-existing, unaffected-by-this-project path) —
            // never HostDirectorySync.connectHostDirectory, which the rollback claim says must
            // never even be reached for this behavior to hold.
            fs.pickDirectoryAsync()
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertEquals("/stelekit/$graphName", opfsPath)
        assertEquals("# root", fs.readFile("/stelekit/$graphName/Root.md"))
        assertEquals("# foo", fs.readFile("/stelekit/$graphName/pages/Foo.md"))
        // No host directory connection was ever established — this is exactly today's
        // pre-live-sync behavior, byte for byte.
        assertNotNull(fs.hostDirectorySync.hostDirHandle)
        assertEquals(HostAccessState.NotApplicable, fs.hostDirectorySync.hostAccessStateFlow.value)
    }
}
