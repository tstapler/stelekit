// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.desktop

import dev.stapler.stelekit.migration.MigrationRunner
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.runBlocking

/**
 * Developer CLI for migration operations.
 *
 * Usage: MigrationCli.run(args, runner, repoSet, graphId)
 *
 * Commands:
 *   validate              — validate migration DAG for structural errors
 *   dry-run               — show what would be applied without writing anything
 *   repair                — delete FAILED rows from the changelog
 *   baseline <migId>      — mark all migrations up to <migId> as APPLIED with 0 changes
 *   recalculate-checksums — recompute checksums for all APPLIED migrations
 */
object MigrationCli {

    fun run(
        args: Array<String>,
        runner: MigrationRunner,
        repoSet: RepositorySet,
        graphId: String,
    ) {
        when (val command = args.firstOrNull()) {
            "validate" -> {
                val errors = runner.validate()
                if (errors.isEmpty()) {
                    println("Migration DAG is valid.")
                } else {
                    errors.forEach { println("ERROR: $it") }
                }
            }

            "dry-run" -> runBlocking {
                val plan = runner.dryRun(graphId, repoSet)
                println("Dry-run for graph '$graphId':")
                println("  Already applied : ${plan.alreadyApplied}")
                println("  Would apply     : ${plan.wouldApply}")
                if (plan.pendingMigrations.isEmpty()) {
                    println("  No pending migrations.")
                } else {
                    plan.pendingMigrations.forEach { entry ->
                        val destructiveTag = if (entry.isDestructive) " [DESTRUCTIVE]" else ""
                        println("  - ${entry.migrationId}: ${entry.description}$destructiveTag")
                        println("    Changes: ${entry.plannedChanges.size}")
                    }
                }
            }

            "repair" -> runBlocking {
                val result = runner.repair(graphId)
                if (result.deletedCount == 0) {
                    println("No FAILED rows found for graph '$graphId'.")
                } else {
                    println("Deleted ${result.deletedCount} FAILED row(s): ${result.deletedIds}")
                }
            }

            "baseline" -> {
                val baselineId = args.getOrNull(1)
                if (baselineId == null) {
                    println("Usage: baseline <migrationId>")
                    return
                }
                runBlocking {
                    val result = runner.baseline(graphId, baselineId)
                    println("Baselined ${result.baselined} migration(s) up to '$baselineId' for graph '$graphId'.")
                }
            }

            "recalculate-checksums" -> runBlocking {
                println(
                    "WARNING: recalculate-checksums voids tamper-detection for all updated migrations. " +
                    "Only use this for intentional, non-semantic checksumBody changes (e.g. whitespace edits)."
                )
                val updated = runner.recalculateChecksums(graphId)
                if (updated.isEmpty()) {
                    println("No applied migrations found for graph '$graphId'.")
                } else {
                    println("Updated checksums for ${updated.size} migration(s): $updated")
                }
            }

            else -> println(
                "Unknown command: $command. " +
                "Available: validate, dry-run, repair, baseline <migrationId>, recalculate-checksums"
            )
        }
    }
}
