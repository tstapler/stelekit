// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Migration-time writer. Runs before [dev.stapler.stelekit.db.DatabaseWriteActor] exists;
 * direct SQL writes are intentional and safe here because migrations execute sequentially
 * on a single coroutine at startup before any concurrent writes are possible.
 */
@OptIn(DirectSqlWrite::class)
class ChangelogRepository(private val db: SteleDatabase) {
    private val restricted = RestrictedDatabaseQueries(db.steleDatabaseQueries)

    /** Returns id → checksum for all APPLIED migrations for this graph. */
    suspend fun appliedIds(graphId: String): Map<String, String> {
        return db.steleDatabaseQueries
            .selectAppliedMigrations(graphId)
            .asFlow().mapToList(PlatformDispatcher.DB).first()
            .associate { it.id to it.checksum }
    }

    suspend fun markRunning(id: String, graphId: String, order: Int, checksum: String, description: String) {
        try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // migration_changelog's primary key is (id, graph_id). A conflict here means another
            // switchGraph() call is already applying this exact migration for this exact graph
            // concurrently (e.g. GraphManager's startup restore racing a UI-triggered switch) —
            // not a real failure. Surface a clear, expected exception instead of a raw driver
            // exception with a platform-specific type (SQLiteException on JVM,
            // SQLiteConstraintException on Android), matched by message text for portability.
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("unique constraint") || msg.contains("primary key constraint")) {
                throw ConcurrentMigrationRunException(
                    "Migration '$id' for graph '$graphId' is already running or applied " +
                    "(concurrent switchGraph call) — skipping this run."
                )
            }
            throw e
        }
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

    suspend fun runningMigrations(graphId: String): List<String> {
        return db.steleDatabaseQueries
            .selectRunningMigrations(graphId)
            .asFlow().mapToList(PlatformDispatcher.DB).first()
            .map { it.id }
    }

    suspend fun deleteRecord(id: String, graphId: String) {
        restricted.deleteMigrationRecord(id, graphId)
    }

    /** Returns IDs of all FAILED migrations for this graph (filtered in memory). */
    suspend fun failedMigrations(graphId: String): List<String> {
        return db.steleDatabaseQueries
            .selectAllMigrationsForGraph(graphId)
            .asFlow().mapToList(PlatformDispatcher.DB).first()
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
