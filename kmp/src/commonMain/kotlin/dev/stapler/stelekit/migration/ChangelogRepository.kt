// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import kotlin.time.Clock

/**
 * Migration-time writer. Runs before [dev.stapler.stelekit.db.DatabaseWriteActor] exists;
 * direct SQL writes are intentional and safe here because migrations execute sequentially
 * on a single coroutine at startup before any concurrent writes are possible.
 */
@OptIn(DirectSqlWrite::class)
class ChangelogRepository(private val db: SteleDatabase) {
    private val restricted = RestrictedDatabaseQueries(db.steleDatabaseQueries)

    /** Returns id → checksum for all APPLIED migrations for this graph. */
    fun appliedIds(graphId: String): Map<String, String> {
        return db.steleDatabaseQueries
            .selectAppliedMigrations(graphId)
            .executeAsList()
            .associate { it.id to it.checksum }
    }

    suspend fun markRunning(id: String, graphId: String, order: Int, checksum: String, description: String) {
        restricted.insertMigrationRecord(
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

    suspend fun markApplied(id: String, graphId: String, executionMs: Long, changesApplied: Int) {
        restricted.updateMigrationStatus(
            status = MigrationStatus.APPLIED.name,
            error_message = null,
            execution_ms = executionMs,
            changes_applied = changesApplied.toLong(),
            id = id,
            graph_id = graphId,
        )
    }

    suspend fun markFailed(id: String, graphId: String, errorMessage: String) {
        restricted.updateMigrationStatus(
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

    suspend fun deleteRecord(id: String, graphId: String) {
        restricted.deleteMigrationRecord(id, graphId)
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
    suspend fun updateChecksum(id: String, graphId: String, newChecksum: String) {
        restricted.updateMigrationChecksum(
            checksum = newChecksum,
            id = id,
            graph_id = graphId,
        )
    }
}
