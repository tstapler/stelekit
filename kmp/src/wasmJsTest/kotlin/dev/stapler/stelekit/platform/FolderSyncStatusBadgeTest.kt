// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.ui.components.folderSyncBadgeContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Epic 2.5 (Story 2.5.3): per-state copy assertions for `FolderSyncStatusBadge`, exercised against
 * [folderSyncBadgeContent] — the pure text/clickability derivation extracted from the composable
 * (see that function's doc comment) specifically so this contract is unit-testable without a
 * Compose UI test harness, which this project's `wasmJsTest` source set does not have wired up
 * (no `ui-test`-equivalent dependency for the web target — `ui-test-junit4`/`ui-test-manifest` are
 * `jvmTest`/`androidUnitTest`-only in `kmp/build.gradle.kts`).
 *
 * Lives under `platform/` (not `ui/components/`) per
 * `project_plans/web-local-folder-livesync/implementation/plan.md`'s Task 2.5.3a file path,
 * grouped with this project's other `HostDirectorySync`-adjacent tests rather than split by the
 * composable's own package.
 *
 * The `SyncDegraded` variant of the `Granted` row (design/ux.md's "N changes not yet synced" copy
 * for a stuck write-through queue, Task 4.4.1c) is covered by
 * [folderSyncStatusBadge_should_RenderSyncDegradedText_When_StateIsGrantedAndPendingCountIsNonZeroAndHostWriteStuck]
 * below, now that the write-through queue exists.
 */
class FolderSyncStatusBadgeTest {

    @Test
    fun folderSyncStatusBadge_should_RenderReconnectFolderText_When_StateIsPromptNeeded() {
        val content = folderSyncBadgeContent(HostAccessState.PromptNeeded, dirName = null, pendingWriteCount = 0)

        assertEquals("Reconnect folder", content?.text)
        assertTrue(content?.clickable == true)
    }

    @Test
    fun folderSyncStatusBadge_should_RenderNothing_When_StateIsNotApplicable() {
        val content = folderSyncBadgeContent(HostAccessState.NotApplicable, dirName = null, pendingWriteCount = 0)

        assertNull(content)
    }

    @Test
    fun folderSyncStatusBadge_should_RenderDistinctTextFromPromptNeeded_When_StateIsDisconnected() {
        val disconnected = folderSyncBadgeContent(
            HostAccessState.Disconnected("NotFoundError"),
            dirName = null,
            pendingWriteCount = 0,
        )
        val promptNeeded = folderSyncBadgeContent(HostAccessState.PromptNeeded, dirName = null, pendingWriteCount = 0)

        assertEquals("Folder not found — Reconnect", disconnected?.text)
        assertNotEquals(promptNeeded?.text, disconnected?.text)
        assertTrue(disconnected?.clickable == true)
    }

    @Test
    fun folderSyncStatusBadge_should_RenderGrantAccessText_When_StateIsDenied() {
        val content = folderSyncBadgeContent(HostAccessState.Denied, dirName = null, pendingWriteCount = 0)

        assertEquals("Folder access declined — Grant access", content?.text)
        assertTrue(content?.clickable == true)
    }

    @Test
    fun folderSyncStatusBadge_should_ShowSyncedToDirName_When_StateIsGrantedAndPendingCountIsZero() {
        val content = folderSyncBadgeContent(HostAccessState.Granted, dirName = "my-notes", pendingWriteCount = 0)

        assertEquals("Synced to my-notes", content?.text)
        assertTrue(content?.clickable == false)
    }

    @Test
    fun folderSyncStatusBadge_should_ShowPendingWriteCount_When_StateIsGrantedAndPendingCountIsNonZero() {
        val content = folderSyncBadgeContent(HostAccessState.Granted, dirName = "my-notes", pendingWriteCount = 3)

        assertEquals("3 changes syncing to my-notes", content?.text)
        assertTrue(content?.clickable == false)
    }

    @Test
    fun folderSyncStatusBadge_should_RenderSyncDegradedText_When_StateIsGrantedAndPendingCountIsNonZeroAndHostWriteStuck() {
        val content = folderSyncBadgeContent(
            HostAccessState.Granted,
            dirName = "my-notes",
            pendingWriteCount = 2,
            hostWriteStuck = true,
        )

        assertEquals("2 changes not yet synced to folder", content?.text)
        assertTrue(content?.clickable == true, "SyncDegraded must offer the same reconnect affordance as Denied/PromptNeeded")
    }

    @Test
    fun folderSyncStatusBadge_should_RenderOrdinarySyncingText_When_StateIsGrantedAndPendingCountIsNonZeroAndNotStuck() {
        val content = folderSyncBadgeContent(
            HostAccessState.Granted,
            dirName = "my-notes",
            pendingWriteCount = 2,
            hostWriteStuck = false,
        )

        assertEquals("2 changes syncing to my-notes", content?.text)
        assertTrue(content?.clickable == false, "ordinary in-flight syncing must not be clickable")
    }

    @Test
    fun folderSyncStatusBadge_should_TakePrecedenceOverSyncDegraded_When_StateIsDeniedEvenWithHostWriteStuckTrue() {
        // ux.md Principle 2 / Surface 3 precedence: Denied/PromptNeeded/Disconnected are
        // unconditional top-precedence rows — hostWriteStuck must never leak SyncDegraded copy
        // into a non-Granted state.
        val content = folderSyncBadgeContent(
            HostAccessState.Denied,
            dirName = "my-notes",
            pendingWriteCount = 2,
            hostWriteStuck = true,
        )

        assertEquals("Folder access declined — Grant access", content?.text)
    }

    @Test
    fun folderSyncStatusBadge_should_ProduceDistinctNonEmptyTextPerState_When_ComparedAcrossEveryImplementedBranch() {
        val texts = listOf(
            folderSyncBadgeContent(HostAccessState.Disconnected("x"), "n", 0)?.text,
            folderSyncBadgeContent(HostAccessState.Denied, "n", 0)?.text,
            folderSyncBadgeContent(HostAccessState.PromptNeeded, "n", 0)?.text,
            folderSyncBadgeContent(HostAccessState.Granted, "n", 2)?.text,
            folderSyncBadgeContent(HostAccessState.Granted, "n", 0)?.text,
        )

        assertTrue(texts.all { !it.isNullOrEmpty() })
        assertEquals(texts.size, texts.toSet().size, "every implemented state must render distinct copy")
    }
}
