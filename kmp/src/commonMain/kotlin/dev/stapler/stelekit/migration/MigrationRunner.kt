// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

// ── Result / error types ───────────────────────────────────────────────────

class InterruptedMigrationException(message: String) : Exception(message)

class MigrationDagException(val errors: List<DagValidator.DagError>) :
    Exception("Migration DAG validation failed: $errors")

class MigrationTamperedError(message: String) : Exception(message)

data class RunResult(val applied: Int, val skipped: Int, val totalMs: Long)

data class RevertResult(val migrationId: String, val changesReverted: Int)

data class RepairResult(val deletedCount: Int, val deletedIds: List<String>)

data class BaselineResult(val baselined: Int)

// ── MigrationRunner ────────────────────────────────────────────────────────

/**
 * Central coordinator that runs pending migrations against a graph, validates
 * DAG integrity, detects tampered checksums, and delegates writes to
 * [ChangeApplier] via the serialized [dev.stapler.stelekit.db.DatabaseWriteActor].
 *
 * @param registry source of all registered [Migration] objects in declaration order
 * @param changelogRepo persistent record of which migrations have run for each graph
 * @param evaluator translates a [Migration.apply] lambda into a [List] of [BlockChange]s
 * @param applier writes [BlockChange]s through the write-actor and returns a [ChangeSummary]
 * @param flusher flushes touched pages back to disk after all migrations complete; nullable
 *   so the runner can be used in contexts where no [dev.stapler.stelekit.db.GraphWriter] is available
 * @param dagValidator validates the migration graph for cycles and unresolved deps
 */
class MigrationRunner(
    private val registry: MigrationRegistry,
    private val changelogRepo: ChangelogRepository,
    private val evaluator: DslEvaluator,
    private val applier: ChangeApplier,
    private val flusher: PostMigrationFlusher? = null,
    private val dagValidator: DagValidator = DagValidator,
) {

    private val logger = Logger("MigrationRunner")

    /**
     * Applies all pending migrations for [graphId] in registry order.
     *
     * Steps:
     * 1. Load already-applied IDs from the changelog.
     * 2. Detect RUNNING rows (interrupted prior run) — throw [InterruptedMigrationException].
     * 3. Validate the DAG — throw [MigrationDagException] on errors.
     * 4. Verify checksums of already-applied migrations — throw [MigrationTamperedError] on mismatch.
     * 5. For each pending migration: mark RUNNING → evaluate → apply → mark APPLIED.
     * 6. On exception during step 5: mark FAILED, re-throw.
     * 7. Flush touched pages to disk via [PostMigrationFlusher] (if present).
     *
     * @return [RunResult] summarising how many migrations were applied and skipped.
     */
    suspend fun runPending(graphId: String, repoSet: RepositorySet, graphPath: String): RunResult {
        val startMs = Clock.System.now().toEpochMilliseconds()

        // 1. Load applied IDs (id → checksum)
        val appliedIds: Map<String, String> = changelogRepo.appliedIds(graphId)

        // 2. Detect interrupted (RUNNING) migrations
        val running = changelogRepo.runningMigrations(graphId)
        if (running.isNotEmpty()) {
            val runningId = running.first()
            throw InterruptedMigrationException(
                "Migration '$runningId' was interrupted. Run repair() to fix."
            )
        }

        // 3. Validate DAG
        val dagErrors = dagValidator.validate(registry.all(), appliedIds.keys.toSet())
        if (dagErrors.isNotEmpty()) {
            throw MigrationDagException(dagErrors)
        }

        // 4. Verify checksums of already-applied migrations
        verifyChecksums(appliedIds)

        // 5. Apply pending migrations
        val touchedPageUuids = mutableSetOf<String>()
        var appliedCount = 0
        val skippedCount = appliedIds.size
        val pending = registry.all().filter { it.id !in appliedIds }

        logger.info("MigrationRunner: starting run for graph '$graphId' — ${pending.size} pending, $skippedCount already applied")

        for ((order, migration) in registry.all().withIndex()) {
            if (migration.id in appliedIds) continue

            val checksum = MigrationChecksumComputer.compute(migration.checksumBody)
            changelogRepo.markRunning(
                id = migration.id,
                graphId = graphId,
                order = order,
                checksum = checksum,
                description = migration.description,
            )

            val migrationStartMs = Clock.System.now().toEpochMilliseconds()
            logger.info("MigrationRunner: applying '${migration.id}' — ${migration.description}")
            try {
                val changes = evaluator.evaluate(migration)
                val summary = applier.apply(changes, repoSet)

                // Surface any partial write failures — a migration with failed changes must
                // not be silently marked APPLIED with an incomplete dataset.
                if (summary.failed.isNotEmpty()) {
                    throw IllegalStateException(
                        "Migration '${migration.id}' completed with ${summary.failed.size} failed " +
                        "change(s): ${summary.failed}"
                    )
                }

                // Track page UUIDs touched by this migration for post-flush.
                collectTouchedPageUuids(changes, repoSet, touchedPageUuids)

                val executionMs = Clock.System.now().toEpochMilliseconds() - migrationStartMs
                changelogRepo.markApplied(
                    id = migration.id,
                    graphId = graphId,
                    executionMs = executionMs,
                    changesApplied = summary.applied,
                )
                logger.info("MigrationRunner: '${migration.id}' applied ${summary.applied} change(s) in ${executionMs}ms")
                appliedCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 6. Mark failed and re-throw
                changelogRepo.markFailed(
                    id = migration.id,
                    graphId = graphId,
                    errorMessage = e.message ?: "unknown",
                )
                logger.error("MigrationRunner: '${migration.id}' FAILED — ${e.message}", e)
                throw e
            }
        }

        // 7. Flush touched pages to disk
        if (flusher != null && touchedPageUuids.isNotEmpty()) {
            flusher.flush(repoSet, touchedPageUuids, graphPath)
        }

        val totalMs = Clock.System.now().toEpochMilliseconds() - startMs
        logger.info("MigrationRunner: complete for graph '$graphId' — applied=$appliedCount skipped=$skippedCount totalMs=$totalMs")
        return RunResult(applied = appliedCount, skipped = skippedCount, totalMs = totalMs)
    }

    /**
     * Reverts a single migration by running its [Migration.revert] lambda and
     * deleting its changelog record.
     *
     * @throws IllegalArgumentException if the migration ID is not found in the registry.
     * @throws UnsupportedOperationException if the migration has no revert lambda.
     */
    suspend fun revert(migrationId: String, graphId: String, repoSet: RepositorySet): RevertResult {
        val migration = registry.all().firstOrNull { it.id == migrationId }
            ?: throw IllegalArgumentException("Migration '$migrationId' not found in registry")

        val revertLambda = migration.revert
            ?: throw UnsupportedOperationException("Migration '$migrationId' has no revert lambda")

        val revertMigration = Migration(
            id = migration.id,
            description = "Revert: ${migration.description}",
            checksumBody = migration.checksumBody,
            apply = revertLambda,
        )

        val changes = evaluator.evaluate(revertMigration)
        val summary = applier.apply(changes, repoSet)

        changelogRepo.deleteRecord(migrationId, graphId)

        return RevertResult(migrationId = migrationId, changesReverted = summary.applied)
    }

    /**
     * Performs a dry run: evaluates all pending migrations without writing anything.
     * Returns a [MigrationPlan] describing what would be applied.
     *
     * Steps mirror [runPending] but no changelog records are created and no
     * [ChangeApplier] calls are made.
     */
    suspend fun dryRun(graphId: String, _repoSet: RepositorySet): MigrationPlan {
        val appliedIds = changelogRepo.appliedIds(graphId)

        // Validate DAG
        val dagErrors = dagValidator.validate(registry.all(), appliedIds.keys.toSet())
        if (dagErrors.isNotEmpty()) throw MigrationDagException(dagErrors)

        // Verify checksums for already-applied migrations
        verifyChecksums(appliedIds)

        // Evaluate pending migrations without writing
        val entries = mutableListOf<MigrationPlanEntry>()
        for (migration in registry.all()) {
            if (migration.id in appliedIds) continue
            val changes = evaluator.evaluate(migration)
            val isDestructive = changes.any { it is BlockChange.DeleteBlock || it is BlockChange.DeletePage }
            entries.add(
                MigrationPlanEntry(
                    migrationId = migration.id,
                    description = migration.description,
                    plannedChanges = changes,
                    isDestructive = isDestructive,
                )
            )
        }

        return MigrationPlan(
            pendingMigrations = entries,
            alreadyApplied = appliedIds.size,
            wouldApply = entries.size,
            wouldSkip = 0,
        )
    }

    /**
     * Deletes all FAILED rows from the changelog for [graphId].
     *
     * @throws [InterruptedMigrationException] if any RUNNING rows exist — the developer
     *   must investigate the interrupted migration before repair is safe.
     */
    suspend fun repair(graphId: String): RepairResult {
        val running = changelogRepo.runningMigrations(graphId)
        if (running.isNotEmpty()) {
            throw InterruptedMigrationException(
                "Cannot repair: migration(s) $running are in RUNNING state. " +
                "Investigate the interrupted migration before running repair."
            )
        }
        val failedIds = changelogRepo.failedMigrations(graphId)
        failedIds.forEach { id -> changelogRepo.deleteRecord(id, graphId) }
        return RepairResult(deletedCount = failedIds.size, deletedIds = failedIds)
    }

    /**
     * Marks all migrations up to and including [baselineId] as APPLIED in the changelog
     * with `changes_applied = 0`. Used for existing graphs that pre-date the migration
     * framework so subsequent runs know these migrations are already satisfied.
     *
     * @throws IllegalArgumentException if [baselineId] is not found in the registry.
     * @throws IllegalStateException if any of the migrations to be baselined are already
     *   present in the changelog.
     */
    suspend fun baseline(graphId: String, baselineId: String): BaselineResult {
        val appliedIds = changelogRepo.appliedIds(graphId)
        val allMigrations = registry.all()
        val baselineIndex = allMigrations.indexOfFirst { it.id == baselineId }
        require(baselineIndex >= 0) { "Migration '$baselineId' not found in registry" }

        val toBaseline = allMigrations.subList(0, baselineIndex + 1)
        val alreadyPresent = toBaseline.filter { it.id in appliedIds }
        if (alreadyPresent.isNotEmpty()) {
            error(
                "Cannot baseline: migrations ${alreadyPresent.map { it.id }} already in changelog"
            )
        }

        toBaseline.forEachIndexed { index, migration ->
            val checksum = MigrationChecksumComputer.compute(migration.checksumBody)
            changelogRepo.markRunning(
                id = migration.id,
                graphId = graphId,
                order = index,
                checksum = checksum,
                description = migration.description,
            )
            changelogRepo.markApplied(
                id = migration.id,
                graphId = graphId,
                executionMs = 0L,
                changesApplied = 0,
            )
        }
        return BaselineResult(baselined = toBaseline.size)
    }

    /**
     * For each APPLIED migration in the changelog, recomputes the checksum from the current
     * [Migration.checksumBody] and updates the stored value.
     *
     * Used when a migration's `checksumBody` has been legitimately changed (e.g. comment update).
     *
     * **Warning**: calling this voids the tamper-detection guarantee for every updated migration.
     * Only use this for intentional, non-semantic changes to `checksumBody` (e.g. whitespace edits).
     *
     * @return list of migration IDs whose stored checksum was updated.
     */
    suspend fun recalculateChecksums(graphId: String): List<String> {
        val appliedIds = changelogRepo.appliedIds(graphId)
        val updated = mutableListOf<String>()
        for (migration in registry.all()) {
            if (migration.id !in appliedIds) continue
            val newChecksum = MigrationChecksumComputer.compute(migration.checksumBody)
            changelogRepo.updateChecksum(migration.id, graphId, newChecksum)
            updated.add(migration.id)
        }
        return updated
    }

    /**
     * Validates the full migration registry for DAG correctness.
     * Does not consult the changelog — this checks structural invariants only.
     */
    fun validate(): List<DagValidator.DagError> = dagValidator.validate(registry.all())

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Verifies that every already-applied migration's stored checksum matches
     * what we would compute today. Throws [MigrationTamperedError] on the first mismatch.
     */
    private fun verifyChecksums(appliedIds: Map<String, String>) {
        for (migration in registry.all()) {
            val storedChecksum = appliedIds[migration.id] ?: continue
            val computed = MigrationChecksumComputer.compute(migration.checksumBody)
            if (computed != storedChecksum) {
                throw MigrationTamperedError(
                    "Migration '${migration.id}' checksum mismatch: expected $storedChecksum but computed $computed"
                )
            }
        }
    }

    /**
     * Collects page UUIDs from [changes] into [dest].
     * Page-level changes carry a pageUuid directly.
     * Block-level changes require a repository lookup.
     */
    private suspend fun collectTouchedPageUuids(
        changes: List<BlockChange>,
        repoSet: RepositorySet,
        dest: MutableSet<String>,
    ) {
        for (change in changes) {
            when (change) {
                is BlockChange.UpsertPageProperty -> dest.add(change.pageUuid)
                is BlockChange.DeletePageProperty -> dest.add(change.pageUuid)
                is BlockChange.RenamePage -> dest.add(change.pageUuid)
                is BlockChange.InsertBlock -> dest.add(change.block.pageUuid)
                is BlockChange.DeletePage -> { /* page deleted — nothing to flush */ }
                is BlockChange.UpsertProperty -> resolveBlockPageUuid(change.blockUuid, repoSet, dest)
                is BlockChange.DeleteProperty -> resolveBlockPageUuid(change.blockUuid, repoSet, dest)
                is BlockChange.SetContent -> resolveBlockPageUuid(change.blockUuid, repoSet, dest)
                is BlockChange.DeleteBlock -> resolveBlockPageUuid(change.blockUuid, repoSet, dest)
            }
        }
    }

    private suspend fun resolveBlockPageUuid(
        blockUuid: String,
        repoSet: RepositorySet,
        dest: MutableSet<String>,
    ) {
        val block = repoSet.blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
        if (block != null) dest.add(block.pageUuid)
    }
}
