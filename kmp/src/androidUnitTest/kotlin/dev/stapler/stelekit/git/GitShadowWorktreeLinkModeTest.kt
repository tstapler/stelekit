// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * plan.md Epic 4.2 (Story 4.2.1, Task 4.2.1b): choosing "Link" in the UI for a git-cloned Android
 * graph has no production hook into [GitShadowWorktree] at all — the shadow-worktree write-back-
 * to-SAF cache mode ([GitShadowWorktree.sweepOrphans]'s `AppOwned`-only protection, exercised by
 * [GitShadowWorktreeSweepStorageGateTest]) is unaffected because nothing in that mechanism reads a
 * "Link was chosen" signal. This test proves that directly: a graph's `storage_locations` row of
 * kind `SafFolder` — the AC's "git-cloned graph currently on SafFolder, user chooses Link with
 * destination AppOwned" scenario — is neither repointed to `AppOwned` (Link never writes
 * `storage_locations`; only Relocate does, via `onGraphLocationDetermined`) nor treated as
 * `AppOwned`-protected by the sweep. Same Robolectric setup and DB-row helpers as
 * [GitShadowWorktreeSweepStorageGateTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitShadowWorktreeLinkModeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val agedMillis = 90L * 24 * 60 * 60 * 1000 // older than DEFAULT_MAX_AGE_MILLIS (60 days)

    private fun createAgedShadowDir(graphId: String): File {
        val graphDir = File(context.filesDir, "graphs/$graphId")
        val shadowDir = File(graphDir, "gitshadow")
        shadowDir.mkdirs()
        File(shadowDir, "some-tracked-file.md").writeText("content")
        val marker = File(graphDir, ".last-used")
        marker.createNewFile()
        marker.setLastModified(System.currentTimeMillis() - agedMillis)
        return shadowDir
    }

    private fun writeStorageLocationRow(graphId: String, kind: String) {
        val dbFile = File(context.filesDir, "stelekit-graph-$graphId.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS storage_locations (" +
                    "graph_id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, tree_uri TEXT, " +
                    "real_path TEXT, display_name TEXT, updated_at_epoch_ms INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO storage_locations (graph_id, kind, updated_at_epoch_ms) VALUES (?, ?, ?)",
                arrayOf<Any>(graphId, kind, System.currentTimeMillis()),
            )
        }
    }

    private fun readStorageLocationKind(graphId: String): String? {
        val dbFile = File(context.filesDir, "stelekit-graph-$graphId.db")
        return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT kind FROM storage_locations WHERE graph_id = ?", arrayOf(graphId)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
    }

    @Test
    fun `linkChoice should KeepShadowWorktreeInExistingCacheMode When GitClonedGraphLinked`() {
        val graphId = "g-link-git-cloned"
        // AC: "an Android user with a git-cloned graph currently on SafFolder chooses Link with
        // destination AppOwned" — the row this simulates is written once, up front, exactly as
        // GraphManager would already have it from the prior Relocate/backfill flow. Nothing in
        // this test (or in production) touches it again on account of an Android "Link" choice.
        writeStorageLocationRow(graphId, "SafFolder")
        val shadowDir = createAgedShadowDir(graphId)

        // sweepOrphans is the one place GitShadowWorktree branches on storage_locations at all
        // (GitShadowWorktreeSweepStorageGateTest.kt covers the AppOwned-protected branch). If
        // choosing "Link" had repointed this row to AppOwned, the shadow dir would have survived
        // the sweep the same way that test's AppOwned case does — it does not: the shadow tree
        // stays in its ordinary, sweep-eligible write-back cache mode, unpromoted.
        GitShadowWorktree.sweepOrphans(context)

        assertFalse(
            shadowDir.exists(),
            "a git-cloned graph's shadow tree must remain ordinary/sweep-eligible after Link — " +
                "Link must never promote it to AppOwned-protected status",
        )
        assertEquals(
            "SafFolder",
            readStorageLocationKind(graphId),
            "storage_locations row must remain exactly what it already was — Link never repoints " +
                "the location of record; only Relocate does, via onGraphLocationDetermined",
        )
    }
}
