// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

/**
 * All registered SteleKit content migrations, in deployment order.
 *
 * Rules:
 * - Never modify or delete an existing migration — add a new one instead (Sqitch rework pattern)
 * - Use timestamp-based IDs: V{YYYYMMDD}{NNN}__description (e.g. V20260414001__add-block-type-property)
 * - Declare `requires` for any migration that depends on prior state
 * - Set `checksumBody` to the canonical string that represents this migration's intent
 */
fun registerAllMigrations() {
    MigrationRegistry.registerAll(
        V20260414001_baseline
    )
}

/**
 * Baseline migration — establishes the initial graph state contract.
 * Applied to all graphs on first framework run. Zero writes; pure audit record.
 */
val V20260414001_baseline = migration("V20260414001__baseline") {
    description = "Baseline: establish initial graph state for migration tracking"
    checksumBody = "V20260414001__baseline: no-op baseline migration"
    apply {
        // No-op: this migration just establishes the baseline in the changelog
        // Future migrations that need to run AFTER initial graph setup declare requires = ["V20260414001__baseline"]
    }
}
