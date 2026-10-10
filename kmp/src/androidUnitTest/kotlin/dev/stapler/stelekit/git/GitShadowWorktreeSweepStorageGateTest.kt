// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-002 / plan.md Epic 1.2 (Story 1.2.1, Task 1.2.1c): [GitShadowWorktree.sweepOrphans]'s
 * `StorageLocation`-aware guard. Same Robolectric setup as [GitShadowWorktreeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitShadowWorktreeSweepStorageGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val agedMillis = 90L * 24 * 60 * 60 * 1000 // older than DEFAULT_MAX_AGE_MILLIS (60 days)

    /** Creates `context.filesDir/graphs/$graphId/gitshadow` with an aged `.last-used` marker. */
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

    /** Writes a real `storage_locations` row for [graphId] into its own per-graph SQLite file. */
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

    /** Writes garbage bytes so opening this graph's DB file as SQLite throws. */
    private fun corruptGraphDb(graphId: String) {
        val dbFile = File(context.filesDir, "stelekit-graph-$graphId.db")
        dbFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    }

    @Test
    fun `sweepOrphans should SkipDeletion When StorageLocationIsAppOwnedRegardlessOfMarkerAge`() {
        val graphId = "g2"
        val shadowDir = createAgedShadowDir(graphId)
        writeStorageLocationRow(graphId, "AppOwned")

        GitShadowWorktree.sweepOrphans(context)

        assertTrue(shadowDir.exists(), "AppOwned graph's shadow directory must survive the sweep")
    }

    @Test
    fun `sweepOrphans should DeleteAsBeforeFeature When NoStorageLocationRowExistsAndMarkerAged`() {
        val graphId = "g3"
        val shadowDir = createAgedShadowDir(graphId)
        // No `stelekit-graph-g3.db` file at all — every graph that predates this migration.

        GitShadowWorktree.sweepOrphans(context)

        assertFalse(shadowDir.exists(), "no storage_locations row must not change pre-existing sweep behavior")
    }

    @Test
    fun `sweepOrphans should TreatLookupFailureAsDoNotDeleteAndContinueOtherDirectories When SelectStorageLocationThrows`() {
        val throwingGraphId = "g4"
        val normalGraphId = "g5"
        val throwingShadowDir = createAgedShadowDir(throwingGraphId)
        corruptGraphDb(throwingGraphId)
        val normalShadowDir = createAgedShadowDir(normalGraphId)
        // g5 has no storage_locations row/db — a normal, sweepable directory.

        GitShadowWorktree.sweepOrphans(context)

        assertTrue(throwingShadowDir.exists(), "a lookup failure must fail safe and leave that directory untouched")
        assertFalse(normalShadowDir.exists(), "a lookup failure for one graph must not abort the sweep for others")
    }
}
