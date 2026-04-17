// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.db.SteleDatabase
import kotlin.time.Clock

class ChangelogRepository(private val db: SteleDatabase) {

    /** Returns id → checksum for all APPLIED migrations for this graph. */
    fun appliedIds(graphId: String): Map<String, String> {
        return db.steleDatabaseQueries
            .selectAppliedMigrations(graphId)
            .executeAsList()
            .associate { it.id to it.checksum }
    }

    fun markRunning(id: String, graphId: String, order: Int, checksum: String, description: String) {
        db.steleDatabaseQueries.insertMigrationRecord(
            id = id,
            graph_id = graphId,
            description = description,
            checksum = checksum,
            applied_at = Clock.System.now().toEpochMilliseconds(),
            execution_ms = 0L,
            status = MigrationStatus.RUNNING.name,
            applied_by = "",
            execution_order = order.toLong(),
            changes_applied = 0L,
            error_message = null,
        )
    }

    fun markApplied(id: String, graphId: String, executionMs: Long, changesApplied: Int) {
        db.steleDatabaseQueries.updateMigrationStatus(
            status = MigrationStatus.APPLIED.name,
            error_message = null,
            execution_ms = executionMs,
            changes_applied = changesApplied.toLong(),
            id = id,
            graph_id = graphId,
        )
    }

    fun markFailed(id: String, graphId: String, errorMessage: String) {
        db.steleDatabaseQueries.updateMigrationStatus(
            status = MigrationStatus.FAILED.name,
            error_message = errorMessage,
            execution_ms = 0L,
            changes_applied = 0L,
            id = id,
            graph_id = graphId,
        )
    }

    fun runningMigrations(graphId: String): List<String> {
        return db.steleDatabaseQueries
            .selectRunningMigrations(graphId)
            .executeAsList()
            .map { it.id }
    }

    fun deleteRecord(id: String, graphId: String) {
        db.steleDatabaseQueries.deleteMigrationRecord(id, graphId)
    }

    /** Returns IDs of all FAILED migrations for this graph (filtered in memory). */
    fun failedMigrations(graphId: String): List<String> {
        return db.steleDatabaseQueries
            .selectAllMigrationsForGraph(graphId)
            .executeAsList()
            .filter { it.status == MigrationStatus.FAILED.name }
            .map { it.id }
    }

    /**
     * Updates the stored checksum for [id] to [newChecksum].
     *
     * Used when a migration's `checksumBody` has been legitimately changed (e.g. a comment edit).
     * Calling this voids the tamper-detection guarantee for the updated migration — use with care.
     */
    fun updateChecksum(id: String, graphId: String, newChecksum: String) {
        db.steleDatabaseQueries.updateMigrationChecksum(
            checksum = newChecksum,
            id = id,
            graph_id = graphId,
        )
    }
}
